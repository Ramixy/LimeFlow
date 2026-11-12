//! Socket-level desync techniques: port of ByeDPI `desync.c` (Linux/Android).
//!
//! Every part of the first request is sent with its own `send()` and the
//! engine waits until the kernel has really put it on the wire
//! (`tcpi_notsent_bytes == 0`) before the next one, so each part leaves as a
//! separate TCP segment. Fakes use the vmsplice trick: the fake bytes are
//! spliced from an anonymous page with a low TTL, then the page is
//! overwritten with the real bytes, so kernel retransmissions carry the
//! original data.

use std::io;
use std::net::SocketAddr;
use std::os::fd::{AsRawFd, RawFd};
use std::time::{Duration, Instant};

use once_cell::sync::Lazy;
use parking_lot::Mutex;
use rand::Rng;
use tokio::io::Interest;
use tokio::net::TcpStream;

use crate::packets::*;
use crate::params::*;

pub const DEFAULT_TTL: i32 = 8;
const UDP_JUNK_MAX: usize = 1024;

const SO_ATTACH_FILTER: libc::c_int = 26;
const SO_DETACH_FILTER: libc::c_int = 27;
const TCP_MD5SIG: libc::c_int = 14;
const TCP_INFO: libc::c_int = 11;
const TCP_USER_TIMEOUT: libc::c_int = 18;
const TCP_SYNCNT: libc::c_int = 7;
const IP_RECVTTL: libc::c_int = 12;
const IPV6_RECVHOPLIMIT: libc::c_int = 51;
const IPV6_HOPLIMIT: libc::c_int = 52;
/// offsetof(struct tcp_info, tcpi_notsent_bytes)
const TCPI_NOTSENT_OFF: usize = 144;

fn cvt(r: libc::c_int) -> io::Result<libc::c_int> {
    if r < 0 {
        Err(io::Error::last_os_error())
    } else {
        Ok(r)
    }
}

fn setsockopt_int(fd: RawFd, level: libc::c_int, opt: libc::c_int, v: libc::c_int) -> io::Result<()> {
    cvt(unsafe {
        libc::setsockopt(
            fd,
            level,
            opt,
            &v as *const _ as *const libc::c_void,
            std::mem::size_of::<libc::c_int>() as libc::socklen_t,
        )
    })
    .map(|_| ())
}

pub fn setttl(fd: RawFd, ttl: i32) -> io::Result<()> {
    let r6 = setsockopt_int(fd, libc::IPPROTO_IPV6, libc::IPV6_UNICAST_HOPS, ttl);
    let r4 = setsockopt_int(fd, libc::IPPROTO_IP, libc::IP_TTL, ttl);
    if r4.is_err() && r6.is_err() {
        return r4;
    }
    Ok(())
}

pub fn set_user_timeout(fd: RawFd, ms: u32) -> io::Result<()> {
    setsockopt_int(fd, libc::IPPROTO_TCP, TCP_USER_TIMEOUT, ms as i32)
}

pub fn set_syncnt(fd: RawFd, n: i32) -> io::Result<()> {
    setsockopt_int(fd, libc::IPPROTO_TCP, TCP_SYNCNT, n)
}

pub fn set_linger_rst(fd: RawFd) {
    let l = libc::linger { l_onoff: 1, l_linger: 0 };
    unsafe {
        libc::setsockopt(
            fd,
            libc::SOL_SOCKET,
            libc::SO_LINGER,
            &l as *const _ as *const libc::c_void,
            std::mem::size_of::<libc::linger>() as libc::socklen_t,
        );
    }
}

/// System default TTL, read from a fresh socket (ByeDPI `get_default_ttl`).
pub fn system_default_ttl() -> i32 {
    unsafe {
        let fd = libc::socket(libc::AF_INET, libc::SOCK_STREAM, 0);
        if fd < 0 {
            return 64;
        }
        let mut v: libc::c_int = 0;
        let mut l = std::mem::size_of::<libc::c_int>() as libc::socklen_t;
        let r = libc::getsockopt(fd, libc::IPPROTO_IP, libc::IP_TTL, &mut v as *mut _ as *mut libc::c_void, &mut l);
        libc::close(fd);
        if r < 0 || v <= 0 {
            64
        } else {
            v
        }
    }
}

// ---------------------------------------------------------------- auto TTL

struct TtlEnt {
    addr: SocketAddr,
    ttl: i32,
    at: Instant,
}

const TTL_CACHE_N: usize = 32;
const TTL_CACHE_SECS: u64 = 300;
static TTL_CACHE: Lazy<Mutex<Vec<TtlEnt>>> = Lazy::new(|| Mutex::new(Vec::new()));

fn lookup_ttl(peer: &SocketAddr) -> Option<i32> {
    let c = TTL_CACHE.lock();
    c.iter()
        .find(|e| e.addr.ip() == peer.ip() && e.at.elapsed().as_secs() < TTL_CACHE_SECS)
        .map(|e| e.ttl)
}

fn store_ttl(peer: SocketAddr, ttl: i32) {
    let mut c = TTL_CACHE.lock();
    c.retain(|e| e.addr.ip() != peer.ip() && e.at.elapsed().as_secs() < TTL_CACHE_SECS);
    if c.len() >= TTL_CACHE_N {
        c.remove(0);
    }
    c.push(TtlEnt { addr: peer, ttl, at: Instant::now() });
}

fn sockaddr_of(a: &SocketAddr) -> (libc::sockaddr_storage, libc::socklen_t) {
    let mut ss: libc::sockaddr_storage = unsafe { std::mem::zeroed() };
    let len = match a {
        SocketAddr::V4(v4) => {
            let sin = libc::sockaddr_in {
                sin_family: libc::AF_INET as libc::sa_family_t,
                sin_port: v4.port().to_be(),
                sin_addr: libc::in_addr { s_addr: u32::from_ne_bytes(v4.ip().octets()) },
                sin_zero: [0; 8],
            };
            unsafe { std::ptr::write(&mut ss as *mut _ as *mut libc::sockaddr_in, sin) };
            std::mem::size_of::<libc::sockaddr_in>()
        }
        SocketAddr::V6(v6) => {
            let mut sin6: libc::sockaddr_in6 = unsafe { std::mem::zeroed() };
            sin6.sin6_family = libc::AF_INET6 as libc::sa_family_t;
            sin6.sin6_port = v6.port().to_be();
            sin6.sin6_addr.s6_addr = v6.ip().octets();
            unsafe { std::ptr::write(&mut ss as *mut _ as *mut libc::sockaddr_in6, sin6) };
            std::mem::size_of::<libc::sockaddr_in6>()
        }
    };
    (ss, len as libc::socklen_t)
}

/// QUIC long-header packet with a reserved version: a QUIC server must answer
/// with Version Negotiation, whose received TTL reveals the hop distance.
/// Padded to 1200 bytes: servers ignore shorter Initial datagrams (the C
/// engine sent 64 bytes and looked for the wrong cmsg type on IPv4, so its
/// auto-TTL almost never kicked in).
fn probe_remote_ttl(peer: SocketAddr) -> Option<i32> {
    let peer = match peer {
        SocketAddr::V6(v6) => match v6.ip().to_ipv4_mapped() {
            Some(v4) => SocketAddr::new(v4.into(), v6.port()),
            None => peer,
        },
        p => p,
    };
    let fam = if peer.is_ipv4() { libc::AF_INET } else { libc::AF_INET6 };
    unsafe {
        let fd = libc::socket(fam, libc::SOCK_DGRAM | libc::SOCK_CLOEXEC, 0);
        if fd < 0 {
            return None;
        }
        let res = (|| {
            if peer.is_ipv4() {
                setsockopt_int(fd, libc::IPPROTO_IP, IP_RECVTTL, 1).ok()?;
            } else {
                setsockopt_int(fd, libc::IPPROTO_IPV6, IPV6_RECVHOPLIMIT, 1).ok()?;
            }
            let (ss, sl) = sockaddr_of(&peer);
            cvt(libc::connect(fd, &ss as *const _ as *const libc::sockaddr, sl)).ok()?;
            let mut req = [0u8; 1200];
            req[0] = 0xC0;
            req[1..5].copy_from_slice(&[0x1a, 0x2a, 0x3a, 0x4a]);
            req[5] = 8;
            rand::thread_rng().fill(&mut req[6..14]);
            if libc::send(fd, req.as_ptr() as *const libc::c_void, req.len(), libc::MSG_NOSIGNAL) < 0 {
                return None;
            }
            let mut pfd = libc::pollfd { fd, events: libc::POLLIN, revents: 0 };
            if libc::poll(&mut pfd, 1, 150) <= 0 {
                return None;
            }
            let mut rs = [0u8; 1500];
            let mut iov = libc::iovec { iov_base: rs.as_mut_ptr() as *mut libc::c_void, iov_len: rs.len() };
            let mut cbuf = [0u64; 16];
            let mut msg: libc::msghdr = std::mem::zeroed();
            msg.msg_iov = &mut iov;
            msg.msg_iovlen = 1;
            msg.msg_control = cbuf.as_mut_ptr() as *mut libc::c_void;
            msg.msg_controllen = std::mem::size_of_val(&cbuf) as _;
            if libc::recvmsg(fd, &mut msg, 0) <= 0 {
                return None;
            }
            let mut ttl = None;
            let mut cm = libc::CMSG_FIRSTHDR(&msg);
            while !cm.is_null() {
                let c = &*cm;
                if (c.cmsg_level == libc::IPPROTO_IP && (c.cmsg_type == libc::IP_TTL || c.cmsg_type == IP_RECVTTL))
                    || (c.cmsg_level == libc::IPPROTO_IPV6 && c.cmsg_type == IPV6_HOPLIMIT)
                {
                    ttl = Some(std::ptr::read_unaligned(libc::CMSG_DATA(cm) as *const libc::c_int));
                }
                cm = libc::CMSG_NXTHDR(&msg, cm);
            }
            ttl
        })();
        libc::close(fd);
        res.filter(|&t| t > 0)
    }
}

/// Observed hop limit of the server's packets, normalised to the hop count
/// (initial TTL 64/128/255 minus received).
fn hops_from_ttl(t: i32) -> i32 {
    let init = if t <= 64 {
        64
    } else if t <= 128 {
        128
    } else {
        255
    };
    init - t
}

pub async fn fake_ttl_for(dp: &Group, peer: Option<SocketAddr>) -> i32 {
    let fixed = if dp.ttl != 0 { dp.ttl } else { DEFAULT_TTL };
    let (a, peer) = match (dp.autottl, peer) {
        (Some(a), Some(p)) => (a, p),
        _ => return fixed,
    };
    let obs = match lookup_ttl(&peer) {
        Some(t) => Some(t),
        None => {
            let t = tokio::task::spawn_blocking(move || probe_remote_ttl(peer)).await.ok().flatten();
            if let Some(t) = t {
                store_ttl(peer, t);
            }
            t
        }
    };
    let obs = match obs {
        Some(t) => t,
        None => return fixed,
    };
    // Same meaning as zapret autottl: fake TTL = hop distance - delta.
    let hops = hops_from_ttl(obs);
    let t = (hops - a.delta).clamp(a.min, a.max);
    log::debug!("autottl: observed {} -> hops {} -> fake ttl {}", obs, hops, t);
    t
}

// ---------------------------------------------------------------- socket helpers

#[repr(C)]
struct SockFilter {
    code: u16,
    jt: u8,
    jf: u8,
    k: u32,
}

#[repr(C)]
struct SockFprog {
    len: libc::c_ushort,
    filter: *const SockFilter,
}

pub fn drop_sack(fd: RawFd) -> io::Result<()> {
    let code = [
        SockFilter { code: 0x30, jt: 0, jf: 0, k: 0x0000000c },
        SockFilter { code: 0x74, jt: 0, jf: 0, k: 0x00000004 },
        SockFilter { code: 0x35, jt: 0, jf: 3, k: 0x0000000b },
        SockFilter { code: 0x30, jt: 0, jf: 0, k: 0x00000022 },
        SockFilter { code: 0x15, jt: 0, jf: 1, k: 0x00000005 },
        SockFilter { code: 0x6, jt: 0, jf: 0, k: 0x00000000 },
        SockFilter { code: 0x6, jt: 0, jf: 0, k: 0x00040000 },
    ];
    let prog = SockFprog { len: code.len() as _, filter: code.as_ptr() };
    cvt(unsafe {
        libc::setsockopt(
            fd,
            libc::SOL_SOCKET,
            SO_ATTACH_FILTER,
            &prog as *const _ as *const libc::c_void,
            std::mem::size_of::<SockFprog>() as libc::socklen_t,
        )
    })
    .map(|_| ())
}

pub fn detach_filter(fd: RawFd) -> io::Result<()> {
    setsockopt_int(fd, libc::SOL_SOCKET, SO_DETACH_FILTER, 0)
}

#[repr(C)]
struct TcpMd5Sig {
    addr: libc::sockaddr_storage,
    flags: u8,
    prefixlen: u8,
    keylen: u16,
    ifindex: u32,
    key: [u8; 80],
}

fn set_md5sig(fd: RawFd, keylen: u16) -> io::Result<()> {
    let mut m: TcpMd5Sig = unsafe { std::mem::zeroed() };
    m.keylen = keylen;
    let mut l = std::mem::size_of::<libc::sockaddr_storage>() as libc::socklen_t;
    cvt(unsafe { libc::getpeername(fd, &mut m.addr as *mut _ as *mut libc::sockaddr, &mut l) })?;
    cvt(unsafe {
        libc::setsockopt(
            fd,
            libc::IPPROTO_TCP,
            TCP_MD5SIG,
            &m as *const _ as *const libc::c_void,
            std::mem::size_of::<TcpMd5Sig>() as libc::socklen_t,
        )
    })
    .map(|_| ())
}

/// True while part of the socket's send queue has not been transmitted yet.
fn has_notsent(fd: RawFd) -> bool {
    let mut buf = [0u8; 256];
    let mut l = buf.len() as libc::socklen_t;
    let r = unsafe { libc::getsockopt(fd, libc::IPPROTO_TCP, TCP_INFO, buf.as_mut_ptr() as *mut libc::c_void, &mut l) };
    if r < 0 {
        return false;
    }
    if buf[0] != 1 {
        // not ESTABLISHED
        return false;
    }
    if (l as usize) < TCPI_NOTSENT_OFF + 4 {
        return false;
    }
    u32::from_ne_bytes(buf[TCPI_NOTSENT_OFF..TCPI_NOTSENT_OFF + 4].try_into().unwrap()) != 0
}

/// Waits until the previous part has left the socket (with an upper bound so
/// a stalled connection cannot hang the task).
async fn wait_sent(fd: RawFd, p: &Params, force: bool) {
    let step = Duration::from_millis(p.await_int.max(1));
    if force {
        tokio::time::sleep(step).await;
    }
    let deadline = Instant::now() + Duration::from_secs(3);
    while has_notsent(fd) && Instant::now() < deadline {
        tokio::time::sleep(step).await;
    }
}

/// One `send()` syscall with flags; waits for writability on EAGAIN.
pub async fn send_flags(s: &TcpStream, data: &[u8], flags: libc::c_int) -> io::Result<usize> {
    let fd = s.as_raw_fd();
    loop {
        s.writable().await?;
        let r = s.try_io(Interest::WRITABLE, || {
            let n = unsafe { libc::send(fd, data.as_ptr() as *const libc::c_void, data.len(), flags | libc::MSG_NOSIGNAL) };
            if n < 0 {
                Err(io::Error::last_os_error())
            } else {
                Ok(n as usize)
            }
        });
        match r {
            Ok(n) => return Ok(n),
            Err(e) if e.kind() == io::ErrorKind::WouldBlock => continue,
            Err(e) => return Err(e),
        }
    }
}

pub async fn send_all(s: &TcpStream, mut data: &[u8]) -> io::Result<()> {
    while !data.is_empty() {
        let n = send_flags(s, data, 0).await?;
        if n == 0 {
            return Err(io::Error::from(io::ErrorKind::WriteZero));
        }
        data = &data[n..];
    }
    Ok(())
}

/// Sends `data` followed by one urgent byte; the urgent byte is dropped by
/// the receiving stack, so only the DPI sees it. Returns bytes of `data` sent.
async fn send_oob(s: &TcpStream, data: &[u8], oob: Option<u8>) -> io::Result<usize> {
    let mut v = Vec::with_capacity(data.len() + 1);
    v.extend_from_slice(data);
    v.push(oob.unwrap_or(b'a'));
    let n = send_flags(s, &v, libc::MSG_OOB).await?;
    Ok(n.saturating_sub(1))
}

// ---------------------------------------------------------------- fakes

/// Anonymous mmap'ed page(s) holding a fake packet.
struct PageBuf {
    ptr: *mut u8,
    len: usize,
}

unsafe impl Send for PageBuf {}
unsafe impl Sync for PageBuf {}

impl PageBuf {
    fn new(len: usize) -> io::Result<Self> {
        let len = len.max(1);
        let p = unsafe {
            libc::mmap(
                std::ptr::null_mut(),
                len,
                libc::PROT_READ | libc::PROT_WRITE,
                libc::MAP_PRIVATE | libc::MAP_ANONYMOUS,
                -1,
                0,
            )
        };
        if p == libc::MAP_FAILED {
            return Err(io::Error::last_os_error());
        }
        Ok(PageBuf { ptr: p as *mut u8, len })
    }

    fn as_mut(&mut self) -> &mut [u8] {
        unsafe { std::slice::from_raw_parts_mut(self.ptr, self.len) }
    }
}

impl Drop for PageBuf {
    fn drop(&mut self) {
        // Pages already spliced into the socket stay referenced by the
        // kernel until acknowledged; unmapping only drops our mapping.
        unsafe { libc::munmap(self.ptr as *mut libc::c_void, self.len) };
    }
}

struct FakePkt {
    mem: PageBuf,
    off: usize,
}

fn tcp_fake(buf: &[u8], info: &mut ProtoInfo, dp: &Group) -> io::Result<FakePkt> {
    let n = buf.len();
    if info.typ == 0 {
        if is_tls_chello(buf) {
            info.typ = IS_HTTPS;
        } else if is_http(buf) {
            info.typ = IS_HTTP;
        }
    }
    let base: &[u8] = match &dp.fake_data {
        Some(d) => d,
        None if info.typ == IS_HTTP => HTTP_DATA,
        None => &TLS_DATA,
    };
    let ps = n.max(base.len());
    let mut mem = PageBuf::new(ps + 64)?;
    let sni = if dp.fake_sni.is_empty() {
        None
    } else {
        Some(dp.fake_sni[rand::thread_rng().gen_range(0..dp.fake_sni.len())].clone())
    };
    {
        let p = &mut mem.as_mut()[..ps];
        let mut f_size = dp.fake_tls_size;
        if f_size < 0 {
            f_size += n as i64;
        }
        if f_size > n as i64 || f_size <= 0 {
            f_size = n as i64;
        }
        let mut done = false;
        if dp.fake_mod & FM_ORIG != 0 && info.typ == IS_HTTPS {
            p[..n].copy_from_slice(buf);
            match &sni {
                None => done = true,
                Some(h) => {
                    if change_tls_sni(h, p, n as i64, f_size).is_ok() {
                        done = true;
                    } else {
                        log::warn!("change sni error");
                    }
                }
            }
        }
        if !done {
            p.iter_mut().for_each(|b| *b = 0);
            p[..base.len()].copy_from_slice(base);
            if let Some(h) = &sni {
                if dp.fake_data.is_none() || is_tls_chello(base) {
                    let _ = change_tls_sni(h, p, base.len() as i64, f_size);
                }
            }
        }
        if dp.fake_mod & FM_RAND != 0 {
            randomize_tls(p);
        }
        if dp.fake_mod & FM_DUPSID != 0 && info.typ == IS_HTTPS && n > 44 {
            let sid = buf[43] as usize;
            if n >= 44 + sid && ps >= 44 + sid {
                p[44..44 + sid].copy_from_slice(&buf[44..44 + sid]);
            }
        }
    }
    let mut off = 0usize;
    if let Some(fo) = dp.fake_offset {
        let o = gen_offset(fo.pos, fo.flag, buf, n, 0, info);
        if o >= 0 && (o as usize) <= ps {
            off = o as usize;
        }
    }
    Ok(FakePkt { mem, off })
}

fn vmsplice_gift(pipe_w: libc::c_int, pkt: &FakePkt, len: usize) -> io::Result<isize> {
    let iov = libc::iovec {
        iov_base: unsafe { pkt.mem.ptr.add(pkt.off) } as *mut libc::c_void,
        iov_len: len,
    };
    let v = unsafe { libc::vmsplice(pipe_w, &iov, 1, libc::SPLICE_F_GIFT) };
    if v < 0 {
        Err(io::Error::last_os_error())
    } else {
        Ok(v as isize)
    }
}

/// Sends `orig.len()` bytes of the fake with a low TTL through
/// vmsplice+splice, waits until they left, then puts the real bytes into the
/// same pages so retransmissions carry the original data.
async fn send_fake(
    s: &TcpStream,
    p: &Params,
    dp: &Group,
    orig: &[u8],
    mut pkt: FakePkt,
    peer: Option<SocketAddr>,
    def_ttl: i32,
) -> io::Result<usize> {
    let fd = s.as_raw_fd();
    let len = orig.len();
    if pkt.off + len > pkt.mem.len {
        // fake shorter than the part: grow with zeroes
        let mut bigger = PageBuf::new(pkt.off + len)?;
        bigger.as_mut()[..pkt.mem.len].copy_from_slice(pkt.mem.as_mut());
        pkt.mem = bigger;
    }
    let ttl = fake_ttl_for(dp, peer).await;
    let mut fds = [0 as libc::c_int; 2];
    cvt(unsafe { libc::pipe2(fds.as_mut_ptr(), libc::O_CLOEXEC) })?;
    struct Pipe([libc::c_int; 2]);
    impl Drop for Pipe {
        fn drop(&mut self) {
            unsafe {
                libc::close(self.0[0]);
                libc::close(self.0[1]);
            }
        }
    }
    let pipe = Pipe(fds);

    let mut md5 = false;
    let res: io::Result<usize> = async {
        setttl(fd, ttl)?;
        if dp.md5sig {
            set_md5sig(fd, 5)?;
            md5 = true;
        }
        let v = vmsplice_gift(pipe.0[1], &pkt, len)?;
        let mut left = v as usize;
        let mut sent = 0usize;
        while left > 0 {
            s.writable().await?;
            let r = s.try_io(Interest::WRITABLE, || {
                let n = unsafe {
                    libc::splice(pipe.0[0], std::ptr::null_mut(), fd, std::ptr::null_mut(), left, libc::SPLICE_F_NONBLOCK)
                };
                if n < 0 {
                    Err(io::Error::last_os_error())
                } else {
                    Ok(n as usize)
                }
            });
            match r {
                Ok(0) => break,
                Ok(n) => {
                    left -= n;
                    sent += n;
                }
                Err(e) if e.kind() == io::ErrorKind::WouldBlock => continue,
                Err(e) => return Err(e),
            }
        }
        wait_sent(fd, p, p.wait_send).await;
        Ok(sent)
    }
    .await;
    // restore_state(): real bytes into the gifted pages, TTL and md5 back
    unsafe {
        std::ptr::copy_nonoverlapping(orig.as_ptr(), pkt.mem.ptr.add(pkt.off), len);
    }
    if md5 {
        let _ = set_md5sig(fd, 0);
    }
    let _ = setttl(fd, def_ttl);
    drop(pkt);
    res
}

// ---------------------------------------------------------------- TCP desync

/// Sends one chunk of the client's data with the group's desync
/// (ByeDPI `desync()`). `skip` is the number of bytes already sent in this
/// round. The whole chunk has been sent when this returns Ok.
pub async fn desync(
    s: &TcpStream,
    p: &Params,
    dp: &Group,
    data: &[u8],
    skip: usize,
    peer: Option<SocketAddr>,
    def_ttl: i32,
) -> io::Result<()> {
    let fd = s.as_raw_fd();
    let mut info = ProtoInfo::default();
    let mut buf = data.to_vec();
    if skip == 0 {
        if log::log_enabled!(log::Level::Debug) {
            info.init_from(&buf);
            if info.host_pos != 0 {
                log::debug!(
                    "host: {} (group {})",
                    String::from_utf8_lossy(&buf[info.host_pos..info.host_pos + info.host_len]),
                    dp.id
                );
            }
        }
        tamp(&mut buf, dp, &mut info);
    }
    let n = buf.len() as i64;
    let skip = skip as i64;
    let mut lp: i64 = 0;
    let mut part = Part::default();
    let (mut i, mut r) = (0usize, 0i32);

    while r > 0 || i < dp.parts.len() {
        if r <= 0 {
            part = dp.parts[i];
            r = part.r.max(1);
            i += 1;
        }
        let rep = part.r.max(1) - r;
        let mut pos = gen_offset(part.pos, part.flag, &buf, buf.len(), lp, &mut info);
        pos += part.s as i64 * rep as i64;
        r -= 1;

        if skip > 0 && pos < skip && part.flag & OFFSET_START == 0 {
            continue;
        }
        if pos < 0 || pos < lp {
            log::debug!("split cancel: pos={}-{}, n={}", lp, pos, n);
            break;
        }
        if pos > n {
            pos = n;
        }
        let chunk = &buf[lp as usize..pos as usize];
        let mut restore_ttl = false;
        let sent = match part.m {
            DesyncMode::Fake => {
                if chunk.is_empty() {
                    0
                } else {
                    let pkt = tcp_fake(&buf, &mut info, dp)?;
                    send_fake(s, p, dp, chunk, pkt, peer, def_ttl).await?
                }
            }
            DesyncMode::Oob => send_oob(s, chunk, dp.oob_char).await?,
            DesyncMode::Disorder | DesyncMode::Disoob => {
                if rep % 2 == 0 {
                    setttl(fd, 1)?;
                }
                restore_ttl = true;
                if part.m == DesyncMode::Disoob {
                    send_oob(s, chunk, dp.oob_char).await?
                } else {
                    send_flags(s, chunk, 0).await?
                }
            }
            _ => send_flags(s, chunk, 0).await?,
        };
        log::trace!("part {:?}: {}-{} ({})", part.m, lp, pos, sent);
        if sent < chunk.len() && part.m != DesyncMode::Fake {
            // short write: the rest goes out as a continuation of this part
            send_all(s, &chunk[sent..]).await?;
        }
        if part.m != DesyncMode::Fake {
            wait_sent(fd, p, p.wait_send).await;
        }
        if restore_ttl {
            setttl(fd, def_ttl)?;
        }
        lp = pos;
    }
    if lp < n {
        send_all(s, &buf[lp as usize..]).await?;
    }
    Ok(())
}

// ---------------------------------------------------------------- UDP

fn send_dgram(fd: RawFd, d: &[u8]) -> io::Result<usize> {
    let n = unsafe { libc::send(fd, d.as_ptr() as *const libc::c_void, d.len(), libc::MSG_NOSIGNAL) };
    if n < 0 {
        Err(io::Error::last_os_error())
    } else {
        Ok(n as usize)
    }
}

fn send_udp_junk_train(fd: RawFd, dp: &Group) -> io::Result<()> {
    if dp.udp_junk_count <= 0 {
        return Ok(());
    }
    let mut jmin = if dp.udp_junk_min > 0 { dp.udp_junk_min as usize } else { 64 };
    let mut jmax = if dp.udp_junk_max > 0 { dp.udp_junk_max as usize } else { 256 };
    jmin = jmin.max(16);
    jmax = jmax.min(UDP_JUNK_MAX);
    if jmin > jmax {
        jmin = jmax;
    }
    let mut rng = rand::thread_rng();
    let mut junk = [0u8; UDP_JUNK_MAX];
    for _ in 0..dp.udp_junk_count {
        let size = if jmax > jmin { rng.gen_range(jmin..=jmax) } else { jmin };
        rng.fill(&mut junk[..size]);
        send_dgram(fd, &junk[..size])?;
    }
    Ok(())
}

/// Fakes with a low TTL, then the junk train, then the real datagram
/// (ByeDPI `desync_udp`). The socket must be connected.
pub fn desync_udp(fd: RawFd, data: &[u8], dp: &Group, def_ttl: i32) -> io::Result<usize> {
    if dp.udp_fake_count != 0 || dp.udp_junk_count != 0 {
        let mut pkt: &[u8] = match &dp.fake_data {
            Some(d) => d,
            None => &UDP_DATA,
        };
        if let Some(fo) = dp.fake_offset {
            if pkt.len() as i64 > fo.pos && fo.pos >= 0 {
                pkt = &pkt[fo.pos as usize..];
            } else {
                pkt = &[];
            }
        }
        setttl(fd, if dp.ttl != 0 { dp.ttl } else { DEFAULT_TTL })?;
        for _ in 0..dp.udp_fake_count {
            send_dgram(fd, pkt)?;
        }
        send_udp_junk_train(fd, dp)?;
        setttl(fd, def_ttl)?;
    }
    send_dgram(fd, data)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn hops() {
        assert_eq!(hops_from_ttl(52), 12);
        assert_eq!(hops_from_ttl(116), 12);
        assert_eq!(hops_from_ttl(243), 12);
    }
}
