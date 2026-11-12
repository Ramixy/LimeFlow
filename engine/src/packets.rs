//! Protocol helpers: port of ByeDPI `packets.c` plus the split position
//! logic (`gen_offset`) from `desync.c`. Pure functions over byte buffers.

use crate::params::*;
use rand::Rng;

const TLS_PREFIX: &[u8] = b"\
\x16\x03\x01\x02\x00\x01\x00\x01\xfc\x03\x03\x03\x5f\
\x6f\x2c\xed\x13\x22\xf8\xdc\xb2\xf2\x60\x48\x2d\x72\
\x66\x6f\x57\xdd\x13\x9d\x1b\x37\xdc\xfa\x36\x2e\xba\
\xf9\x92\x99\x3a\x20\xf9\xdf\x0c\x2e\x8a\x55\x89\x82\
\x31\x63\x1a\xef\xa8\xbe\x08\x58\xa7\xa3\x5a\x18\xd3\
\x96\x5f\x04\x5c\xb4\x62\xaf\x89\xd7\x0f\x8b\x00\x3e\
\x13\x02\x13\x03\x13\x01\xc0\x2c\xc0\x30\x00\x9f\xcc\
\xa9\xcc\xa8\xcc\xaa\xc0\x2b\xc0\x2f\x00\x9e\xc0\x24\
\xc0\x28\x00\x6b\xc0\x23\xc0\x27\x00\x67\xc0\x0a\xc0\
\x14\x00\x39\xc0\x09\xc0\x13\x00\x33\x00\x9d\x00\x9c\
\x00\x3d\x00\x3c\x00\x35\x00\x2f\x00\xff\x01\x00\x01\
\x75\x00\x00\x00\x16\x00\x14\x00\x00\x11\x77\x77\x77\
\x2e\x77\x69\x6b\x69\x70\x65\x64\x69\x61\x2e\x6f\x72\
\x67\x00\x0b\x00\x04\x03\x00\x01\x02\x00\x0a\x00\x16\
\x00\x14\x00\x1d\x00\x17\x00\x1e\x00\x19\x00\x18\x01\
\x00\x01\x01\x01\x02\x01\x03\x01\x04\x00\x10\x00\x0e\
\x00\x0c\x02\x68\x32\x08\x68\x74\x74\x70\x2f\x31\x2e\
\x31\x00\x16\x00\x00\x00\x17\x00\x00\x00\x31\x00\x00\
\x00\x0d\x00\x2a\x00\x28\x04\x03\x05\x03\x06\x03\x08\
\x07\x08\x08\x08\x09\x08\x0a\x08\x0b\x08\x04\x08\x05\
\x08\x06\x04\x01\x05\x01\x06\x01\x03\x03\x03\x01\x03\
\x02\x04\x02\x05\x02\x06\x02\x00\x2b\x00\x09\x08\x03\
\x04\x03\x03\x03\x02\x03\x01\x00\x2d\x00\x02\x01\x01\
\x00\x33\x00\x26\x00\x24\x00\x1d\x00\x20\x11\x8c\xb8\
\x8c\xe8\x8a\x08\x90\x1e\xee\x19\xd9\xdd\xe8\xd4\x06\
\xb1\xd1\xe2\xab\xe0\x16\x63\xd6\xdc\xda\x84\xa4\xb8\
\x4b\xfb\x0e\x00\x15\x00\xac\x00\x00\x00\x00\x00\x00";

/// ByeDPI default fake ClientHello (www.wikipedia.org), zero padded to 517.
pub const TLS_DATA: [u8; 517] = {
    let mut a = [0u8; 517];
    let mut i = 0;
    while i < TLS_PREFIX.len() {
        a[i] = TLS_PREFIX[i];
        i += 1;
    }
    a
};

pub const HTTP_DATA: &[u8] = b"GET / HTTP/1.1\r\nHost: www.wikipedia.org\r\n\r\n";
pub const UDP_DATA: [u8; 64] = [0u8; 64];

#[inline]
fn antohs(d: &[u8], i: usize) -> usize {
    if i + 1 >= d.len() {
        return 0;
    }
    ((d[i] as usize) << 8) | d[i + 1] as usize
}

#[inline]
fn shtona(d: &mut [u8], i: usize, v: i64) {
    if i + 1 < d.len() {
        let v = v as u16;
        d[i] = (v >> 8) as u8;
        d[i + 1] = v as u8;
    }
}

/// memmove with clamping to the buffer (rightward moves may lose the tail,
/// exactly the bytes that would fall outside the final packet size).
fn mmove(buf: &mut [u8], dst: i64, src: i64, len: i64) {
    if len <= 0 || dst < 0 || src < 0 {
        return;
    }
    let (dst, src) = (dst as usize, src as usize);
    let blen = buf.len();
    if src >= blen || dst >= blen {
        return;
    }
    let len = (len as usize).min(blen - src).min(blen - dst);
    buf.copy_within(src..src + len, dst);
}

pub fn is_tls_chello(b: &[u8]) -> bool {
    b.len() > 5 && antohs(b, 0) == 0x1603 && b[5] == 0x01
}

pub fn is_tls_shello(b: &[u8]) -> bool {
    b.len() > 5 && antohs(b, 0) == 0x1603 && b[5] == 0x02
}

pub fn is_http(b: &[u8]) -> bool {
    if b.len() < 16 || b[0] > b'T' || b[0] < b'C' {
        return false;
    }
    const M: [&[u8]; 9] = [b"HEAD", b"GET", b"POST", b"PUT", b"DELETE", b"OPTIONS", b"CONNECT", b"TRACE", b"PATCH"];
    M.iter().any(|m| b.starts_with(m))
}

fn find_tls_ext_offset(typ: usize, data: &[u8], size: usize, mut skip: usize) -> usize {
    let mut size = size.min(data.len());
    if size <= skip + 2 {
        return 0;
    }
    let ext_len = antohs(data, skip);
    skip += 2;
    if ext_len < size - skip {
        size = ext_len + skip;
    }
    while skip + 4 < size {
        if antohs(data, skip) == typ {
            return skip;
        }
        let len = antohs(data, skip + 2);
        skip += len + 4;
    }
    0
}

fn find_ext_block(data: &[u8], size: usize) -> usize {
    let size = size.min(data.len());
    if size < 44 {
        return 0;
    }
    let sid_len = data[43] as usize;
    if size < 44 + sid_len + 2 {
        return 0;
    }
    let cip_len = antohs(data, 44 + sid_len);
    let skip = 44 + sid_len + 2 + cip_len + 2;
    if skip > size {
        0
    } else {
        skip
    }
}

/// Returns (host offset, host length) of the SNI.
pub fn parse_tls(b: &[u8]) -> Option<(usize, usize)> {
    if !is_tls_chello(b) {
        return None;
    }
    let skip = find_ext_block(b, b.len());
    if skip == 0 {
        return None;
    }
    let sni = find_tls_ext_offset(0x00, b, b.len(), skip);
    if sni == 0 || sni + 12 >= b.len() {
        return None;
    }
    let len = antohs(b, sni + 7);
    if sni + 9 + len > b.len() || len == 0 {
        return None;
    }
    Some((sni + 9, len))
}

fn find_ci(hay: &[u8], needle: &[u8]) -> Option<usize> {
    if needle.len() > hay.len() {
        return None;
    }
    (0..=hay.len() - needle.len()).find(|&i| hay[i..i + needle.len()].eq_ignore_ascii_case(needle))
}

/// Returns (host offset, host length, port) of the Host header.
pub fn parse_http(b: &[u8]) -> Option<(usize, usize, u16)> {
    if !is_http(b) {
        return None;
    }
    let mut host = find_ci(b, b"\nHost:")? + 6;
    while host < b.len() && b[host] == b' ' {
        host += 1;
    }
    let mut l_end = host + b[host..].iter().position(|&c| c == b'\n')?;
    while l_end > host && b[l_end - 1].is_ascii_whitespace() {
        l_end -= 1;
    }
    if l_end <= host {
        return None;
    }
    let mut h_end = l_end - 1;
    while h_end > host && b[h_end - 1].is_ascii_digit() {
        h_end -= 1;
    }
    let mut port: u16 = 80;
    let mut h_end_final = l_end;
    if h_end > host && b[h_end - 1] == b':' && h_end < l_end {
        let s = std::str::from_utf8(&b[h_end..l_end]).ok()?;
        let p: u32 = s.parse().ok()?;
        if p == 0 || p > 0xffff {
            return None;
        }
        port = p as u16;
        h_end_final = h_end - 1;
    }
    let mut start = host;
    if b[start] == b'[' {
        if h_end_final == 0 || b[h_end_final - 1] != b']' {
            return None;
        }
        h_end_final -= 1;
        start += 1;
    }
    if h_end_final <= start {
        return None;
    }
    Some((start, h_end_final - start, port))
}

/// SNI or HTTP Host of the request.
pub fn find_host(b: &[u8]) -> Option<(usize, usize)> {
    parse_tls(b).or_else(|| parse_http(b).map(|(p, l, _)| (p, l)))
}

fn get_http_code(b: &[u8]) -> u32 {
    if b.len() < 13 || !b.starts_with(b"HTTP/1.") {
        return 0;
    }
    if !b[12..].contains(&b'\n') {
        return 0;
    }
    let digits: Vec<u8> = b[9..].iter().take_while(|c| c.is_ascii_digit()).copied().collect();
    let next = b.get(9 + digits.len()).copied().unwrap_or(0);
    let num: u32 = std::str::from_utf8(&digits).ok().and_then(|s| s.parse().ok()).unwrap_or(0);
    if !(100..=511).contains(&num) || !next.is_ascii_whitespace() {
        return 0;
    }
    num
}

pub fn is_http_redirect(req: &[u8], resp: &[u8]) -> bool {
    let (hpos, hlen, _) = match parse_http(req) {
        Some(h) => h,
        None => return false,
    };
    if resp.len() < 29 {
        return false;
    }
    let code = get_http_code(resp);
    if !(300..=308).contains(&code) {
        return false;
    }
    let mut loc = match find_ci(resp, b"\nLocation:") {
        Some(i) => i + 11,
        None => return false,
    };
    if loc + 8 >= resp.len() {
        return false;
    }
    let mut l_end = match resp[loc..].iter().position(|&c| c == b'\n') {
        Some(i) => loc + i,
        None => return false,
    };
    while l_end > loc && resp[l_end - 1].is_ascii_whitespace() {
        l_end -= 1;
    }
    if l_end - loc > 7 {
        if resp[loc..].starts_with(b"http://") {
            loc += 7;
        } else if resp[loc..].starts_with(b"https://") {
            loc += 8;
        }
    }
    let le = resp[loc..l_end].iter().position(|&c| c == b'/').map(|i| loc + i).unwrap_or(l_end);
    // last two labels of the requested host
    let host = &req[hpos..hpos + hlen];
    let mut dots = 0;
    let mut h = host.len();
    while h > 0 {
        if host[h - 1] == b'.' {
            dots += 1;
            if dots == 2 {
                break;
            }
        }
        h -= 1;
    }
    let tail = &host[h..];
    let loc_host = &resp[loc..le];
    loc_host.len() < tail.len() || !loc_host.ends_with(tail)
}

pub fn neq_tls_sid(req: &[u8], resp: &[u8]) -> bool {
    if req.len() < 75 || resp.len() < 75 {
        return false;
    }
    if !is_tls_chello(req) || antohs(resp, 0) != 0x1603 {
        return false;
    }
    let sid_len = req[43] as usize;
    let skip = 44 + sid_len + 3;
    if find_tls_ext_offset(0x2b, resp, resp.len(), skip) == 0 {
        return false;
    }
    if req[43] != resp[43] {
        return true;
    }
    if 44 + sid_len > req.len() || 44 + sid_len > resp.len() {
        return false;
    }
    req[44..44 + sid_len] != resp[44..44 + sid_len]
}

pub fn mod_http(b: &mut [u8], m: u32) -> bool {
    let (host, mut hlen, _) = match parse_http(b) {
        Some(h) => h,
        None => return false,
    };
    // `par` points at "Host:" (4 letters before the colon)
    let mut colon = host.saturating_sub(1);
    while colon > 0 && b[colon] != b':' {
        colon -= 1;
    }
    if colon < 4 {
        return false;
    }
    let par = colon - 4;
    if m & MH_HMIX != 0 {
        b[par] = b[par].to_ascii_lowercase();
        b[par + 1] = b[par + 1].to_ascii_uppercase();
        b[par + 3] = b[par + 3].to_ascii_uppercase();
    }
    if m & MH_DMIX != 0 {
        let mut i = 0;
        while i < hlen {
            b[host + i] = b[host + i].to_ascii_uppercase();
            i += 2;
        }
    }
    if m & MH_SPACE != 0 {
        while host + hlen < b.len() && !b[host + hlen].is_ascii_whitespace() {
            hlen += 1;
        }
        let sc = host - (par + 5);
        b.copy_within(host..host + hlen, par + 5);
        for x in &mut b[par + 5 + hlen..par + 5 + hlen + sc] {
            *x = b'\t';
        }
    }
    true
}

/// Splits the TLS record starting at `base` into two records at `pos`
/// (relative to the record payload). Inserts a 5-byte header.
pub fn part_tls(buf: &mut Vec<u8>, base: usize, pos: i64) -> bool {
    let n = buf.len().saturating_sub(base) as i64;
    if n < 3 || pos < 0 || pos + 5 > n {
        return false;
    }
    let r_sz = antohs(buf, base + 3) as i64;
    if r_sz < pos {
        // The C code reports success here and then miscounts the length;
        // nothing sensible to split, so leave the record alone.
        return false;
    }
    let at = base + 5 + pos as usize;
    let hdr = [buf[base], buf[base + 1], buf[base + 2], 0, 0];
    buf.splice(at..at, hdr);
    shtona(buf, base + 3, pos);
    shtona(buf, at + 3, r_sz - pos);
    true
}

pub fn randomize_tls(b: &mut [u8]) {
    let n = b.len();
    if n < 44 {
        return;
    }
    let sid_len = b[43] as usize;
    if n < 44 + sid_len + 2 {
        return;
    }
    let mut rng = rand::thread_rng();
    rng.fill(&mut b[11..43]);
    rng.fill(&mut b[44..44 + sid_len]);
    let skip = find_ext_block(b, n);
    if skip == 0 {
        return;
    }
    let ks = find_tls_ext_offset(0x0033, b, n, skip);
    if ks == 0 || ks + 6 >= n {
        return;
    }
    let ks_sz = antohs(b, ks + 2);
    if ks + 4 + ks_sz > n {
        return;
    }
    let mut g = ks + 4 + 2;
    while g + 4 < ks + 4 + ks_sz {
        let g_sz = antohs(b, g + 2);
        if ks + 4 + g_sz > n || g + 4 + g_sz > n {
            return;
        }
        rng.fill(&mut b[g + 4..g + 4 + g_sz]);
        g += 4 + g_sz;
    }
}

fn merge_tls_records(b: &mut [u8], n: i64) -> i64 {
    if n < 5 {
        return 0;
    }
    let mut full: i64 = 0;
    let mut r_sz = antohs(b, 3) as i64;
    let mut i = 0;
    loop {
        full += r_sz;
        if 5 + full > n - 5 || b.get((5 + full) as usize) != Some(&b[0]) {
            break;
        }
        r_sz = antohs(b, (5 + full + 3) as usize) as i64;
        if full + 10 + r_sz > n {
            break;
        }
        mmove(b, 5 + full, 10 + full, n - (10 + full));
        i += 1;
    }
    shtona(b, 3, full);
    shtona(b, 7, full - 4);
    i * 5
}

fn copy_name(out: &mut [u8], name: &[u8]) {
    let mut rng = rand::thread_rng();
    for (o, &c) in out.iter_mut().zip(name) {
        *o = match c {
            b'*' => {
                let r = rng.gen_range(0..36u8);
                if r < 10 {
                    b'0' + r
                } else {
                    b'a' + r - 10
                }
            }
            b'?' => b'a' + rng.gen_range(0..26u8),
            b'#' => b'0' + rng.gen_range(0..10u8),
            other => other,
        };
    }
}

fn remove_ks_group(b: &mut [u8], n: i64, skip: usize, group: usize) -> i64 {
    let ks = find_tls_ext_offset(0x0033, b, n as usize, skip) as i64;
    if ks == 0 || ks + 6 >= n {
        return 0;
    }
    let ks_sz = antohs(b, (ks + 2) as usize) as i64;
    if ks + 4 + ks_sz > n {
        return 0;
    }
    let mut g = ks + 4 + 2;
    while g + 4 < ks + 4 + ks_sz {
        let g_sz = antohs(b, (g + 2) as usize) as i64;
        if ks + 4 + g_sz > n {
            return 0;
        }
        if antohs(b, g as usize) == group {
            let g_end = g + 4 + g_sz;
            mmove(b, g, g_end, n - g_end);
            shtona(b, (ks + 2) as usize, ks_sz - (4 + g_sz));
            shtona(b, (ks + 4) as usize, ks_sz - (4 + g_sz) - 2);
            return 4 + g_sz;
        }
        g += 4 + g_sz;
    }
    0
}

fn remove_tls_ext(b: &mut [u8], n: i64, skip: usize, typ: usize) -> i64 {
    let off = find_tls_ext_offset(typ, b, n as usize, skip) as i64;
    if off == 0 {
        return 0;
    }
    let sz = antohs(b, (off + 2) as usize) as i64;
    let end = off + 4 + sz;
    if end > n {
        return 0;
    }
    mmove(b, off, end, n - end);
    sz + 4
}

fn resize_ech_ext(b: &mut [u8], n: i64, skip: usize, mut inc: i64) -> i64 {
    let off = find_tls_ext_offset(0xfe0d, b, n as usize, skip) as i64;
    if off == 0 {
        return 0;
    }
    let ech_sz = antohs(b, (off + 2) as usize) as i64;
    let ech_end = off + 4 + ech_sz;
    if ech_sz < 12 || ech_end > n {
        return 0;
    }
    let enc_sz = antohs(b, (off + 4 + 6) as usize) as i64;
    let pay_offs = off + 4 + 8 + enc_sz;
    let pay_sz = ech_sz - (8 + enc_sz + 2);
    if pay_offs + 2 > n {
        return 0;
    }
    if pay_sz < -inc {
        inc = -pay_sz;
    }
    shtona(b, (off + 2) as usize, ech_sz + inc);
    shtona(b, pay_offs as usize, pay_sz + inc);
    mmove(b, ech_end + inc, ech_end, n - (ech_end + inc));
    inc
}

fn resize_sni(b: &mut [u8], n: i64, sni_offs: i64, sni_sz: i64, new_sz: i64) {
    shtona(b, (sni_offs + 2) as usize, new_sz + 5);
    shtona(b, (sni_offs + 4) as usize, new_sz + 3);
    shtona(b, (sni_offs + 7) as usize, new_sz);
    let sni_end = sni_offs + 4 + sni_sz;
    mmove(b, sni_end + new_sz - (sni_sz - 5), sni_end, n - sni_end);
}

/// Replaces the SNI of the ClientHello in `b[..n]` with `host`, keeping the
/// packet `nn` bytes long (padding / dropped extensions absorb the difference).
/// `b.len()` must be at least `max(n, nn)`.
pub fn change_tls_sni(host: &[u8], b: &mut [u8], n: i64, nn: i64) -> Result<(), ()> {
    let mut avail = merge_tls_records(b, n);
    avail += nn - n;
    let mut r_sz = antohs(b, 3) as i64;
    r_sz += avail;
    let skip = find_ext_block(b, n as usize);
    if skip == 0 {
        return Err(());
    }
    let mut sni = find_tls_ext_offset(0x00, b, n as usize, skip) as i64;
    if sni == 0 {
        return Err(());
    }
    let new_sz = host.len() as i64;
    let sni_sz = antohs(b, (sni + 2) as usize) as i64;
    if sni + 4 + sni_sz > n {
        return Err(());
    }
    let mut diff = new_sz - (sni_sz - 5);
    avail -= diff;
    if diff < 0 && avail > 0 {
        resize_sni(b, n, sni, sni_sz, new_sz);
        diff = 0;
    }
    if avail != 0 {
        avail -= resize_ech_ext(b, n, skip, avail);
    }
    if avail < -50 {
        avail += remove_ks_group(b, n, skip, 0x11ec);
    }
    const EXTS: [usize; 9] = [0x0015, 0x0031, 0x0010, 0x001c, 0x0023, 0x0005, 0x0022, 0x0012, 0x001b];
    let mut e = 0;
    while avail != 0 && avail < 4 {
        if e >= EXTS.len() {
            return Err(());
        }
        avail += remove_tls_ext(b, n, skip, EXTS[e]);
        e += 1;
    }
    sni = find_tls_ext_offset(0x00, b, n as usize, skip) as i64;
    if sni == 0 {
        return Err(());
    }
    if diff != 0 {
        resize_sni(b, n, sni, sni_sz, new_sz);
    }
    let start = (sni + 9) as usize;
    if start + host.len() > b.len() {
        return Err(());
    }
    copy_name(&mut b[start..start + host.len()], host);
    if avail > 0 {
        avail -= resize_ech_ext(b, n, skip, avail);
    }
    if avail >= 4 {
        let at = 5 + r_sz - avail;
        if at >= 0 && (at + avail) as usize <= b.len() {
            shtona(b, at as usize, 0x0015);
            shtona(b, (at + 2) as usize, avail - 4);
            for x in &mut b[(at + 4) as usize..(at + avail) as usize] {
                *x = 0;
            }
        }
    }
    shtona(b, 3, r_sz);
    shtona(b, 7, r_sz - 4);
    shtona(b, skip, 5 + r_sz - skip as i64 - 2);
    Ok(())
}

/// Lazily parsed request info (ByeDPI `proto_info`).
#[derive(Default, Clone, Copy, Debug)]
pub struct ProtoInfo {
    pub init: bool,
    pub typ: u32,
    pub host_pos: usize,
    pub host_len: usize,
}

impl ProtoInfo {
    pub fn init_from(&mut self, b: &[u8]) {
        if self.init {
            return;
        }
        if let Some((p, l)) = parse_tls(b) {
            self.typ = IS_HTTPS;
            self.host_pos = p;
            self.host_len = l;
        } else if let Some((p, l, _)) = parse_http(b) {
            self.typ = IS_HTTP;
            self.host_pos = p;
            self.host_len = l;
        }
        self.init = true;
    }
}

/// Split position for a part (ByeDPI `gen_offset`). Returns -1 when the
/// anchor (SNI/Host) is missing.
pub fn gen_offset(mut pos: i64, flag: u32, b: &[u8], n: usize, lp: i64, info: &mut ProtoInfo) -> i64 {
    let n_i = n as i64;
    if flag & (OFFSET_SNI | OFFSET_HOST) != 0 {
        info.init_from(&b[..n.min(b.len())]);
        if info.host_pos == 0 || (flag & OFFSET_SNI != 0 && info.typ != IS_HTTPS) {
            return -1;
        }
        pos += info.host_pos as i64;
        let hl = info.host_len as i64;
        if flag & OFFSET_EXTSTART != 0 {
            if info.host_pos < 9 {
                return -1;
            }
            pos -= 9;
        } else if flag & OFFSET_SLD != 0 {
            let host = &b[info.host_pos..info.host_pos + info.host_len];
            let mut tld = hl;
            while tld > 0 && host[(tld - 1) as usize] != b'.' {
                tld -= 1;
            }
            let dot = if tld > 0 { tld - 1 } else { hl };
            let mut sld = dot;
            while sld > 0 && host[(sld - 1) as usize] != b'.' {
                sld -= 1;
            }
            pos += (sld + dot) / 2;
        } else if flag & OFFSET_END != 0 {
            pos += hl;
        } else if flag & OFFSET_MID != 0 {
            pos += hl / 2;
        } else if flag & OFFSET_RAND != 0 && hl > 0 {
            pos += rand::thread_rng().gen_range(0..hl);
        }
    } else if flag & OFFSET_RAND != 0 {
        let span = n_i - lp;
        pos += lp + if span > 0 { rand::thread_rng().gen_range(0..span) } else { 0 };
    } else if flag & OFFSET_MID != 0 {
        pos += n_i / 2;
    } else if pos < 0 || flag & OFFSET_END != 0 {
        pos += n_i;
    }
    pos
}

/// tlsminor / mod-http / tlsrec applied to the first request (ByeDPI `tamp`).
pub fn tamp(buf: &mut Vec<u8>, dp: &Group, info: &mut ProtoInfo) {
    if dp.mod_http != 0 && is_http(buf) && !mod_http(buf, dp.mod_http) {
        log::warn!("mod http error");
    }
    if let Some(minor) = dp.tlsminor {
        if is_tls_chello(buf) {
            buf[2] = minor;
        }
    }
    if dp.tlsrec.is_empty() || !is_tls_chello(buf) {
        return;
    }
    let mut lp: i64 = 0;
    let mut part = Part::default();
    let (mut i, mut r, mut rc) = (0usize, 0i32, 0i64);
    while r > 0 || i < dp.tlsrec.len() {
        if r <= 0 {
            part = dp.tlsrec[i];
            r = part.r.max(1);
            i += 1;
        }
        let n = buf.len() as i64;
        let mut pos = rc * 5;
        let sub = (n - pos).max(0) as usize;
        pos += gen_offset(part.pos, part.flag, buf, sub, lp, info);
        if part.pos < 0 || part.flag != 0 {
            pos -= 5;
        }
        pos += part.s as i64 * (part.r.max(1) - r) as i64;
        if pos < lp {
            log::debug!("tlsrec cancel: {} < {}", pos, lp);
            break;
        }
        if !part_tls(buf, lp as usize, pos - lp) {
            log::debug!("tlsrec error: pos={}", pos);
            break;
        }
        lp = pos + 5;
        rc += 1;
        r -= 1;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn chello(host: &str) -> Vec<u8> {
        let mut v = TLS_DATA.to_vec();
        change_tls_sni(host.as_bytes(), &mut v, 517, 517).unwrap();
        v
    }

    #[test]
    fn default_fake_has_wikipedia_sni() {
        let (p, l) = parse_tls(&TLS_DATA).unwrap();
        assert_eq!(&TLS_DATA[p..p + l], b"www.wikipedia.org");
    }

    #[test]
    fn sni_change_keeps_size_and_parses() {
        for h in ["ya.ru", "www.google.com", "very-long-hostname.example.example.org"] {
            let v = chello(h);
            assert_eq!(v.len(), 517);
            let (p, l) = parse_tls(&v).unwrap();
            assert_eq!(&v[p..p + l], h.as_bytes());
            assert_eq!(antohs(&v, 3) + 5, 517, "record length for {}", h);
        }
    }

    #[test]
    fn http_host_and_redirect() {
        let req = b"GET / HTTP/1.1\r\nHost: www.youtube.com:8080\r\n\r\n";
        let (p, l, port) = parse_http(req).unwrap();
        assert_eq!(&req[p..p + l], b"www.youtube.com");
        assert_eq!(port, 8080);
        let resp = b"HTTP/1.1 302 Found\r\nLocation: http://warning.rt.ru/\r\n\r\n";
        assert!(is_http_redirect(req, resp));
        let same = b"HTTP/1.1 301 Moved\r\nLocation: https://m.youtube.com/x\r\n\r\n";
        assert!(!is_http_redirect(req, same));
    }

    #[test]
    fn tlsrec_split_at_sni() {
        let mut v = chello("www.youtube.com");
        let mut info = ProtoInfo::default();
        let mut g = Group::default();
        g.tlsrec.push(parse_offset("1+s").unwrap());
        tamp(&mut v, &g, &mut info);
        assert_eq!(v.len(), 522);
        let first = antohs(&v, 3);
        assert_eq!(v[5 + first], 0x16);
        assert_eq!(antohs(&v, 5 + first + 3) + first, 512);
    }

    #[test]
    fn offsets_follow_byedpi() {
        let v = chello("www.youtube.com");
        let (hp, hl) = parse_tls(&v).unwrap();
        let mut info = ProtoInfo::default();
        assert_eq!(gen_offset(1, OFFSET_SNI, &v, v.len(), 0, &mut info), hp as i64 + 1);
        assert_eq!(gen_offset(0, OFFSET_SNI | OFFSET_END, &v, v.len(), 0, &mut info), (hp + hl) as i64);
        assert_eq!(gen_offset(-1, 0, &v, v.len(), 0, &mut info), v.len() as i64 - 1);
        // midsld of www.youtube.com: sld "youtube" spans 4..11 -> 7
        assert_eq!(gen_offset(0, OFFSET_SNI | OFFSET_SLD, &v, v.len(), 0, &mut info), hp as i64 + 7);
        assert_eq!(gen_offset(0, OFFSET_SNI | OFFSET_EXTSTART, &v, v.len(), 0, &mut info), hp as i64 - 9);
    }

    #[test]
    fn randomize_keeps_structure() {
        let mut v = chello("ya.ru");
        randomize_tls(&mut v);
        assert!(parse_tls(&v).is_some());
    }

    #[test]
    fn mod_http_mixes_case() {
        let mut req = b"GET / HTTP/1.1\r\nHost: example.com\r\n\r\n".to_vec();
        assert!(mod_http(&mut req, MH_HMIX | MH_DMIX));
        assert!(req.windows(5).any(|w| w == b"hOsT:"));
        assert!(req.windows(4).any(|w| w == b"ExAm"));
    }
}
