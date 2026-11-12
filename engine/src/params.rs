//! Engine parameters and the ByeDPI-compatible command line parser.
//!
//! Every strategy in the LimeFlow catalog (and every user strategy) is a
//! ByeDPI command line, so this parser mirrors `parse_args()` of the C fork
//! option for option, including attached values (`-s1+s`, `-Kt,h`,
//! `-H:"a b"`), the `-A` group cascade and `-B` group copies.

use std::collections::HashSet;
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::sync::atomic::{AtomicI32, AtomicU32};

pub const OFFSET_END: u32 = 1;
pub const OFFSET_MID: u32 = 2;
pub const OFFSET_RAND: u32 = 4;
pub const OFFSET_SNI: u32 = 8;
pub const OFFSET_HOST: u32 = 16;
pub const OFFSET_START: u32 = 32;
/// `+sd` (zapret midsld): middle of the second-level domain.
pub const OFFSET_SLD: u32 = 64;
/// `+sx` (zapret sniext): start of the SNI extension, 9 bytes before the host.
pub const OFFSET_EXTSTART: u32 = 128;

pub const DETECT_HTTP_LOCAT: u32 = 1;
pub const DETECT_TLS_ERR: u32 = 2;
pub const DETECT_TORST: u32 = 8;
pub const DETECT_CONNECT: u32 = 16;

pub const AUTO_ONRECONN: u32 = 1;
pub const AUTO_NORECONN: u32 = 2;
pub const AUTO_SORT: u32 = 4;

pub const IS_TCP: u32 = 1;
pub const IS_UDP: u32 = 2;
pub const IS_HTTP: u32 = 4;
pub const IS_HTTPS: u32 = 8;
pub const IS_IPV4: u32 = 16;

pub const FM_RAND: u32 = 1;
pub const FM_ORIG: u32 = 2;
/// `-Qd` (zapret dupsid): copy the real session id into the fake.
pub const FM_DUPSID: u32 = 4;

pub const MH_HMIX: u32 = 1;
pub const MH_SPACE: u32 = 2;
pub const MH_DMIX: u32 = 4;

pub const MODE_SOCKS5: u32 = 1;
pub const MODE_SOCKS4: u32 = 2;

#[derive(Clone, Copy, Debug, PartialEq, Eq, Default)]
pub enum DesyncMode {
    #[default]
    None,
    Split,
    Disorder,
    Oob,
    Disoob,
    Fake,
}

#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct Part {
    pub m: DesyncMode,
    pub flag: u32,
    pub pos: i64,
    pub r: i32,
    pub s: i32,
}

#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct AutoTtl {
    pub delta: i32,
    pub min: i32,
    pub max: i32,
}

/// Host whitelist with ByeDPI semantics: an entry matches the host itself and
/// every subdomain of it (`youtube.com` matches `www.youtube.com`).
#[derive(Default, Debug, Clone)]
pub struct HostSet {
    set: HashSet<Vec<u8>>,
}

impl HostSet {
    pub fn insert(&mut self, host: &[u8]) {
        self.set.insert(host.to_ascii_lowercase());
    }

    pub fn len(&self) -> usize {
        self.set.len()
    }

    pub fn is_empty(&self) -> bool {
        self.set.is_empty()
    }

    pub fn matches(&self, host: &[u8]) -> bool {
        if self.set.is_empty() {
            return false;
        }
        let host = host.to_ascii_lowercase();
        let mut cur: &[u8] = &host;
        loop {
            if self.set.contains(cur) {
                return true;
            }
            match cur.iter().position(|&c| c == b'.') {
                Some(i) => cur = &cur[i + 1..],
                None => return false,
            }
        }
    }
}

/// IP whitelist of CIDR prefixes.
#[derive(Default, Debug, Clone)]
pub struct IpSet {
    v4: Vec<(u32, u32)>,
    v6: Vec<(u128, u32)>,
}

impl IpSet {
    pub fn insert(&mut self, ip: IpAddr, bits: u32) {
        match ip {
            IpAddr::V4(a) => self.v4.push((u32::from(a), bits.min(32))),
            IpAddr::V6(a) => self.v6.push((u128::from(a), bits.min(128))),
        }
    }

    pub fn len(&self) -> usize {
        self.v4.len() + self.v6.len()
    }

    pub fn contains(&self, ip: IpAddr) -> bool {
        let ip = match ip {
            IpAddr::V6(a) => match a.to_ipv4_mapped() {
                Some(v4) => IpAddr::V4(v4),
                None => IpAddr::V6(a),
            },
            other => other,
        };
        match ip {
            IpAddr::V4(a) => {
                let v = u32::from(a);
                self.v4.iter().any(|&(net, bits)| {
                    bits == 0 || (v >> (32 - bits)) == (net >> (32 - bits))
                })
            }
            IpAddr::V6(a) => {
                let v = u128::from(a);
                self.v6.iter().any(|&(net, bits)| {
                    bits == 0 || (v >> (128 - bits)) == (net >> (128 - bits))
                })
            }
        }
    }
}

#[derive(Debug, Default)]
pub struct Group {
    pub id: usize,
    pub ttl: i32,
    pub autottl: Option<AutoTtl>,
    pub md5sig: bool,
    pub fake_data: Option<Vec<u8>>,
    pub udp_fake_count: i32,
    pub udp_junk_count: i32,
    pub udp_junk_min: i32,
    pub udp_junk_max: i32,
    pub fake_offset: Option<Part>,
    pub fake_sni: Vec<Vec<u8>>,
    pub fake_mod: u32,
    pub fake_tls_size: i64,
    pub drop_sack: bool,
    pub oob_char: Option<u8>,
    pub parts: Vec<Part>,
    pub mod_http: u32,
    pub tlsrec: Vec<Part>,
    pub tlsminor: Option<u8>,
    pub proto: u32,
    pub detect: u32,
    pub auto_level: u32,
    pub hosts: Option<HostSet>,
    pub ipset: Option<IpSet>,
    /// Port range in host byte order; (0, 0) means any port.
    pub pf: (u16, u16),
    pub rounds: (i32, i32),
    pub cache_ttl: i64,
    pub label: String,
    pub pri: AtomicI32,
    pub fail_count: AtomicU32,
}

impl Group {
    fn new(id: usize) -> Self {
        Group { id, ..Default::default() }
    }

    pub fn bit(&self) -> u64 {
        1u64 << (self.id.min(63))
    }

    fn is_limited(&self) -> bool {
        self.hosts.is_some() || self.proto != 0 || self.pf.0 != 0 || self.detect != 0 || self.ipset.is_some()
    }
}

#[derive(Debug)]
pub struct Params {
    pub groups: Vec<Group>,
    pub full_mask: u64,
    pub await_int: u64,
    pub wait_send: bool,
    pub def_ttl: i32,
    pub custom_ttl: bool,
    pub tfo: bool,
    pub timeout_ms: u32,
    pub ptimeout_ms: u32,
    pub to_count_lim: i32,
    pub to_bytes_lim: i64,
    pub cache_pre: u32,
    pub mode: u32,
    pub ipv6: bool,
    pub resolve: bool,
    pub udp: bool,
    pub block_quic: bool,
    pub auto_reconnect: bool,
    pub max_open: usize,
    pub debug: i32,
    pub bfsize: usize,
    pub listen: SocketAddr,
    pub bind_ip: Option<IpAddr>,
}

impl Default for Params {
    fn default() -> Self {
        Params {
            groups: Vec::new(),
            full_mask: 0,
            await_int: 10,
            wait_send: false,
            def_ttl: 0,
            custom_ttl: false,
            tfo: false,
            timeout_ms: 0,
            ptimeout_ms: 0,
            to_count_lim: 0,
            to_bytes_lim: 0,
            cache_pre: 0,
            mode: 0,
            ipv6: true,
            resolve: true,
            udp: true,
            block_quic: false,
            auto_reconnect: false,
            max_open: 512,
            debug: 0,
            bfsize: 16384,
            listen: SocketAddr::new(IpAddr::V4(Ipv4Addr::UNSPECIFIED), 1080),
            bind_ip: None,
        }
    }
}

#[derive(Debug)]
pub enum ParseOutcome {
    Run(Params),
    /// `-h` / `-v`: print and exit successfully.
    Info(String),
}

/// (long name, short char, takes argument)
const OPTIONS: &[(&str, char, bool)] = &[
    ("daemon", 'D', false),
    ("pidfile", 'w', true),
    ("no-domain", 'N', false),
    ("no-ipv6", 'X', false),
    ("no-udp", 'U', false),
    ("block-quic", 'k', false),
    ("help", 'h', false),
    ("version", 'v', false),
    ("ip", 'i', true),
    ("port", 'p', true),
    ("transparent", 'E', false),
    ("conn-ip", 'I', true),
    ("buf-size", 'b', true),
    ("max-conn", 'c', true),
    ("debug", 'x', true),
    ("tfo", 'F', false),
    ("auto", 'A', true),
    ("auto-mode", 'L', true),
    ("cache-ttl", 'u', true),
    ("timeout", 'T', true),
    ("copy", 'B', true),
    ("cache-file", 'y', true),
    ("proto", 'K', true),
    ("hosts", 'H', true),
    ("pf", 'V', true),
    ("round", 'R', true),
    ("split", 's', true),
    ("disorder", 'd', true),
    ("oob", 'o', true),
    ("disoob", 'q', true),
    ("fake", 'f', true),
    ("md5sig", 'S', false),
    ("fake-sni", 'n', true),
    ("ttl", 't', true),
    ("auto-ttl", 'z', true),
    ("fake-data", 'l', true),
    ("fake-offset", 'O', true),
    ("fake-tls-mod", 'Q', true),
    ("oob-data", 'e', true),
    ("mod-http", 'M', true),
    ("tlsrec", 'r', true),
    ("tlsminor", 'm', true),
    ("udp-fake", 'a', true),
    ("udp-junk", 'J', true),
    ("udp-junk-size", 'G', true),
    ("def-ttl", 'g', true),
    ("wait-send", 'Z', false),
    ("await-int", 'W', true),
    ("drop-sack", 'Y', false),
    ("protect-path", 'P', true),
    ("ipset", 'j', true),
    ("connect-to", 'C', true),
    ("comment", '#', true),
    ("cache-merge", '/', true),
];

fn find_short(c: char) -> Option<&'static (&'static str, char, bool)> {
    OPTIONS.iter().find(|o| o.1 == c)
}

fn find_long(name: &str) -> Option<&'static (&'static str, char, bool)> {
    OPTIONS.iter().find(|o| o.0 == name)
}

/// One parsed option occurrence: (short char, value, index of the next token).
#[derive(Debug, Clone)]
struct Opt {
    c: char,
    val: Option<String>,
    next: usize,
}

/// getopt_long-style tokenizer over argv (argv[0] is the program name).
fn tokenize(argv: &[String]) -> Result<Vec<Opt>, String> {
    let mut out = Vec::new();
    let mut i = 1;
    while i < argv.len() {
        let a = &argv[i];
        i += 1;
        if let Some(long) = a.strip_prefix("--") {
            if long.is_empty() {
                break;
            }
            let (name, inline) = match long.split_once('=') {
                Some((n, v)) => (n, Some(v.to_string())),
                None => (long, None),
            };
            let o = find_long(name).ok_or_else(|| format!("unknown option --{}", name))?;
            let val = if o.2 {
                match inline {
                    Some(v) => Some(v),
                    None => {
                        let v = argv.get(i).cloned().ok_or_else(|| format!("--{} needs a value", name))?;
                        i += 1;
                        Some(v)
                    }
                }
            } else {
                None
            };
            out.push(Opt { c: o.1, val, next: i });
        } else if let Some(shorts) = a.strip_prefix('-') {
            if shorts.is_empty() {
                return Err("stray '-'".into());
            }
            let chars: Vec<char> = shorts.chars().collect();
            let mut k = 0;
            while k < chars.len() {
                let c = chars[k];
                let o = find_short(c).ok_or_else(|| format!("unknown option -{}", c))?;
                if o.2 {
                    let rest: String = chars[k + 1..].iter().collect();
                    let val = if !rest.is_empty() {
                        rest
                    } else {
                        let v = argv.get(i).cloned().ok_or_else(|| format!("-{} needs a value", c))?;
                        i += 1;
                        v
                    };
                    out.push(Opt { c, val: Some(val), next: i });
                    break;
                }
                out.push(Opt { c, val: None, next: i });
                k += 1;
            }
        } else {
            return Err(format!("unexpected argument {}", a));
        }
    }
    Ok(out)
}

/// strtol(…, 0) prefix parse: returns (value, rest). Accepts 0x / 0 prefixes.
pub fn strtol(s: &str) -> (Option<i64>, &str) {
    let b = s.as_bytes();
    let mut i = 0;
    while i < b.len() && (b[i] == b' ' || b[i] == b'\t') {
        i += 1;
    }
    let mut neg = false;
    if i < b.len() && (b[i] == b'-' || b[i] == b'+') {
        neg = b[i] == b'-';
        i += 1;
    }
    let (radix, start) = if b.get(i) == Some(&b'0') && (b.get(i + 1) == Some(&b'x') || b.get(i + 1) == Some(&b'X'))
        && b.get(i + 2).map_or(false, |c| c.is_ascii_hexdigit())
    {
        (16u32, i + 2)
    } else if b.get(i) == Some(&b'0') {
        (8u32, i)
    } else {
        (10u32, i)
    };
    let mut j = start;
    let mut v: i64 = 0;
    while j < b.len() {
        match (b[j] as char).to_digit(radix) {
            Some(d) => {
                v = v.saturating_mul(radix as i64).saturating_add(d as i64);
                j += 1;
            }
            None => break,
        }
    }
    if j == start {
        return (None, s);
    }
    (Some(if neg { -v } else { v }), &s[j..])
}
fn strtof(s: &str) -> (Option<f64>, &str) {
    let b = s.as_bytes();
    let mut j = 0;
    if j < b.len() && (b[j] == b'-' || b[j] == b'+') {
        j += 1;
    }
    let mut seen = false;
    while j < b.len() && (b[j].is_ascii_digit() || b[j] == b'.') {
        seen = true;
        j += 1;
    }
    if !seen {
        return (None, s);
    }
    (s[..j].parse::<f64>().ok(), &s[j..])
}

/// C-style escapes (`\xHH`, octal, `\n` …) — ByeDPI `parse_cform`.
pub fn parse_cform(s: &str) -> Vec<u8> {
    let b = s.as_bytes();
    let mut out = Vec::with_capacity(b.len());
    let mut p = 0;
    while p < b.len() {
        if b[p] != b'\\' {
            out.push(b[p]);
            p += 1;
            continue;
        }
        p += 1;
        if p >= b.len() {
            break;
        }
        let esc = match b[p] {
            b'r' => Some(b'\r'),
            b'n' => Some(b'\n'),
            b't' => Some(b'\t'),
            b'\\' => Some(b'\\'),
            b'f' => Some(0x0c),
            b'b' => Some(0x08),
            b'v' => Some(0x0b),
            b'a' => Some(0x07),
            _ => None,
        };
        if let Some(c) = esc {
            out.push(c);
            p += 1;
            continue;
        }
        if b[p] == b'x' {
            let mut v: u32 = 0;
            let mut n = 0;
            while n < 2 && p + 1 + n < b.len() && (b[p + 1 + n] as char).is_ascii_hexdigit() {
                v = v * 16 + (b[p + 1 + n] as char).to_digit(16).unwrap();
                n += 1;
            }
            if n > 0 {
                out.push(v as u8);
                p += 1 + n;
                continue;
            }
        } else if (b'0'..=b'7').contains(&b[p]) {
            let mut v: u32 = 0;
            let mut n = 0;
            while n < 3 && p + n < b.len() && (b'0'..=b'7').contains(&b[p + n]) {
                v = v * 8 + (b[p + n] - b'0') as u32;
                n += 1;
            }
            out.push(v as u8);
            p += n;
            continue;
        }
        // unknown escape: ByeDPI drops the backslash and re-reads the char
    }
    out
}

/// `-l`/`-H`/`-j` value: `:inline data` or a file path.
fn ftob(s: &str) -> Result<Vec<u8>, String> {
    if let Some(inline) = s.strip_prefix(':') {
        let d = parse_cform(inline);
        if d.is_empty() {
            return Err("empty inline data".into());
        }
        return Ok(d);
    }
    let d = std::fs::read(s).map_err(|e| format!("{}: {}", s, e))?;
    if d.is_empty() {
        return Err(format!("{}: empty", s));
    }
    Ok(d)
}

fn host_char_ok(c: u8) -> bool {
    // lower_char(): '-' .. '9', A-Z (lowered), a-z
    (b'-'..=b'9').contains(&c) || c.is_ascii_alphabetic()
}

pub fn parse_hosts(set: &mut HostSet, data: &[u8]) {
    for tok in data.split(|&c| c == b' ' || c == b'\n' || c == b'\r') {
        if tok.is_empty() {
            continue;
        }
        if tok.iter().all(|&c| host_char_ok(c)) {
            set.insert(tok);
        } else {
            log::warn!("invalid host: {}", String::from_utf8_lossy(tok));
        }
    }
}

fn parse_ipset(set: &mut IpSet, data: &[u8]) {
    for tok in data.split(|&c| c == b' ' || c == b'\n' || c == b'\r') {
        if tok.is_empty() {
            continue;
        }
        let s = String::from_utf8_lossy(tok);
        let (ip_s, bits) = match s.split_once('/') {
            Some((a, b)) => match b.parse::<u32>() {
                Ok(v) if v > 0 => (a.to_string(), Some(v)),
                _ => continue,
            },
            None => (s.to_string(), None),
        };
        match ip_s.parse::<IpAddr>() {
            Ok(ip) => {
                let max = if ip.is_ipv4() { 32 } else { 128 };
                set.insert(ip, bits.unwrap_or(max).min(max));
            }
            Err(_) => log::warn!("invalid ip: {}", s),
        }
    }
}

pub fn parse_offset(s: &str) -> Option<Part> {
    let mut part = Part::default();
    let (v, mut rest) = strtol(s);
    let val = v.unwrap_or(0);
    if v.is_none() && !(rest.starts_with(':') || rest.starts_with('+')) && !rest.is_empty() {
        return None;
    }
    while let Some(r) = rest.strip_prefix(':') {
        let (rs, rr) = strtol(r);
        let rs = rs.unwrap_or(0);
        rest = rr;
        if rs < 0 || rs > i32::MAX as i64 {
            return None;
        }
        if part.r == 0 {
            if rs == 0 {
                return None;
            }
            part.r = rs as i32;
        } else {
            part.s = rs as i32;
            break;
        }
    }
    if let Some(f) = rest.strip_prefix('+') {
        let fb = f.as_bytes();
        match fb.first() {
            Some(b's') => part.flag = OFFSET_SNI,
            Some(b'h') => part.flag = OFFSET_HOST,
            Some(b'n') => {}
            _ => return None,
        }
        match fb.get(1) {
            Some(b'e') => part.flag |= OFFSET_END,
            Some(b'm') => part.flag |= OFFSET_MID,
            Some(b'd') => part.flag |= OFFSET_SNI | OFFSET_SLD,
            Some(b'x') => part.flag |= OFFSET_SNI | OFFSET_EXTSTART,
            Some(b'r') => part.flag |= OFFSET_RAND,
            Some(b's') => part.flag |= OFFSET_START,
            _ => {}
        }
    } else if !rest.is_empty() {
        return None;
    }
    if part.r == 0 {
        part.r = 1;
    }
    part.pos = val;
    Some(part)
}

/// `-i` / `-I` value: `ip`, `ip:port`, `[v6]` or `[v6]:port`.
fn parse_host_port(s: &str) -> Option<(IpAddr, Option<u16>)> {
    let s = s.trim_start_matches("socks5://").trim_start_matches("socks4://").trim_start_matches("socks://");
    if let Some(rest) = s.strip_prefix('[') {
        let (ip, tail) = rest.split_once(']')?;
        let port = match tail.strip_prefix(':') {
            Some(p) => Some(p.parse().ok()?),
            None if tail.is_empty() => None,
            None => return None,
        };
        return Some((ip.parse().ok()?, port));
    }
    if s.matches(':').count() == 1 {
        let (ip, port) = s.split_once(':')?;
        return Some((ip.parse().ok()?, Some(port.parse().ok()?)));
    }
    Some((s.parse().ok()?, None))
}
fn bad(c: char, v: &str) -> String {
    format!("invalid value: -{} {}", c, v)
}

pub fn parse_args(argv: &[String]) -> Result<ParseOutcome, String> {
    let opts = tokenize(argv)?;
    let mut p = Params::default();
    let mut groups: Vec<Group> = vec![Group::new(0)];
    let mut all_limited = true;
    let mut listen_ip: Option<IpAddr> = None;
    let mut listen_port: u16 = 1080;

    // Option queue: `-B n` splices group n's options in place.
    let mut queue: std::collections::VecDeque<Opt> = opts.iter().cloned().collect();
    // Remember which raw options belong to each group (for -B).
    let mut group_opts: Vec<Vec<Opt>> = vec![Vec::new()];

    while let Some(o) = queue.pop_front() {
        let v = o.val.clone().unwrap_or_default();
        let cur = groups.len() - 1;
        if o.c != 'A' && o.c != 'B' {
            group_opts[cur].push(o.clone());
        }
        let dp = groups.last_mut().unwrap();
        match o.c {
            'N' => p.resolve = false,
            'X' => p.ipv6 = false,
            'U' => p.udp = false,
            'k' => p.block_quic = true,
            'E' => return Err("transparent mode is not supported".into()),
            'D' | 'w' | 'P' | 'y' | '/' => {}
            'h' => return Ok(ParseOutcome::Info("LimeFlow engine (ByeDPI-compatible). See ByeDPI --help.".into())),
            'v' => return Ok(ParseOutcome::Info("lfengine 17.3".into())),
            'i' => {
                let (ip, port) = parse_host_port(&v).ok_or_else(|| bad('i', &v))?;
                listen_ip = Some(ip);
                if let Some(port) = port {
                    listen_port = port;
                }
            }
            'p' => {
                let n: i64 = v.parse().map_err(|_| bad('p', &v))?;
                if n <= 0 || n > 0xffff {
                    return Err(bad('p', &v));
                }
                listen_port = n as u16;
            }
            'I' => p.bind_ip = Some(parse_host_port(&v).ok_or_else(|| bad('I', &v))?.0),
            'b' => {
                let n: i64 = v.parse().map_err(|_| bad('b', &v))?;
                if n <= 0 || n > (i32::MAX / 4) as i64 {
                    return Err(bad('b', &v));
                }
                p.bfsize = n as usize;
            }
            'c' => {
                let n: i64 = v.parse().map_err(|_| bad('c', &v))?;
                if n <= 0 || n >= 0xffff / 2 {
                    return Err(bad('c', &v));
                }
                p.max_open = n as usize;
            }
            'x' => p.debug = strtol(&v).0.unwrap_or(0) as i32,
            'F' => p.tfo = true,
            'L' => {
                for f in v.split(',') {
                    match f.chars().next() {
                        Some('o') => dp.auto_level |= AUTO_ONRECONN,
                        Some('s') => dp.auto_level |= AUTO_SORT,
                        Some('n') => dp.auto_level |= AUTO_NORECONN,
                        _ => return Err(bad('L', &v)),
                    }
                }
            }
            'A' => {
                if !dp.is_limited() {
                    all_limited = false;
                }
                let id = groups.len();
                let mut ng = Group::new(id);
                for f in v.split(',') {
                    match f.chars().next() {
                        Some('t') => ng.detect |= DETECT_TORST,
                        Some('r') => ng.detect |= DETECT_HTTP_LOCAT,
                        Some('s') => ng.detect |= DETECT_TLS_ERR,
                        Some('c') => ng.detect |= DETECT_CONNECT,
                        Some('n') => {}
                        Some('p') => {
                            if let Some((_, x)) = f.split_once('=') {
                                let pri = x.parse::<f64>().map_err(|_| bad('A', &v))?;
                                groups.last().unwrap().pri.store(pri as i32, std::sync::atomic::Ordering::Relaxed);
                            }
                        }
                        _ => return Err(bad('A', &v)),
                    }
                }
                if ng.detect != 0 {
                    p.auto_reconnect = true;
                }
                groups.push(ng);
                group_opts.push(Vec::new());
            }
            'B' => {
                if v.starts_with('i') {
                    dp.pf.0 = 1;
                    continue;
                }
                let n = strtol(&v).0.unwrap_or(0);
                if n < 1 || n as usize > group_opts.len() {
                    return Err(bad('B', &v));
                }
                let copy = group_opts[(n - 1) as usize].clone();
                for c in copy.into_iter().rev() {
                    queue.push_front(c);
                }
            }
            '#' => dp.label = v.clone(),
            'u' => {
                let n = strtol(&v).0.unwrap_or(0);
                if n <= 0 {
                    return Err(bad('u', &v));
                }
                dp.cache_ttl = n;
            }
            'T' => {
                let (f, rest) = strtof(&v);
                p.timeout_ms = (f.ok_or_else(|| bad('T', &v))? * 1000.0) as u32;
                let mut rest = rest;
                if let Some(r) = rest.strip_prefix(':') {
                    let (f, r2) = strtof(r);
                    p.ptimeout_ms = (f.unwrap_or(0.0) * 1000.0) as u32;
                    rest = r2;
                }
                if let Some(r) = rest.strip_prefix(':') {
                    let (f, r2) = strtof(r);
                    p.to_count_lim = f.unwrap_or(0.0) as i32;
                    rest = r2;
                }
                if let Some(r) = rest.strip_prefix(':') {
                    let (f, r2) = strtof(r);
                    p.to_bytes_lim = f.unwrap_or(0.0) as i64;
                    rest = r2;
                }
                if !rest.is_empty() {
                    return Err(bad('T', &v));
                }
            }
            'K' => {
                for f in v.split(',') {
                    match f.chars().next() {
                        Some('t') => dp.proto |= IS_TCP | IS_HTTPS,
                        Some('h') => dp.proto |= IS_TCP | IS_HTTP,
                        Some('u') => dp.proto |= IS_UDP,
                        Some('i') => dp.proto |= IS_IPV4,
                        _ => return Err(bad('K', &v)),
                    }
                }
            }
            'H' => {
                let data = ftob(&v).map_err(|e| format!("-H: {}", e))?;
                parse_hosts(dp.hosts.get_or_insert_with(HostSet::default), &data);
            }
            'j' => {
                let data = ftob(&v).map_err(|e| format!("-j: {}", e))?;
                parse_ipset(dp.ipset.get_or_insert_with(IpSet::default), &data);
            }
            's' | 'd' | 'o' | 'q' | 'f' => {
                let mut part = parse_offset(&v).ok_or_else(|| bad(o.c, &v))?;
                part.m = match o.c {
                    's' => DesyncMode::Split,
                    'd' => DesyncMode::Disorder,
                    'o' => DesyncMode::Oob,
                    'q' => DesyncMode::Disoob,
                    _ => DesyncMode::Fake,
                };
                dp.parts.push(part);
            }
            't' => {
                let n: i64 = strtol(&v).0.ok_or_else(|| bad('t', &v))?;
                if n <= 0 || n > 255 || !strtol(&v).1.is_empty() {
                    return Err(bad('t', &v));
                }
                dp.ttl = n as i32;
            }
            'z' => {
                let mut a = AutoTtl { delta: 1, min: 3, max: 128 };
                let fields: Vec<&str> = v.split(':').collect();
                if fields.is_empty() || fields.len() > 3 {
                    return Err(bad('z', &v));
                }
                for (k, f) in fields.iter().enumerate() {
                    if f.is_empty() {
                        continue;
                    }
                    let n: i32 = f.parse().map_err(|_| bad('z', &v))?;
                    match k {
                        0 => a.delta = n,
                        1 => a.min = n,
                        _ => a.max = n,
                    }
                }
                if a.min > a.max || a.delta < 0 {
                    return Err(bad('z', &v));
                }
                dp.autottl = Some(a);
            }
            'S' => dp.md5sig = true,
            'O' => {
                let mut part = parse_offset(&v).ok_or_else(|| bad('O', &v))?;
                part.m = DesyncMode::Split;
                dp.fake_offset = Some(part);
            }
            'Q' => {
                for f in v.split(',') {
                    match f.chars().next() {
                        Some('r') => dp.fake_mod |= FM_RAND,
                        Some('o') => dp.fake_mod |= FM_ORIG,
                        Some('d') => dp.fake_mod |= FM_DUPSID,
                        Some('m') => {
                            let n = f.split_once('=').and_then(|(_, x)| x.parse::<i64>().ok()).ok_or_else(|| bad('Q', &v))?;
                            dp.fake_tls_size = n;
                        }
                        _ => return Err(bad('Q', &v)),
                    }
                }
            }
            'n' => dp.fake_sni.push(v.as_bytes().to_vec()),
            'l' => {
                if dp.fake_data.is_none() {
                    dp.fake_data = Some(ftob(&v).map_err(|e| format!("-l: {}", e))?);
                }
            }
            'e' => {
                let d = parse_cform(&v);
                if d.len() != 1 {
                    return Err(bad('e', &v));
                }
                dp.oob_char = Some(d[0]);
            }
            'M' => {
                for f in v.split(',') {
                    match f.chars().next() {
                        Some('r') => dp.mod_http |= MH_SPACE,
                        Some('h') => dp.mod_http |= MH_HMIX,
                        Some('d') => dp.mod_http |= MH_DMIX,
                        _ => return Err(bad('M', &v)),
                    }
                }
            }
            'r' => {
                let part = parse_offset(&v).ok_or_else(|| bad('r', &v))?;
                if part.pos > 0xffff {
                    return Err(bad('r', &v));
                }
                dp.tlsrec.push(part);
            }
            'm' => {
                let n: i64 = v.parse().map_err(|_| bad('m', &v))?;
                if n <= 0 || n > 255 {
                    return Err(bad('m', &v));
                }
                dp.tlsminor = Some(n as u8);
            }
            'a' => {
                let n: i64 = v.parse().map_err(|_| bad('a', &v))?;
                if n < 0 {
                    return Err(bad('a', &v));
                }
                dp.udp_fake_count = n as i32;
            }
            'J' => {
                let n: i64 = v.parse().map_err(|_| bad('J', &v))?;
                if !(0..=64).contains(&n) {
                    return Err(bad('J', &v));
                }
                dp.udp_junk_count = n as i32;
                if dp.udp_junk_min == 0 {
                    dp.udp_junk_min = 64;
                }
                if dp.udp_junk_max == 0 {
                    dp.udp_junk_max = 256;
                }
            }
            'G' => {
                let (a, rest) = strtol(&v);
                let a = a.ok_or_else(|| bad('G', &v))?;
                if !(16..=1024).contains(&a) {
                    return Err(bad('G', &v));
                }
                dp.udp_junk_min = a as i32;
                dp.udp_junk_max = a as i32;
                if let Some(r) = rest.strip_prefix('-') {
                    let (b, r2) = strtol(r);
                    let b = b.ok_or_else(|| bad('G', &v))?;
                    if b < a || b > 1024 || !r2.is_empty() {
                        return Err(bad('G', &v));
                    }
                    dp.udp_junk_max = b as i32;
                } else if !rest.is_empty() {
                    return Err(bad('G', &v));
                }
            }
            'V' => {
                let (a, rest) = strtol(&v);
                let a = a.ok_or_else(|| bad('V', &v))?;
                if a <= 0 || a > 0xffff {
                    return Err(bad('V', &v));
                }
                let mut b = a;
                if let Some(r) = rest.strip_prefix('-') {
                    let (x, r2) = strtol(r);
                    b = x.ok_or_else(|| bad('V', &v))?;
                    if b <= 0 || b > 0xffff || !r2.is_empty() {
                        return Err(bad('V', &v));
                    }
                } else if !rest.is_empty() {
                    return Err(bad('V', &v));
                }
                dp.pf = (a as u16, b as u16);
            }
            'R' => {
                let (a, rest) = strtol(&v);
                let a = a.ok_or_else(|| bad('R', &v))?;
                if a <= 0 {
                    return Err(bad('R', &v));
                }
                let mut b = a;
                if let Some(r) = rest.strip_prefix('-') {
                    let (x, r2) = strtol(r);
                    b = x.ok_or_else(|| bad('R', &v))?;
                    if b <= 0 || !r2.is_empty() {
                        return Err(bad('R', &v));
                    }
                } else if !rest.is_empty() {
                    return Err(bad('R', &v));
                }
                dp.rounds = (a as i32, b as i32);
            }
            'g' => {
                let n: i64 = v.parse().map_err(|_| bad('g', &v))?;
                if n <= 0 || n > 255 {
                    return Err(bad('g', &v));
                }
                p.def_ttl = n as i32;
                p.custom_ttl = true;
            }
            'Y' => dp.drop_sack = true,
            'Z' => p.wait_send = true,
            'W' => p.await_int = v.parse::<u64>().unwrap_or(10),
            'C' => log::warn!("--connect-to is not supported by the Rust engine, ignored"),
            other => return Err(format!("unknown option -{}", other)),
        }
    }

    if all_limited {
        let id = groups.len();
        groups.push(Group::new(id));
    }
    if groups.len() > 64 {
        log::error!("too many groups!");
    }
    p.full_mask = groups.iter().fold(0u64, |m, g| m | g.bit());
    p.groups = groups;
    if p.mode == 0 {
        p.mode = MODE_SOCKS4 | MODE_SOCKS5;
    }
    let ip = listen_ip.unwrap_or(IpAddr::V4(Ipv4Addr::UNSPECIFIED));
    p.listen = SocketAddr::new(ip, listen_port);
    if let Some(IpAddr::V4(_)) = p.bind_ip {
        p.ipv6 = false;
    }
    Ok(ParseOutcome::Run(p))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn args(s: &[&str]) -> Vec<String> {
        std::iter::once("ciadpi").chain(s.iter().copied()).map(String::from).collect()
    }

    fn run(s: &[&str]) -> Params {
        match parse_args(&args(s)).unwrap() {
            ParseOutcome::Run(p) => p,
            _ => panic!(),
        }
    }

    #[test]
    fn offsets() {
        assert_eq!(parse_offset("1+s").unwrap().flag, OFFSET_SNI);
        let p = parse_offset("1:5+sm").unwrap();
        assert_eq!((p.pos, p.r, p.flag), (1, 5, OFFSET_SNI | OFFSET_MID));
        let p = parse_offset("-1").unwrap();
        assert_eq!(p.pos, -1);
        let p = parse_offset("3:7+sm").unwrap();
        assert_eq!((p.pos, p.r), (3, 7));
        let p = parse_offset("1+sd").unwrap();
        assert_eq!(p.flag, OFFSET_SNI | OFFSET_SLD);
        let p = parse_offset("-5+se").unwrap();
        assert_eq!((p.pos, p.flag), (-5, OFFSET_SNI | OFFSET_END));
        assert!(parse_offset("1+q").is_none());
    }

    #[test]
    fn catalog_style_profile() {
        let p = run(&[
            "-T4", "-Ku", "-V443", "-R1-8", "-J8", "-G64-320", "-l:\\xC2\\x00\\x01", "-a10", "-An",
            "-Kt,h", "-H:youtube.com googlevideo.com", "-Qr", "-nwww.google.com", "-f-1", "-t5",
            "-s1+s", "-d3+s", "-An", "-Kt,h", "-H:_limeflow.invalid", "-At,r,s", "-o1", "-d1",
            "-s3+sm", "-r1+s", "--block-quic",
        ]);
        assert_eq!(p.timeout_ms, 4000);
        assert!(p.block_quic);
        assert!(p.auto_reconnect);
        assert_eq!(p.groups[0].proto, IS_UDP);
        assert_eq!(p.groups[0].pf, (443, 443));
        assert_eq!(p.groups[0].rounds, (1, 8));
        assert_eq!(p.groups[0].fake_data.as_deref(), Some(&[0xC2u8, 0, 1][..]));
        assert_eq!(p.groups[0].udp_junk_count, 8);
        assert_eq!((p.groups[0].udp_junk_min, p.groups[0].udp_junk_max), (64, 320));
        let yt = &p.groups[1];
        assert!(yt.hosts.as_ref().unwrap().matches(b"rr1---sn.googlevideo.com"));
        assert!(!yt.hosts.as_ref().unwrap().matches(b"notgooglevideo.com"));
        assert_eq!(yt.fake_mod, FM_RAND);
        assert_eq!(yt.ttl, 5);
        assert_eq!(yt.parts.len(), 3);
        assert_eq!(p.groups[3].detect, DETECT_TORST | DETECT_HTTP_LOCAT | DETECT_TLS_ERR);
        // every group limited -> trailing empty pass-through group
        assert_eq!(p.groups.len(), 5);
    }

    #[test]
    fn copy_group() {
        let p = run(&["-s1", "-An", "-B1"]);
        assert_eq!(p.groups[1].parts.len(), 1);
    }

    #[test]
    fn hostset_rules() {
        let mut h = HostSet::default();
        parse_hosts(&mut h, b"YouTube.com bad_host! ya.ru");
        assert!(h.matches(b"m.youtube.com"));
        assert!(h.matches(b"ya.ru"));
        assert_eq!(h.len(), 2);
    }

    #[test]
    fn cform() {
        assert_eq!(parse_cform("a\\x41\\n\\101"), b"aA\nA".to_vec());
    }
}
