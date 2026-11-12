//! SOCKS4/5 proxy with ByeDPI's auto-group logic (port of `proxy.c` and
//! `extend.c`) on tokio. One task per client connection.

use std::collections::HashMap;
use std::io;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};
use std::os::fd::AsRawFd;
use std::sync::atomic::Ordering;
use std::sync::Arc;
use std::time::{Duration, Instant};

use parking_lot::Mutex;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpSocket, TcpStream, UdpSocket};
use tokio::sync::{mpsc, Semaphore};
use tokio_util::sync::CancellationToken;

use crate::desync::{self, send_all};
use crate::packets::*;
use crate::params::*;

const S_VER5: u8 = 5;
const S_VER4: u8 = 4;
const S_CMD_CONN: u8 = 1;
const S_CMD_AUDP: u8 = 3;
const S_ATP_I4: u8 = 1;
const S_ATP_ID: u8 = 3;
const S_ATP_I6: u8 = 4;
const S_ER_GEN: u8 = 1;
const S_ER_NET: u8 = 3;
const S_ER_HOST: u8 = 4;
const S_ER_CONN: u8 = 5;
const S_ER_CMD: u8 = 7;
const S_ER_ATP: u8 = 8;

#[derive(Clone, Copy)]
struct Slot {
    gid: usize,
    detect: u32,
    auto_level: u32,
}

struct CacheEnt {
    gid: usize,
    mask: u64,
    detect: u32,
    at: Instant,
}

pub struct Engine {
    pub p: Params,
    order: Mutex<Vec<Slot>>,
    cache: Mutex<HashMap<SocketAddr, CacheEnt>>,
    def_ttl: i32,
}

#[derive(Clone, Copy, PartialEq)]
enum Flag {
    S4,
    S5,
}

/// Per-client group selection state (`dp`, `dp_mask`, `detect` of the C eval).
#[derive(Default)]
struct Sel {
    gid: Option<usize>,
    mask: u64,
    detect: u32,
}

fn bit(gid: usize) -> u64 {
    1u64 << gid.min(63)
}

fn check_round(nr: (i32, i32), r: i32) -> bool {
    (nr.1 == 0 && r <= 1) || (r >= nr.0 && r <= nr.1)
}

fn canon(ip: IpAddr) -> IpAddr {
    match ip {
        IpAddr::V6(v6) => match v6.to_ipv4_mapped() {
            Some(v4) => IpAddr::V4(v4),
            None => IpAddr::V6(v6),
        },
        v4 => v4,
    }
}

fn check_proto_tcp(proto: u32, b: &[u8]) -> bool {
    if proto & !IS_IPV4 == 0 {
        return true;
    }
    (proto & IS_HTTP != 0 && is_http(b)) || (proto & IS_HTTPS != 0 && is_tls_chello(b))
}

fn check_host(hosts: &HostSet, b: &[u8]) -> bool {
    match find_host(b) {
        Some((p, l)) => hosts.matches(&b[p..p + l]),
        None => false,
    }
}

fn check_l34(g: &Group, udp: bool, dst: &SocketAddr) -> bool {
    if g.proto & IS_UDP != 0 && !udp {
        return false;
    }
    if g.proto & IS_TCP != 0 && udp {
        return false;
    }
    if g.proto & IS_IPV4 != 0 && !canon(dst.ip()).is_ipv4() {
        return false;
    }
    if g.pf.0 != 0 && (dst.port() < g.pf.0 || dst.port() > g.pf.1.max(g.pf.0)) {
        return false;
    }
    if let Some(set) = &g.ipset {
        if !set.contains(dst.ip()) {
            return false;
        }
    }
    true
}

fn errno_of(e: &io::Error) -> i32 {
    e.raw_os_error().unwrap_or(0)
}

fn is_conn_err(e: i32) -> bool {
    matches!(e, libc::ECONNRESET | libc::ECONNREFUSED | libc::ETIMEDOUT | libc::EHOSTUNREACH)
}

impl Engine {
    pub fn new(p: Params) -> Arc<Self> {
        let order = p
            .groups
            .iter()
            .map(|g| Slot { gid: g.id, detect: g.detect, auto_level: g.auto_level })
            .collect();
        let def_ttl = if p.custom_ttl { p.def_ttl } else { desync::system_default_ttl() };
        Arc::new(Engine { p, order: Mutex::new(order), cache: Mutex::new(HashMap::new()), def_ttl })
    }

    fn group(&self, gid: usize) -> &Group {
        &self.p.groups[gid]
    }

    fn cache_get(&self, dst: &SocketAddr) -> Option<(usize, u64, u32)> {
        let mut c = self.cache.lock();
        let e = c.get(dst)?;
        let ttl = self.group(e.gid).cache_ttl;
        if ttl > 0 && e.at.elapsed().as_secs() as i64 > ttl {
            c.remove(dst);
            return None;
        }
        Some((e.gid, e.mask, e.detect))
    }

    /// ByeDPI `find_dp`: next usable group in list order, starting at the
    /// client's current one and wrapping around.
    fn find_dp(&self, sel: &mut Sel, buf: Option<&[u8]>, dst: &SocketAddr) -> Option<usize> {
        let order = self.order.lock();
        let n = order.len();
        let init = sel.gid;
        let mut idx = init.and_then(|g| order.iter().position(|s| s.gid == g)).unwrap_or(0);
        loop {
            if sel.mask & self.p.full_mask == self.p.full_mask {
                return None;
            }
            let slot = order[idx];
            let g = self.group(slot.gid);
            let b = bit(slot.gid);
            if b & sel.mask == 0
                && (Some(slot.gid) == init || slot.detect == 0 || sel.detect & slot.detect != 0)
                && check_l34(g, false, dst)
                && (g.proto == 0 || buf.map_or(true, |d| check_proto_tcp(g.proto, d)))
                && (g.hosts.is_none() || buf.map_or(true, |d| check_host(g.hosts.as_ref().unwrap(), d)))
            {
                return Some(slot.gid);
            }
            sel.mask |= b;
            idx = (idx + 1) % n;
        }
    }

    /// ByeDPI `on_trigger`: marks the current group as failed for this
    /// destination, remembers the next candidate in the cache and returns it
    /// when the connection should be re-established with it.
    fn on_trigger(&self, typ: u32, sel: &mut Sel, dst: &SocketAddr, can_reconn: bool) -> Option<usize> {
        let cur = sel.gid?;
        let g = self.group(cur);
        g.fail_count.fetch_add(1, Ordering::Relaxed);
        sel.mask |= bit(cur);
        sel.detect = typ;

        let mut order = self.order.lock();
        let mut cache = self.cache.lock();
        let first = order[0].gid;
        let ent = cache
            .entry(*dst)
            .or_insert(CacheEnt { gid: first, mask: 0, detect: 0, at: Instant::now() });
        ent.at = Instant::now();

        let mut unchecked = sel.mask;
        let mut next: Option<usize> = None;
        for (pos, slot) in order.iter().enumerate() {
            if unchecked == 0 && slot.detect == 0 {
                break;
            }
            let b = bit(slot.gid);
            if b & sel.mask == 0
                && (slot.detect == 0 || slot.detect & typ != 0)
                && (slot.auto_level & AUTO_ONRECONN == 0 || can_reconn)
            {
                next = Some(pos);
                break;
            }
            unchecked &= !b;
            sel.mask |= b;
        }
        if bit(cur) & ent.mask == 0 {
            if let Some(np) = next {
                let nslot = order[np];
                let cpri = g.pri.load(Ordering::Relaxed);
                let npri = self.group(nslot.gid).pri.load(Ordering::Relaxed);
                if nslot.auto_level & AUTO_SORT != 0 && cpri > npri && bit(cur) & unchecked == 0 {
                    if let Some(cp) = order.iter().position(|s| s.gid == cur) {
                        // swop_groups(): positions keep their detect/auto_level
                        order[np].gid = cur;
                        order[cp].gid = nslot.gid;
                        log::debug!("swop: {} <-> {}", cur, nslot.gid);
                    }
                }
            }
            g.pri.fetch_add(1, Ordering::Relaxed);
        }
        match next {
            None => {
                log::debug!("unreach ip: {}", dst);
                ent.mask = 0;
                ent.detect = 0;
                ent.gid = order[0].gid;
                None
            }
            Some(np) => {
                let slot = order[np];
                log::debug!("save: ip={}, group={}", dst, slot.gid);
                ent.mask |= sel.mask;
                ent.detect = sel.detect;
                ent.gid = slot.gid;
                if slot.auto_level & AUTO_NORECONN == 0 && can_reconn {
                    Some(slot.gid)
                } else {
                    None
                }
            }
        }
    }

    /// ByeDPI `on_response`: checks the first answer of the server against
    /// the remaining groups' detectors.
    fn check_response(&self, sel: &mut Sel, req: &[u8], resp: &[u8]) -> Option<u32> {
        if let Some(cur) = sel.gid {
            sel.mask |= bit(cur);
        }
        let order = self.order.lock();
        for slot in order.iter() {
            if bit(slot.gid) & sel.mask != 0 {
                continue;
            }
            if slot.detect & DETECT_HTTP_LOCAT != 0 && is_http_redirect(req, resp) {
                return Some(slot.detect);
            }
            if slot.detect & DETECT_TLS_ERR != 0
                && ((is_tls_chello(req) && !is_tls_shello(resp)) || neq_tls_sid(req, resp))
            {
                return Some(slot.detect);
            }
        }
        None
    }

    fn udp_group(&self, dst: &SocketAddr) -> Option<usize> {
        let order = self.order.lock();
        order
            .iter()
            .find(|s| s.detect == 0 && check_l34(self.group(s.gid), true, dst))
            .map(|s| s.gid)
    }

    // ------------------------------------------------------------ sockets

    async fn connect_remote(&self, dst: SocketAddr) -> io::Result<TcpStream> {
        let dst = SocketAddr::new(canon(dst.ip()), dst.port());
        let sock = if dst.is_ipv4() { TcpSocket::new_v4()? } else { TcpSocket::new_v6()? };
        let fd = sock.as_raw_fd();
        if self.p.custom_ttl {
            desync::setttl(fd, self.p.def_ttl)?;
        }
        let _ = desync::set_syncnt(fd, 1);
        sock.set_nodelay(true)?;
        if let Some(ip) = self.p.bind_ip {
            if ip.is_ipv4() == dst.is_ipv4() {
                sock.bind(SocketAddr::new(ip, 0))?;
            }
        }
        // TCP_SYNCNT=1 already bounds the handshake to ~3 s; the timeout is a
        // safety net for kernels that ignore it.
        match tokio::time::timeout(Duration::from_secs(10), sock.connect(dst)).await {
            Ok(r) => r,
            Err(_) => Err(io::Error::from_raw_os_error(libc::ETIMEDOUT)),
        }
    }

    /// connect_hook + on_connect error path: picks a group and connects,
    /// moving to the next group on connection errors (`-A c`).
    async fn open(&self, sel: &mut Sel, dst: SocketAddr) -> Result<TcpStream, i32> {
        loop {
            let gid = match self.find_dp(sel, None, &dst) {
                Some(g) => g,
                None => {
                    log::debug!("drop connection");
                    return Err(libc::ECONNREFUSED);
                }
            };
            sel.gid = Some(gid);
            match self.connect_remote(dst).await {
                Ok(s) => return Ok(s),
                Err(e) => {
                    let en = errno_of(&e);
                    log::debug!("connect {}: {}", dst, e);
                    if matches!(en, libc::ECONNRESET | libc::ECONNREFUSED | libc::ETIMEDOUT)
                        && self.on_trigger(DETECT_CONNECT, sel, &dst, true).is_some()
                    {
                        continue;
                    }
                    return Err(en);
                }
            }
        }
    }

    async fn resolve(&self, host: &str, port: u16) -> Option<SocketAddr> {
        if !self.p.resolve {
            return None;
        }
        let it = tokio::net::lookup_host((host, port)).await.ok()?;
        let mut v6 = None;
        for a in it {
            if a.is_ipv4() {
                return Some(a);
            }
            if self.p.ipv6 && v6.is_none() {
                v6 = Some(a);
            }
        }
        v6
    }

    // ------------------------------------------------------------ server

    pub async fn run(self: Arc<Self>, cancel: CancellationToken) -> io::Result<()> {
        let listener = {
            let addr = self.p.listen;
            let sock = if addr.is_ipv4() { TcpSocket::new_v4()? } else { TcpSocket::new_v6()? };
            sock.set_reuseaddr(true)?;
            sock.bind(addr)?;
            sock.listen(1024)?
        };
        log::info!("listening on {}", self.p.listen);
        let limit = Arc::new(Semaphore::new(self.p.max_open));
        loop {
            let accepted = tokio::select! {
                _ = cancel.cancelled() => break,
                r = listener.accept() => r,
            };
            let (stream, peer) = match accepted {
                Ok(x) => x,
                Err(e) => {
                    log::warn!("accept: {}", e);
                    match e.raw_os_error() {
                        Some(libc::EMFILE) | Some(libc::ENFILE) | Some(libc::ENOBUFS) | Some(libc::ENOMEM) => {
                            tokio::time::sleep(Duration::from_millis(50)).await;
                            continue;
                        }
                        Some(libc::ECONNABORTED) | Some(libc::EINTR) => continue,
                        _ => return Err(e),
                    }
                }
            };
            let permit = match limit.clone().try_acquire_owned() {
                Ok(p) => p,
                Err(_) => {
                    log::warn!("too many connections");
                    continue;
                }
            };
            let _ = stream.set_nodelay(true);
            let eng = self.clone();
            let token = cancel.child_token();
            tokio::spawn(async move {
                tokio::select! {
                    _ = token.cancelled() => {}
                    _ = eng.handle_client(stream, peer) => {}
                }
                drop(permit);
            });
        }
        drop(listener);
        Ok(())
    }

    async fn handle_client(self: Arc<Self>, mut client: TcpStream, peer: SocketAddr) {
        let mut buf = vec![0u8; 1024];
        let n = match client.read(&mut buf).await {
            Ok(n) if n > 0 => n,
            _ => return,
        };
        if buf[0] == S_VER5 && self.p.mode & MODE_SOCKS5 != 0 {
            // greeting
            if n <= 2 || buf[1] as usize != n - 2 {
                return;
            }
            let ok = buf[2..n].contains(&0);
            if client.write_all(&[S_VER5, if ok { 0 } else { 0xff }]).await.is_err() || !ok {
                return;
            }
            let n = match client.read(&mut buf).await {
                Ok(n) if n >= 4 => n,
                _ => return,
            };
            let req = &buf[..n];
            let cmd = req[1];
            let addr = match self.s5_addr(req).await {
                Ok(a) => a,
                Err(code) => {
                    let _ = s5_reply(&mut client, code).await;
                    return;
                }
            };
            match cmd {
                S_CMD_CONN => self.tcp_session(client, addr, Flag::S5).await,
                S_CMD_AUDP if self.p.udp => self.udp_session(client, peer).await,
                _ => {
                    let _ = s5_reply(&mut client, S_ER_CMD).await;
                }
            }
        } else if buf[0] == S_VER4 && self.p.mode & MODE_SOCKS4 != 0 {
            match self.s4_addr(&buf[..n]).await {
                Some(a) => self.tcp_session(client, a, Flag::S4).await,
                None => {
                    let _ = client.write_all(&[0, 0x5b, 0, 0, 0, 0, 0, 0]).await;
                }
            }
        } else {
            log::debug!("invalid version 0x{:x}", buf[0]);
        }
    }

    async fn s5_addr(&self, r: &[u8]) -> Result<SocketAddr, u8> {
        let atp = r[3];
        let (ip, o): (Option<IpAddr>, usize) = match atp {
            S_ATP_I4 => {
                if r.len() < 10 {
                    return Err(S_ER_GEN);
                }
                (Some(IpAddr::V4(Ipv4Addr::new(r[4], r[5], r[6], r[7]))), 10)
            }
            S_ATP_I6 => {
                if !self.p.ipv6 {
                    return Err(S_ER_ATP);
                }
                if r.len() < 22 {
                    return Err(S_ER_GEN);
                }
                let mut a = [0u8; 16];
                a.copy_from_slice(&r[4..20]);
                (Some(IpAddr::V6(Ipv6Addr::from(a))), 22)
            }
            S_ATP_ID => {
                let l = r.get(4).copied().unwrap_or(0) as usize;
                if r.len() < 7 + l {
                    return Err(S_ER_GEN);
                }
                (None, 7 + l)
            }
            _ => return Err(S_ER_ATP),
        };
        let port = u16::from_be_bytes([r[o - 2], r[o - 1]]);
        match ip {
            Some(ip) => Ok(SocketAddr::new(ip, port)),
            None => {
                if !self.p.resolve {
                    return Err(S_ER_ATP);
                }
                let l = r[4] as usize;
                let host = String::from_utf8_lossy(&r[5..5 + l]).to_string();
                if l < 3 {
                    return Err(S_ER_HOST);
                }
                self.resolve(&host, port).await.ok_or_else(|| {
                    log::debug!("not resolved: {}", host);
                    S_ER_HOST
                })
            }
        }
    }

    async fn s4_addr(&self, r: &[u8]) -> Option<SocketAddr> {
        if r.len() < 9 || r[1] != S_CMD_CONN {
            return None;
        }
        let port = u16::from_be_bytes([r[2], r[3]]);
        let ip = Ipv4Addr::new(r[4], r[5], r[6], r[7]);
        if u32::from(ip) <= 255 {
            // SOCKS4a: user id \0 host \0
            if *r.last()? != 0 {
                return None;
            }
            let id_end = 8 + r[8..].iter().position(|&c| c == 0)?;
            let host = &r[id_end + 1..r.len() - 1];
            if host.len() < 3 || host.len() > 255 {
                return None;
            }
            return self.resolve(&String::from_utf8_lossy(host), port).await;
        }
        Some(SocketAddr::new(IpAddr::V4(ip), port))
    }

    // ------------------------------------------------------------ TCP

    async fn tcp_session(self: Arc<Self>, mut client: TcpStream, dst: SocketAddr, flag: Flag) {
        let mut sel = Sel::default();
        if let Some((gid, mask, detect)) = self.cache_get(&dst) {
            sel = Sel { gid: Some(gid), mask, detect };
        }
        let remote = match self.open(&mut sel, dst).await {
            Ok(s) => s,
            Err(e) => {
                let _ = match flag {
                    Flag::S5 => s5_reply(&mut client, s5_code(e)).await,
                    Flag::S4 => client.write_all(&[0, 0x5b, 0, 0, 0, 0, 0, 0]).await,
                };
                return;
            }
        };
        let ok = match flag {
            Flag::S5 => s5_reply(&mut client, 0).await,
            Flag::S4 => client.write_all(&[0, 0x5a, 0, 0, 0, 0, 0, 0]).await,
        };
        if ok.is_err() {
            return;
        }
        let mut t = Tunnel::new(self, client, remote, dst, sel);
        t.run().await;
    }

    // ------------------------------------------------------------ UDP

    async fn udp_session(self: Arc<Self>, mut ctrl: TcpStream, peer: SocketAddr) {
        let local_ip = match ctrl.local_addr() {
            Ok(a) => a.ip(),
            Err(_) => return,
        };
        let cu = match UdpSocket::bind(SocketAddr::new(local_ip, 0)).await {
            Ok(s) => Arc::new(s),
            Err(_) => {
                let _ = s5_reply(&mut ctrl, S_ER_GEN).await;
                return;
            }
        };
        let bound = match cu.local_addr() {
            Ok(a) => a,
            Err(_) => return,
        };
        if ctrl.write_all(&s5_rep_addr(0, &bound)).await.is_err() {
            return;
        }
        log::debug!("udp associate: {}", bound);

        let (tx, mut rx) = mpsc::channel::<(SocketAddr, Vec<u8>)>(256);
        let mut remotes: HashMap<SocketAddr, (Arc<UdpSocket>, Option<usize>)> = HashMap::new();
        let mut client_addr: Option<SocketAddr> = None;
        let mut rounds = UdpRounds::default();
        let mut buf = vec![0u8; 65536];
        let mut cbuf = [0u8; 64];
        let mut readers = tokio::task::JoinSet::new();

        loop {
            tokio::select! {
                r = ctrl.read(&mut cbuf) => {
                    if !matches!(r, Ok(n) if n > 0) { break; }
                }
                r = cu.recv_from(&mut buf) => {
                    let (n, from) = match r { Ok(x) => x, Err(_) => continue };
                    match client_addr {
                        None => {
                            if canon(from.ip()) != canon(peer.ip()) { continue; }
                            client_addr = Some(from);
                        }
                        Some(a) if a != from => continue,
                        _ => {}
                    }
                    let d = &buf[..n];
                    if n < 4 || d[2] != 0 { continue; }
                    let (dst, offs) = match parse_udp_hdr(d) { Some(x) => x, None => continue };
                    let dst = match dst {
                        UdpDst::Addr(a) => a,
                        UdpDst::Name(h, port) => match self.resolve(&h, port).await { Some(a) => a, None => continue },
                    };
                    if self.p.block_quic && dst.port() == 443 { continue; }
                    rounds.on_client();
                    if !remotes.contains_key(&dst) {
                        if remotes.len() >= 64 { continue; }
                        let target = SocketAddr::new(canon(dst.ip()), dst.port());
                        let bind: SocketAddr = if target.is_ipv4() { "0.0.0.0:0".parse().unwrap() } else { "[::]:0".parse().unwrap() };
                        let rs = match UdpSocket::bind(bind).await { Ok(s) => Arc::new(s), Err(_) => continue };
                        if self.p.custom_ttl { let _ = desync::setttl(rs.as_raw_fd(), self.p.def_ttl); }
                        if rs.connect(target).await.is_err() { continue; }
                        let gid = self.udp_group(&dst);
                        let txc = tx.clone();
                        let rsc = rs.clone();
                        readers.spawn(async move {
                            let mut b = vec![0u8; 65536];
                            loop {
                                match tokio::time::timeout(Duration::from_secs(120), rsc.recv(&mut b)).await {
                                    Ok(Ok(n)) => { if txc.send((dst, b[..n].to_vec())).await.is_err() { break; } }
                                    Ok(Err(e)) if e.raw_os_error() == Some(libc::ECONNREFUSED) => continue,
                                    _ => break,
                                }
                            }
                        });
                        remotes.insert(dst, (rs, gid));
                    }
                    let (rs, gid) = remotes.get(&dst).unwrap();
                    let payload = &d[offs..];
                    let res = match gid {
                        Some(g) if check_round(self.group(*g).rounds, rounds.client_round) => {
                            desync::desync_udp(rs.as_raw_fd(), payload, self.group(*g), self.def_ttl)
                        }
                        _ => rs.send(payload).await,
                    };
                    if let Err(e) = res {
                        if !matches!(e.raw_os_error(), Some(libc::EMSGSIZE) | Some(libc::ECONNREFUSED)) {
                            log::debug!("udp send: {}", e);
                        }
                    }
                }
                Some((from, data)) = rx.recv() => {
                    rounds.on_remote();
                    if let Some(ca) = client_addr {
                        let mut out = s5_udp_hdr(&from);
                        out.extend_from_slice(&data);
                        let _ = cu.send_to(&out, ca).await;
                    }
                }
            }
        }
        readers.abort_all();
        drop(remotes);
    }
}

#[derive(Default)]
struct UdpRounds {
    client_round: i32,
    client_sent: bool,
    remote_sent: bool,
}

impl UdpRounds {
    fn on_client(&mut self) {
        if !self.client_sent {
            self.client_round += 1;
            self.client_sent = true;
            self.remote_sent = false;
        }
    }
    fn on_remote(&mut self) {
        if !self.remote_sent {
            self.remote_sent = true;
            self.client_sent = false;
        }
    }
}

enum UdpDst {
    Addr(SocketAddr),
    Name(String, u16),
}

fn parse_udp_hdr(d: &[u8]) -> Option<(UdpDst, usize)> {
    match d[3] {
        S_ATP_I4 if d.len() >= 10 => {
            let ip = Ipv4Addr::new(d[4], d[5], d[6], d[7]);
            Some((UdpDst::Addr(SocketAddr::new(ip.into(), u16::from_be_bytes([d[8], d[9]]))), 10))
        }
        S_ATP_I6 if d.len() >= 22 => {
            let mut a = [0u8; 16];
            a.copy_from_slice(&d[4..20]);
            Some((UdpDst::Addr(SocketAddr::new(Ipv6Addr::from(a).into(), u16::from_be_bytes([d[20], d[21]]))), 22))
        }
        S_ATP_ID => {
            let l = *d.get(4)? as usize;
            if d.len() < 7 + l {
                return None;
            }
            let h = String::from_utf8_lossy(&d[5..5 + l]).to_string();
            Some((UdpDst::Name(h, u16::from_be_bytes([d[5 + l], d[6 + l]])), 7 + l))
        }
        _ => None,
    }
}

fn s5_udp_hdr(a: &SocketAddr) -> Vec<u8> {
    let mut v = vec![0, 0, 0];
    match SocketAddr::new(canon(a.ip()), a.port()) {
        SocketAddr::V4(x) => {
            v.push(S_ATP_I4);
            v.extend_from_slice(&x.ip().octets());
        }
        SocketAddr::V6(x) => {
            v.push(S_ATP_I6);
            v.extend_from_slice(&x.ip().octets());
        }
    }
    v.extend_from_slice(&a.port().to_be_bytes());
    v
}

fn s5_rep_addr(code: u8, a: &SocketAddr) -> Vec<u8> {
    let mut v = vec![S_VER5, code, 0];
    v.extend_from_slice(&s5_udp_hdr(a)[3..]);
    v
}

async fn s5_reply(c: &mut TcpStream, code: u8) -> io::Result<()> {
    c.write_all(&[S_VER5, code, 0, S_ATP_I4, 0, 0, 0, 0, 0, 0]).await
}

fn s5_code(e: i32) -> u8 {
    match e {
        0 => 0,
        libc::ECONNREFUSED => S_ER_CONN,
        libc::EHOSTUNREACH | libc::ETIMEDOUT => S_ER_HOST,
        libc::ENETUNREACH => S_ER_NET,
        _ => S_ER_GEN,
    }
}

// ---------------------------------------------------------------- tunnel

/// What a hook decided about the connection.
enum Act {
    Continue,
    /// Re-establish the remote side with the selected group and replay the
    /// first request.
    Reconnect,
    Close,
    /// Close and reset the client (SO_LINGER 0), like ByeDPI's on_torst.
    Reset,
}

struct Tunnel {
    eng: Arc<Engine>,
    client: TcpStream,
    remote: TcpStream,
    dst: SocketAddr,
    sel: Sel,
    // client side
    c_recv: usize,
    c_round: i32,
    c_round_sent: usize,
    // remote side
    r_recv: usize,
    r_round: i32,
    r_round_sent: usize,
    /// First request, kept while auto groups may still reconnect.
    sq: Option<Vec<u8>>,
    /// First request was a TLS ClientHello.
    mark: bool,
    setup_done: bool,
    to_disabled: bool,
    /// Response deadline for silent drops (`-T` with `-A t`).
    deadline: Option<tokio::time::Instant>,
}

impl Tunnel {
    fn new(eng: Arc<Engine>, client: TcpStream, remote: TcpStream, dst: SocketAddr, sel: Sel) -> Self {
        Tunnel {
            eng,
            client,
            remote,
            dst,
            sel,
            c_recv: 0,
            c_round: 0,
            c_round_sent: 0,
            r_recv: 0,
            r_round: 0,
            r_round_sent: 0,
            sq: None,
            mark: false,
            setup_done: false,
            to_disabled: false,
            deadline: None,
        }
    }

    fn dp(&self) -> &Group {
        self.eng.group(self.sel.gid.unwrap_or(0))
    }

    async fn run(&mut self) {
        let bf = self.eng.p.bfsize;
        let mut cbuf = vec![0u8; bf];
        let mut rbuf = vec![0u8; bf];
        loop {
            let deadline = self.deadline;
            let act = tokio::select! {
                r = self.client.read(&mut cbuf) => match r {
                    Ok(0) => self.on_fin(true),
                    Ok(n) => self.on_client_data(&cbuf[..n]).await,
                    Err(e) => self.on_err(true, &e),
                },
                r = self.remote.read(&mut rbuf) => match r {
                    Ok(0) => self.on_fin(false),
                    Ok(n) => self.on_remote_data(&rbuf[..n]).await,
                    Err(e) => self.on_err(false, &e),
                },
                _ = async { tokio::time::sleep_until(deadline.unwrap()).await }, if deadline.is_some() => {
                    log::debug!("no response from {} in time", self.dst);
                    self.deadline = None;
                    self.on_torst()
                }
            };
            match act {
                Act::Continue => {}
                Act::Close => break,
                Act::Reset => {
                    desync::set_linger_rst(self.client.as_raw_fd());
                    break;
                }
                Act::Reconnect => {
                    if !self.reconnect().await {
                        desync::set_linger_rst(self.client.as_raw_fd());
                        break;
                    }
                }
            }
        }
    }

    fn before_req(&self) -> bool {
        self.c_recv == 0 && self.r_recv == 0
    }

    fn trigger(&mut self, typ: u32, client_alive: bool) -> bool {
        let can = (self.sq.is_some() || self.before_req()) && client_alive;
        self.eng.on_trigger(typ, &mut self.sel, &self.dst, can).is_some()
    }

    fn on_torst(&mut self) -> Act {
        if self.trigger(DETECT_TORST, true) {
            Act::Reconnect
        } else {
            Act::Reset
        }
    }

    fn on_fin(&mut self, is_client: bool) -> Act {
        if !(self.mark && self.r_round <= 1) {
            return Act::Close;
        }
        if self.trigger(DETECT_TLS_ERR, !is_client) {
            return Act::Reconnect;
        }
        Act::Close
    }

    fn on_err(&mut self, is_client: bool, e: &io::Error) -> Act {
        let en = errno_of(e);
        log::debug!("{} error: {}", if is_client { "client" } else { "remote" }, e);
        if !is_conn_err(en) {
            return Act::Close;
        }
        if is_client {
            self.on_fin(true)
        } else {
            self.on_torst()
        }
    }

    /// setup_conn(): group by the real first request, timeouts, drop-sack.
    fn setup(&mut self, data: &[u8]) -> bool {
        let gid = match self.eng.find_dp(&mut self.sel, Some(data), &self.dst) {
            Some(g) => g,
            None => {
                log::debug!("drop connection");
                return false;
            }
        };
        self.sel.gid = Some(gid);
        self.mark = is_tls_chello(data);
        let fd = self.remote.as_raw_fd();
        if self.eng.p.timeout_ms > 0 {
            let _ = desync::set_user_timeout(fd, self.eng.p.timeout_ms);
        }
        if self.dp().drop_sack && desync::drop_sack(fd).is_err() {
            return false;
        }
        self.setup_done = true;
        true
    }

    async fn send_to_remote(&mut self, data: &[u8]) -> Act {
        if !self.setup_done && !self.setup(data) {
            return Act::Close;
        }
        let dp_rounds = self.dp().rounds;
        let res = if check_round(dp_rounds, self.c_round) {
            log::debug!("desync TCP: group={}, round={}", self.dp().id, self.c_round);
            let peer = self.remote.peer_addr().ok();
            let eng = self.eng.clone();
            desync::desync(&self.remote, &eng.p, eng.group(self.sel.gid.unwrap_or(0)), data, self.c_round_sent, peer, eng.def_ttl)
                .await
        } else {
            send_all(&self.remote, data).await
        };
        if let Err(e) = res {
            return self.on_err(false, &e);
        }
        self.c_round_sent += data.len();
        Act::Continue
    }

    async fn on_client_data(&mut self, data: &[u8]) -> Act {
        let n = data.len();
        self.c_recv += n;
        if self.c_round_sent == 0 {
            self.c_round += 1;
            self.r_round_sent = 0;
        }
        if self.eng.p.auto_reconnect && (self.sq.is_some() || self.c_recv == n) {
            let sq = self.sq.get_or_insert_with(Vec::new);
            sq.extend_from_slice(data);
            if sq.len() >= self.eng.p.bfsize {
                self.sq = None;
            }
        }
        let act = self.send_to_remote(data).await;
        if matches!(act, Act::Continue)
            && self.sq.is_some()
            && self.eng.p.timeout_ms > 0
            && self.deadline.is_none()
            && self.r_recv == 0
        {
            // TCP_USER_TIMEOUT only fires while data is unacknowledged; a DPI
            // that ACKs and then drops the answer is caught here instead.
            let ms = (self.eng.p.timeout_ms as u64).max(1000);
            self.deadline = Some(tokio::time::Instant::now() + Duration::from_millis(ms));
        }
        act
    }

    async fn on_remote_data(&mut self, data: &[u8]) -> Act {
        let n = data.len();
        self.r_recv += n;
        if self.r_round_sent == 0 {
            self.r_round += 1;
            self.c_round_sent = 0;
            let nr = self.dp().rounds;
            if check_round(nr, self.r_round) && !check_round(nr, self.r_round + 1) && self.dp().drop_sack {
                let _ = desync::detach_filter(self.remote.as_raw_fd());
            }
        }
        let p = &self.eng.p;
        if !self.to_disabled && p.timeout_ms > 0 && p.to_bytes_lim > 0 && self.r_recv as i64 > p.to_bytes_lim {
            let _ = desync::set_user_timeout(self.remote.as_raw_fd(), 0);
            self.to_disabled = true;
        }
        self.deadline = None;
        if let Some(req) = self.sq.take() {
            if let Some(typ) = self.eng.check_response(&mut self.sel, &req, data) {
                self.sq = Some(req);
                if self.trigger(typ, true) {
                    return Act::Reconnect;
                }
                self.sq = None;
                return Act::Close;
            }
        }
        if let Err(e) = send_all(&self.client, data).await {
            return self.on_err(true, &e);
        }
        self.r_round_sent += n;
        Act::Continue
    }

    /// reconnect(): new remote with the next group, replay the first request.
    async fn reconnect(&mut self) -> bool {
        loop {
            let remote = match self.eng.open(&mut self.sel, self.dst).await {
                Ok(s) => s,
                Err(_) => return false,
            };
            log::debug!("reconnect {} with group {:?}", self.dst, self.sel.gid);
            self.remote = remote;
            self.r_recv = 0;
            self.r_round = 0;
            self.r_round_sent = 0;
            self.c_round_sent = 0;
            self.setup_done = false;
            self.deadline = None;
            let req = match &self.sq {
                Some(r) => r.clone(),
                None => return true, // before the first request
            };
            match self.send_to_remote(&req).await {
                Act::Continue => {
                    if self.eng.p.timeout_ms > 0 {
                        let ms = (self.eng.p.timeout_ms as u64).max(1000);
                        self.deadline = Some(tokio::time::Instant::now() + Duration::from_millis(ms));
                    }
                    return true;
                }
                Act::Reconnect => continue,
                _ => return false,
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn eng(args: &[&str]) -> Arc<Engine> {
        let argv: Vec<String> = std::iter::once("x").chain(args.iter().copied()).map(String::from).collect();
        match parse_args(&argv).unwrap() {
            ParseOutcome::Run(p) => Engine::new(p),
            _ => panic!(),
        }
    }

    fn chello(host: &str) -> Vec<u8> {
        let mut v = TLS_DATA.to_vec();
        change_tls_sni(host.as_bytes(), &mut v, 517, 517).unwrap();
        v
    }

    #[test]
    fn host_group_and_fallback() {
        let e = eng(&["-Kt", "-H:youtube.com", "-s1", "-An", "-Kt", "-H:discord.com", "-d1"]);
        let dst: SocketAddr = "1.2.3.4:443".parse().unwrap();
        let mut sel = Sel::default();
        assert_eq!(e.find_dp(&mut sel, None, &dst), Some(0));
        let mut sel = Sel::default();
        assert_eq!(e.find_dp(&mut sel, Some(&chello("www.youtube.com")), &dst), Some(0));
        let mut sel = Sel::default();
        assert_eq!(e.find_dp(&mut sel, Some(&chello("cdn.discord.com")), &dst), Some(1));
        let mut sel = Sel::default();
        // neither host: trailing pass-through group
        assert_eq!(e.find_dp(&mut sel, Some(&chello("example.org")), &dst), Some(2));
    }

    #[test]
    fn torst_moves_to_next_group_and_caches() {
        let e = eng(&["-s1", "-At", "-d1", "-At", "-f-1"]);
        let dst: SocketAddr = "5.6.7.8:443".parse().unwrap();
        let mut sel = Sel::default();
        assert_eq!(e.find_dp(&mut sel, None, &dst), Some(0));
        sel.gid = Some(0);
        assert_eq!(e.on_trigger(DETECT_TORST, &mut sel, &dst, true), Some(1));
        assert_eq!(e.find_dp(&mut sel, None, &dst), Some(1));
        sel.gid = Some(1);
        assert_eq!(e.on_trigger(DETECT_TORST, &mut sel, &dst, true), Some(2));
        // a new connection to the same destination starts from the cache
        let (gid, _, _) = e.cache_get(&dst).unwrap();
        assert_eq!(gid, 2);
        // a tls_err trigger does not select torst-only groups
        let e = eng(&["-s1", "-At", "-d1"]);
        let mut sel = Sel { gid: Some(0), ..Default::default() };
        assert_eq!(e.on_trigger(DETECT_TLS_ERR, &mut sel, &dst, true), None);
    }

    #[test]
    fn rounds() {
        assert!(check_round((0, 0), 1));
        assert!(!check_round((0, 0), 2));
        assert!(check_round((1, 3), 3));
        assert!(!check_round((2, 3), 1));
    }
}
