use crate::config::*;
use crate::crypto::xor_mask_in_place;
use crate::{ldebug};
use base64::Engine;
use byteorder::{BigEndian, ByteOrder};
use rand::RngCore;
use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::{ClientConfig, DigitallySignedStruct, SignatureScheme};
use rustls_pki_types::{CertificateDer, ServerName, UnixTime};
use std::collections::HashMap;
use std::future::Future;
use std::net::IpAddr;
use std::pin::Pin;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::task::{Context, Poll};
use std::time::Duration;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt, BufReader, ReadBuf};
use tokio::net::TcpStream;
use tokio_rustls::client::TlsStream;
use tokio_rustls::TlsConnector;

// ---------------------------------------------------------------------------
// WS opcodes
// ---------------------------------------------------------------------------

pub const OP_CONT: u8 = 0x0;
pub const OP_TEXT: u8 = 0x1;
pub const OP_BINARY: u8 = 0x2;
pub const OP_CLOSE: u8 = 0x8;
pub const OP_PING: u8 = 0x9;
pub const OP_PONG: u8 = 0xA;

// ---------------------------------------------------------------------------
// TLS config: InsecureSkipVerify + session cache (как в Go)
// ---------------------------------------------------------------------------

#[derive(Debug)]
struct NoVerify;

impl ServerCertVerifier for NoVerify {
    fn verify_server_cert(
        &self,
        _end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _server_name: &ServerName<'_>,
        _ocsp_response: &[u8],
        _now: UnixTime,
    ) -> Result<ServerCertVerified, rustls::Error> {
        Ok(ServerCertVerified::assertion())
    }

    fn verify_tls12_signature(
        &self,
        _message: &[u8],
        _cert: &CertificateDer<'_>,
        _dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        Ok(HandshakeSignatureValid::assertion())
    }

    fn verify_tls13_signature(
        &self,
        _message: &[u8],
        _cert: &CertificateDer<'_>,
        _dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        Ok(HandshakeSignatureValid::assertion())
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        vec![
            SignatureScheme::RSA_PKCS1_SHA256,
            SignatureScheme::RSA_PKCS1_SHA384,
            SignatureScheme::RSA_PKCS1_SHA512,
            SignatureScheme::ECDSA_NISTP256_SHA256,
            SignatureScheme::ECDSA_NISTP384_SHA384,
            SignatureScheme::ECDSA_NISTP521_SHA512,
            SignatureScheme::RSA_PSS_SHA256,
            SignatureScheme::RSA_PSS_SHA384,
            SignatureScheme::RSA_PSS_SHA512,
            SignatureScheme::ED25519,
        ]
    }
}

use once_cell::sync::Lazy;

// Глобальный TLS-конфиг с session resumption cache (аналог tls.NewLRUClientSessionCache(100))
static TLS_CONFIG: Lazy<Arc<ClientConfig>> = Lazy::new(|| {
    let mut cfg = ClientConfig::builder()
        .dangerous()
        .with_custom_certificate_verifier(Arc::new(NoVerify))
        .with_no_client_auth();
    cfg.resumption = rustls::client::Resumption::in_memory_sessions(100);
    Arc::new(cfg)
});

// ---------------------------------------------------------------------------
// WsHandshakeError
// ---------------------------------------------------------------------------

#[derive(Debug, Clone)]
pub struct WsHandshakeError {
    pub status_code: i32,
    pub status_line: String,
    pub headers: HashMap<String, String>,
    pub location: String,
}

impl WsHandshakeError {
    pub fn is_redirect(&self) -> bool {
        matches!(self.status_code, 301 | 302 | 303 | 307 | 308)
    }
}

#[derive(Debug)]
pub enum WsError {
    Io(std::io::Error),
    Handshake(WsHandshakeError),
    Timeout,
    Canceled,
    Other(String),
}

impl WsError {
    pub fn compact(&self) -> String {
        match self {
            WsError::Canceled => "canceled".to_string(),
            WsError::Timeout => "timeout".to_string(),
            WsError::Handshake(h) => format!("http {}", h.status_code),
            WsError::Io(e) => {
                if e.kind() == std::io::ErrorKind::TimedOut
                    || e.kind() == std::io::ErrorKind::WouldBlock
                {
                    "timeout".to_string()
                } else {
                    e.to_string()
                }
            }
            WsError::Other(s) => s.clone(),
        }
    }
    pub fn handshake_status(&self) -> Option<i32> {
        if let WsError::Handshake(h) = self {
            Some(h.status_code)
        } else {
            None
        }
    }
    pub fn handshake(&self) -> Option<&WsHandshakeError> {
        if let WsError::Handshake(h) = self {
            Some(h)
        } else {
            None
        }
    }
}

pub fn is_http_status_error(err: &WsError, code: i32) -> bool {
    err.handshake_status() == Some(code)
}

impl From<std::io::Error> for WsError {
    fn from(e: std::io::Error) -> Self {
        WsError::Io(e)
    }
}

// ---------------------------------------------------------------------------
// DesyncStream & SOCKS5 upstream support for DPI circumvention
// ---------------------------------------------------------------------------

// Режимы десинхронизации первого пакета (ClientHello):
//   0 — старый: TCP-разрез на split_pos байт
//   1 — TCP-разрез посреди SNI (hostname)
//   2 — TLS record split посреди SNI + TCP-разрез посреди SNI (по умолчанию)
// TLS record split ломает DPI, который ищет SNI в одной TLS-записи и не
// собирает несколько записей (типичное поведение ТСПУ у мобильных операторов).
pub const DESYNC_MODE_SPLIT: i32 = 0;
pub const DESYNC_MODE_SNI_SPLIT: i32 = 1;
pub const DESYNC_MODE_TLSREC: i32 = 2;

/// Возвращает (смещение начала hostname, длина) внутри TLS ClientHello,
/// если `buf` начинается с полной TLS-записи handshake/ClientHello с SNI.
pub fn find_sni(buf: &[u8]) -> Option<(usize, usize)> {
    if buf.len() < 5 || buf[0] != 0x16 || buf[1] != 0x03 {
        return None;
    }
    let rec_len = ((buf[3] as usize) << 8) | buf[4] as usize;
    let rec_end = 5 + rec_len;
    if buf.len() < rec_end || rec_len < 4 || buf[5] != 0x01 {
        return None;
    }
    let rd16 = |p: usize| -> Option<usize> {
        if p + 2 > rec_end {
            None
        } else {
            Some(((buf[p] as usize) << 8) | buf[p + 1] as usize)
        }
    };
    // handshake header (4) + legacy_version (2) + random (32)
    let mut p = 5 + 4 + 2 + 32;
    if p + 1 > rec_end {
        return None;
    }
    p += 1 + buf[p] as usize; // session_id
    p += 2 + rd16(p)?; // cipher_suites
    if p + 1 > rec_end {
        return None;
    }
    p += 1 + buf[p] as usize; // compression_methods
    let ext_total = rd16(p)?;
    p += 2;
    let ext_end = (p + ext_total).min(rec_end);
    while p + 4 <= ext_end {
        let ext_type = rd16(p)?;
        let ext_len = rd16(p + 2)?;
        let body = p + 4;
        if body + ext_len > ext_end {
            return None;
        }
        if ext_type == 0 {
            // server_name_list len(2) + name_type(1) + name len(2) + name
            if ext_len < 5 || buf[body + 2] != 0 {
                return None;
            }
            let name_len = rd16(body + 3)?;
            let name_start = body + 5;
            if name_len == 0 || name_start + name_len > body + ext_len {
                return None;
            }
            return Some((name_start, name_len));
        }
        p = body + ext_len;
    }
    None
}

/// Строит переписанный первый пакет и длины TCP-сегментов, на которые его
/// надо порезать. None — пакет не трогаем.
pub fn plan_desync(buf: &[u8], mode: i32, split_pos: usize) -> Option<(Vec<u8>, Vec<usize>)> {
    let sni = if mode == DESYNC_MODE_SPLIT { None } else { find_sni(buf) };
    match sni {
        Some((host_start, host_len)) => {
            let host_mid = host_start + (host_len / 2).max(1);
            if mode == DESYNC_MODE_TLSREC {
                let rec_len = ((buf[3] as usize) << 8) | buf[4] as usize;
                let payload = &buf[5..5 + rec_len];
                let k = host_mid - 5; // точка разреза внутри payload записи
                if k == 0 || k >= rec_len {
                    return None;
                }
                let mut out = Vec::with_capacity(buf.len() + 5);
                out.extend_from_slice(&[0x16, buf[1], buf[2], (k >> 8) as u8, k as u8]);
                out.extend_from_slice(&payload[..k]);
                let rest = rec_len - k;
                out.extend_from_slice(&[0x16, buf[1], buf[2], (rest >> 8) as u8, rest as u8]);
                out.extend_from_slice(&payload[k..]);
                out.extend_from_slice(&buf[5 + rec_len..]);
                // TCP: [начало .. середина первой половины hostname] | остальное.
                // Hostname оказывается разрезан и по TCP, и по TLS-записям.
                let cut = (host_start + (host_mid - host_start) / 2).max(host_start + 1);
                let mut segs = Vec::new();
                if split_pos > 0 && split_pos < cut {
                    segs.push(split_pos);
                    segs.push(cut - split_pos);
                } else {
                    segs.push(cut);
                }
                segs.push(out.len() - cut);
                Some((out, segs))
            } else {
                let cut = host_mid;
                if cut >= buf.len() {
                    return None;
                }
                let mut segs = Vec::new();
                if split_pos > 0 && split_pos < cut {
                    segs.push(split_pos);
                    segs.push(cut - split_pos);
                } else {
                    segs.push(cut);
                }
                segs.push(buf.len() - cut);
                Some((buf.to_vec(), segs))
            }
        }
        None => {
            if split_pos > 0 && buf.len() > split_pos {
                Some((buf.to_vec(), vec![split_pos, buf.len() - split_pos]))
            } else {
                None
            }
        }
    }
}

pub struct DesyncStream<S> {
    inner: S,
    enabled: bool,
    mode: i32,
    split_pos: usize,
    first_done: bool,
    // переписанный первый пакет и план его отправки
    pending: Vec<u8>,
    segs: std::collections::VecDeque<usize>,
    off: usize,
    seg_left: usize,
    consumed: usize,
    delay: Option<Pin<Box<tokio::time::Sleep>>>,
}

impl<S> DesyncStream<S> {
    pub fn new(inner: S, split_pos: usize) -> Self {
        Self::with_mode(inner, split_pos > 0, DESYNC_MODE_TLSREC, split_pos)
    }

    pub fn with_mode(inner: S, enabled: bool, mode: i32, split_pos: usize) -> Self {
        Self {
            inner,
            enabled,
            mode,
            split_pos,
            first_done: !enabled,
            pending: Vec::new(),
            segs: std::collections::VecDeque::new(),
            off: 0,
            seg_left: 0,
            consumed: 0,
            delay: None,
        }
    }
}

impl<S: AsyncRead + Unpin> AsyncRead for DesyncStream<S> {
    fn poll_read(
        mut self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: &mut ReadBuf<'_>,
    ) -> Poll<std::io::Result<()>> {
        Pin::new(&mut self.inner).poll_read(cx, buf)
    }
}

impl<S: AsyncWrite + Unpin> AsyncWrite for DesyncStream<S> {
    fn poll_write(
        mut self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: &[u8],
    ) -> Poll<std::io::Result<usize>> {
        let this = &mut *self;
        if this.first_done {
            return Pin::new(&mut this.inner).poll_write(cx, buf);
        }

        // Первый вызов: строим план. rustls при Pending повторяет запись тех
        // же байт, поэтому копия в pending корректна.
        if this.pending.is_empty() {
            match plan_desync(buf, this.mode, this.split_pos) {
                Some((data, segs)) => {
                    this.pending = data;
                    this.segs = segs.into_iter().filter(|&n| n > 0).collect();
                    this.off = 0;
                    this.seg_left = this.segs.pop_front().unwrap_or(0);
                    this.consumed = buf.len();
                }
                None => {
                    this.first_done = true;
                    return Pin::new(&mut this.inner).poll_write(cx, buf);
                }
            }
        }

        loop {
            if let Some(d) = this.delay.as_mut() {
                match d.as_mut().poll(cx) {
                    Poll::Ready(()) => this.delay = None,
                    Poll::Pending => return Poll::Pending,
                }
            }
            if this.seg_left == 0 {
                match this.segs.pop_front() {
                    Some(n) => this.seg_left = n,
                    None => {
                        this.first_done = true;
                        this.pending = Vec::new();
                        let n = this.consumed.min(buf.len().max(1));
                        return Poll::Ready(Ok(n));
                    }
                }
            }
            let end = this.off + this.seg_left;
            match Pin::new(&mut this.inner).poll_write(cx, &this.pending[this.off..end]) {
                Poll::Ready(Ok(0)) => {
                    return Poll::Ready(Err(std::io::Error::new(
                        std::io::ErrorKind::WriteZero,
                        "desync write zero",
                    )))
                }
                Poll::Ready(Ok(w)) => {
                    this.off += w;
                    this.seg_left -= w;
                    if this.seg_left == 0 && !this.segs.is_empty() {
                        // Пауза между сегментами, чтобы ядро не склеило их
                        // и DPI получил разрезанные пакеты по отдельности.
                        let ms = DPI_SEGMENT_DELAY_MS.load(Ordering::Relaxed).max(0) as u64;
                        if ms > 0 {
                            this.delay =
                                Some(Box::pin(tokio::time::sleep(Duration::from_millis(ms))));
                        }
                    }
                }
                Poll::Ready(Err(e)) => return Poll::Ready(Err(e)),
                Poll::Pending => return Poll::Pending,
            }
        }
    }

    fn poll_flush(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<std::io::Result<()>> {
        Pin::new(&mut self.inner).poll_flush(cx)
    }

    fn poll_shutdown(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<std::io::Result<()>> {
        Pin::new(&mut self.inner).poll_shutdown(cx)
    }
}

pub type UpstreamStream = DesyncStream<TcpStream>;
pub type UpstreamTlsStream = TlsStream<UpstreamStream>;

pub enum UpstreamConn {
    Tls(UpstreamTlsStream),
    Plain(UpstreamStream),
}

impl AsyncRead for UpstreamConn {
    fn poll_read(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: &mut ReadBuf<'_>,
    ) -> Poll<std::io::Result<()>> {
        match self.get_mut() {
            UpstreamConn::Tls(s) => Pin::new(s).poll_read(cx, buf),
            UpstreamConn::Plain(s) => Pin::new(s).poll_read(cx, buf),
        }
    }
}

impl AsyncWrite for UpstreamConn {
    fn poll_write(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: &[u8],
    ) -> Poll<std::io::Result<usize>> {
        match self.get_mut() {
            UpstreamConn::Tls(s) => Pin::new(s).poll_write(cx, buf),
            UpstreamConn::Plain(s) => Pin::new(s).poll_write(cx, buf),
        }
    }

    fn poll_flush(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<std::io::Result<()>> {
        match self.get_mut() {
            UpstreamConn::Tls(s) => Pin::new(s).poll_flush(cx),
            UpstreamConn::Plain(s) => Pin::new(s).poll_flush(cx),
        }
    }

    fn poll_shutdown(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<std::io::Result<()>> {
        match self.get_mut() {
            UpstreamConn::Tls(s) => Pin::new(s).poll_shutdown(cx),
            UpstreamConn::Plain(s) => Pin::new(s).poll_shutdown(cx),
        }
    }
}

pub async fn connect_socks5(
    proxy_port: u16,
    target_host: &str,
    target_port: u16,
) -> std::io::Result<TcpStream> {
    let proxy_addr = format!("127.0.0.1:{}", proxy_port);
    let mut stream = TcpStream::connect(&proxy_addr).await?;
    let _ = stream.set_nodelay(true);

    // 1. Send SOCKS5 greeting
    stream.write_all(&[0x05, 0x01, 0x00]).await?;
    let mut auth_resp = [0u8; 2];
    stream.read_exact(&mut auth_resp).await?;
    if auth_resp[0] != 0x05 || auth_resp[1] != 0x00 {
        return Err(std::io::Error::new(
            std::io::ErrorKind::PermissionDenied,
            "SOCKS5 auth failed",
        ));
    }

    // 2. Send CONNECT request
    let mut req = Vec::with_capacity(32);
    req.extend_from_slice(&[0x05, 0x01, 0x00]); // VER, CMD (CONNECT), RSV

    if let Ok(ip) = target_host.parse::<std::net::IpAddr>() {
        match ip {
            std::net::IpAddr::V4(v4) => {
                req.push(0x01); // ATYP IPv4
                req.extend_from_slice(&v4.octets());
            }
            std::net::IpAddr::V6(v6) => {
                req.push(0x04); // ATYP IPv6
                req.extend_from_slice(&v6.octets());
            }
        }
    } else {
        req.push(0x03); // ATYP DOMAINNAME
        req.push(target_host.len() as u8);
        req.extend_from_slice(target_host.as_bytes());
    }
    req.extend_from_slice(&target_port.to_be_bytes());

    stream.write_all(&req).await?;

    // 3. Read response: [VER, REP, RSV, ATYP, BND.ADDR, BND.PORT]
    let mut header = [0u8; 4];
    stream.read_exact(&mut header).await?;
    if header[1] != 0x00 {
        return Err(std::io::Error::new(
            std::io::ErrorKind::ConnectionRefused,
            format!("SOCKS5 connect rejected: status 0x{:02x}", header[1]),
        ));
    }

    // Drain bound address and port
    match header[3] {
        0x01 => {
            let mut addr = [0u8; 4 + 2]; // IPv4 + port
            stream.read_exact(&mut addr).await?;
        }
        0x04 => {
            let mut addr = [0u8; 16 + 2]; // IPv6 + port
            stream.read_exact(&mut addr).await?;
        }
        0x03 => {
            let mut len = [0u8; 1];
            stream.read_exact(&mut len).await?;
            let mut domain_buf = vec![0u8; len[0] as usize + 2];
            stream.read_exact(&mut domain_buf).await?;
        }
        _ => {}
    }

    Ok(stream)
}

// ---------------------------------------------------------------------------
// RawWebSocket
// ---------------------------------------------------------------------------

pub struct RawWebSocket {
    reader: tokio::sync::Mutex<BufReader<tokio::io::ReadHalf<UpstreamConn>>>,
    writer: tokio::sync::Mutex<tokio::io::WriteHalf<UpstreamConn>>,
    frag: tokio::sync::Mutex<Vec<u8>>,
    pub closed: AtomicBool,
}

impl RawWebSocket {
    pub fn is_closed(&self) -> bool {
        self.closed.load(Ordering::Relaxed)
    }

    pub async fn send(&self, data: &[u8]) -> Result<(), WsError> {
        if self.is_closed() {
            return Err(WsError::Other("WebSocket closed".to_string()));
        }
        let frame = build_frame(OP_BINARY, data, true);
        self.write_frame(&frame, WS_WRITE_TIMEOUT).await
    }

    pub async fn send_batch(&self, parts: &[Vec<u8>]) -> Result<(), WsError> {
        if self.is_closed() {
            return Err(WsError::Other("WebSocket closed".to_string()));
        }
        let mut writer = self.writer.lock().await;
        for part in parts {
            let frame = build_frame(OP_BINARY, part, true);
            match tokio::time::timeout(WS_WRITE_TIMEOUT, writer.write_all(&frame)).await {
                Ok(Ok(())) => {}
                Ok(Err(e)) => {
                    self.closed.store(true, Ordering::Relaxed);
                    return Err(WsError::Io(e));
                }
                Err(_) => {
                    self.closed.store(true, Ordering::Relaxed);
                    return Err(WsError::Timeout);
                }
            }
        }
        match tokio::time::timeout(WS_WRITE_TIMEOUT, writer.flush()).await {
            Ok(Ok(())) => Ok(()),
            Ok(Err(e)) => {
                self.closed.store(true, Ordering::Relaxed);
                Err(WsError::Io(e))
            }
            Err(_) => {
                self.closed.store(true, Ordering::Relaxed);
                Err(WsError::Timeout)
            }
        }
    }

    pub async fn send_ping(&self) -> Result<(), WsError> {
        if self.is_closed() {
            return Err(WsError::Other("WebSocket closed".to_string()));
        }
        let frame = build_frame(OP_PING, &[], true);
        self.write_frame(&frame, WS_CONTROL_TIMEOUT).await
    }

    async fn write_frame(&self, frame: &[u8], timeout: Duration) -> Result<(), WsError> {
        let mut writer = self.writer.lock().await;
        let res = if timeout > Duration::ZERO {
            tokio::time::timeout(timeout, async {
                writer.write_all(frame).await?;
                writer.flush().await
            })
            .await
        } else {
            Ok(async {
                writer.write_all(frame).await?;
                writer.flush().await
            }
            .await)
        };
        match res {
            Ok(Ok(())) => Ok(()),
            Ok(Err(e)) => {
                self.closed.store(true, Ordering::Relaxed);
                Err(WsError::Io(e))
            }
            Err(_) => {
                self.closed.store(true, Ordering::Relaxed);
                Err(WsError::Timeout)
            }
        }
    }

    // Recv reassembles fragmented WebSocket messages and responds to control frames
    pub async fn recv(&self) -> Result<Vec<u8>, WsError> {
        while !self.is_closed() {
            let (opcode, payload, fin) = match self.read_frame().await {
                Ok(v) => v,
                Err(e) => {
                    self.closed.store(true, Ordering::Relaxed);
                    return Err(e);
                }
            };
            match opcode {
                OP_CLOSE => {
                    self.closed.store(true, Ordering::Relaxed);
                    let mut close_payload = payload;
                    if close_payload.len() > 2 {
                        close_payload.truncate(2);
                    }
                    let reply = build_frame(OP_CLOSE, &close_payload, true);
                    let _ = self.write_frame(&reply, WS_CONTROL_TIMEOUT).await;
                    return Err(WsError::Io(std::io::Error::new(
                        std::io::ErrorKind::UnexpectedEof,
                        "EOF",
                    )));
                }
                OP_PING => {
                    let pong = build_frame(OP_PONG, &payload, true);
                    let _ = self.write_frame(&pong, WS_CONTROL_TIMEOUT).await;
                    continue;
                }
                OP_PONG => continue,
                OP_CONT | OP_TEXT | OP_BINARY => {
                    let mut frag = self.frag.lock().await;
                    if fin && frag.is_empty() {
                        return Ok(payload);
                    }
                    frag.extend_from_slice(&payload);
                    if frag.len() > 16 * 1024 * 1024 {
                        self.closed.store(true, Ordering::Relaxed);
                        return Err(WsError::Other(format!(
                            "WS message too large: {} bytes",
                            frag.len()
                        )));
                    }
                    if !fin {
                        continue;
                    }
                    let message = std::mem::take(&mut *frag);
                    return Ok(message);
                }
                _ => continue,
            }
        }
        Err(WsError::Io(std::io::Error::new(
            std::io::ErrorKind::UnexpectedEof,
            "EOF",
        )))
    }

    pub async fn close(&self) {
        if self.closed.swap(true, Ordering::Relaxed) {
            return;
        }
        let frame = build_frame(OP_CLOSE, &[], true);
        let _ = self.write_frame(&frame, WS_CONTROL_TIMEOUT).await;
        // Skipping writer.shutdown().await to avoid hanging on dead connections
    }

    pub async fn recv_with_timeout(&self, dur: Duration) -> Result<Vec<u8>, WsError> {
        match tokio::time::timeout(dur, self.recv()).await {
            Ok(v) => v,
            Err(_) => Err(WsError::Timeout),
        }
    }

    async fn read_frame(&self) -> Result<(u8, Vec<u8>, bool), WsError> {
        let mut reader = self.reader.lock().await;
        read_frame_locked(&mut reader).await
    }
}

// Чтение одного фрейма из уже захваченного reader.
async fn read_frame_locked(
    reader: &mut BufReader<tokio::io::ReadHalf<UpstreamConn>>,
) -> Result<(u8, Vec<u8>, bool), WsError> {
    let mut hdr = [0u8; 2];
    reader.read_exact(&mut hdr).await?;

    let fin = (hdr[0] & 0x80) != 0;
    let opcode = hdr[0] & 0x0F;
    let mut length = (hdr[1] & 0x7F) as u64;

    if length == 126 {
        let mut buf = [0u8; 2];
        reader.read_exact(&mut buf).await?;
        length = BigEndian::read_u16(&buf) as u64;
    } else if length == 127 {
        let mut buf = [0u8; 8];
        reader.read_exact(&mut buf).await?;
        length = BigEndian::read_u64(&buf);
    }

    let has_mask = (hdr[1] & 0x80) != 0;
    let mut mask_key = [0u8; 4];
    if has_mask {
        reader.read_exact(&mut mask_key).await?;
    }

    const MAX_FRAME_PAYLOAD: u64 = 16 * 1024 * 1024;
    if length > MAX_FRAME_PAYLOAD {
        return Err(WsError::Other(format!("frame too large: {} bytes", length)));
    }
    let mut payload = vec![0u8; length as usize];
    if length > 0 {
        reader.read_exact(&mut payload).await?;
    }
    if has_mask {
        xor_mask_in_place(&mut payload, &mask_key);
    }
    Ok((opcode, payload, fin))
}

// ---------------------------------------------------------------------------
// Frame builder (mask=true всегда для клиента)
// ---------------------------------------------------------------------------

pub fn build_frame(opcode: u8, data: &[u8], mask: bool) -> Vec<u8> {
    let length = data.len();
    let fb = 0x80 | opcode;

    let mut header_size = 2;
    if mask {
        header_size += 4;
    }
    if length >= 126 && length < 65536 {
        header_size += 2;
    } else if length >= 65536 {
        header_size += 8;
    }

    let total_size = header_size + length;
    let mut result = vec![0u8; total_size];

    let mut pos = 0;
    result[pos] = fb;
    pos += 1;

    let mut mask_key = [0u8; 4];
    if mask {
        rand::thread_rng().fill_bytes(&mut mask_key);
    }

    if length < 126 {
        let mut lb = length as u8;
        if mask {
            lb |= 0x80;
        }
        result[pos] = lb;
        pos += 1;
    } else if length < 65536 {
        let mut lb = 126u8;
        if mask {
            lb |= 0x80;
        }
        result[pos] = lb;
        pos += 1;
        BigEndian::write_u16(&mut result[pos..], length as u16);
        pos += 2;
    } else {
        let mut lb = 127u8;
        if mask {
            lb |= 0x80;
        }
        result[pos] = lb;
        pos += 1;
        BigEndian::write_u64(&mut result[pos..], length as u64);
        pos += 8;
    }

    if mask {
        result[pos..pos + 4].copy_from_slice(&mask_key);
        pos += 4;
        result[pos..pos + length].copy_from_slice(data);
        xor_mask_in_place(&mut result[pos..pos + length], &mask_key);
    } else {
        result[pos..pos + length].copy_from_slice(data);
    }
    result
}

// ---------------------------------------------------------------------------
// Connection helpers
// ---------------------------------------------------------------------------

fn set_sock_opts(stream: &TcpStream) {
    if TCP_NODELAY {
        let _ = stream.set_nodelay(true);
    }
    // Аналог Go: SetKeepAlive(true)+SetKeepAlivePeriod(30s) — детект мёртвых соединений на мобиле.
    let sock = socket2::SockRef::from(stream);
    let ka = socket2::TcpKeepalive::new().with_time(Duration::from_secs(30));
    let _ = sock.set_tcp_keepalive(&ka);
}

pub fn ws_connect_timeout(timeout: f64) -> Duration {
    if timeout <= 0.0 {
        Duration::from_secs(5)
    } else {
        Duration::from_secs_f64(timeout)
    }
}

pub fn ws_handshake_timeout(total: Duration) -> Duration {
    if total <= Duration::ZERO {
        Duration::from_secs(3)
    } else if total > Duration::from_secs(3) {
        Duration::from_secs(3)
    } else {
        total
    }
}

fn server_name(domain: &str) -> ServerName<'static> {
    ServerName::try_from(domain.to_string())
        .unwrap_or_else(|_| ServerName::IpAddress("127.0.0.1".parse::<IpAddr>().unwrap().into()))
}

// ws_connect_once_full — support TLS, plain HTTP, custom SNI (domain fronting)
pub async fn ws_connect_once_full(
    dial_addr: &str,
    domain: &str,
    path: &str,
    timeout: Duration,
    sni_override: Option<&str>,
    secure: bool,
) -> Result<RawWebSocket, WsError> {
    if dial_addr.is_empty() {
        return Err(WsError::Other("empty dial address".to_string()));
    }

    let port: u16 = if secure { 443 } else { 80 };
    let target_addr = format!("{}:{}", dial_addr, port);
    let socks5_port = UPSTREAM_SOCKS5_PORT.load(Ordering::Relaxed);

    let raw_conn = if socks5_port > 0 {
        match tokio::time::timeout(timeout, connect_socks5(socks5_port as u16, dial_addr, port)).await {
            Ok(Ok(c)) => c,
            Ok(Err(e)) => return Err(WsError::Io(e)),
            Err(_) => return Err(WsError::Timeout),
        }
    } else {
        match tokio::time::timeout(timeout, TcpStream::connect(&target_addr)).await {
            Ok(Ok(c)) => c,
            Ok(Err(e)) => return Err(WsError::Io(e)),
            Err(_) => return Err(WsError::Timeout),
        }
    };
    set_sock_opts(&raw_conn);

    // Через внешний SOCKS5 (ByeDPI) десинхронизацию делает он сам —
    // двойная обработка ClientHello ему только мешает.
    let dpi_enabled = DPI_BYPASS_ENABLED.load(Ordering::Relaxed) && socks5_port <= 0;
    let split_pos = DPI_SPLIT_POS.load(Ordering::Relaxed).max(0) as usize;
    let mode = DPI_MODE.load(Ordering::Relaxed);
    let desync_conn = DesyncStream::with_mode(raw_conn, dpi_enabled, mode, split_pos);

    let upstream_conn: UpstreamConn = if secure {
        let connector = TlsConnector::from(TLS_CONFIG.clone());
        let sni_name = sni_override.unwrap_or(domain);
        let sni = server_name(sni_name);

        let handshake_timeout = ws_handshake_timeout(timeout);
        let tls_conn =
            match tokio::time::timeout(handshake_timeout, connector.connect(sni, desync_conn)).await {
                Ok(Ok(c)) => c,
                Ok(Err(e)) => {
                    if e.kind() != std::io::ErrorKind::ConnectionReset {
                        ldebug!(" ws tls fail {} via {}: {}", domain, dial_addr, e);
                    }
                    return Err(WsError::Io(e));
                }
                Err(_) => {
                    ldebug!(" ws tls fail {} via {}: timeout", domain, dial_addr);
                    return Err(WsError::Timeout);
                }
            };
        UpstreamConn::Tls(tls_conn)
    } else {
        UpstreamConn::Plain(desync_conn)
    };

    let (read_half, mut write_half) = tokio::io::split(upstream_conn);

    // websocket key
    let mut ws_key_bytes = [0u8; 16];
    rand::thread_rng().fill_bytes(&mut ws_key_bytes);
    let ws_key = base64::engine::general_purpose::STANDARD.encode(ws_key_bytes);

    let req = format!(
        "GET {} HTTP/1.1\r\n\
         Host: {}\r\n\
         Upgrade: websocket\r\n\
         Connection: Upgrade\r\n\
         Sec-WebSocket-Key: {}\r\n\
         Sec-WebSocket-Version: 13\r\n\
         Sec-WebSocket-Protocol: binary\r\n\r\n",
        path, domain, ws_key
    );

    match tokio::time::timeout(timeout, async {
        write_half.write_all(req.as_bytes()).await?;
        write_half.flush().await
    })
    .await
    {
        Ok(Ok(())) => {}
        Ok(Err(e)) => return Err(WsError::Io(e)),
        Err(_) => return Err(WsError::Timeout),
    }

    let mut bufreader = BufReader::with_capacity(4096, read_half);

    // читаем заголовки строками
    let mut response_lines: Vec<String> = Vec::new();
    let read_result = tokio::time::timeout(timeout, async {
        loop {
            let line = read_line(&mut bufreader).await?;
            let line = line.trim_end_matches(['\r', '\n']).to_string();
            if line.is_empty() {
                break;
            }
            response_lines.push(line);
            if response_lines.len() > 100 {
                return Err(WsError::Other("too many HTTP headers".to_string()));
            }
        }
        Ok::<(), WsError>(())
    })
    .await;

    match read_result {
        Ok(Ok(())) => {}
        Ok(Err(e)) => return Err(e),
        Err(_) => return Err(WsError::Timeout),
    }

    if response_lines.is_empty() {
        return Err(WsError::Handshake(WsHandshakeError {
            status_code: 0,
            status_line: "empty response".to_string(),
            headers: HashMap::new(),
            location: String::new(),
        }));
    }

    let first_line = response_lines[0].clone();
    let parts: Vec<&str> = first_line.splitn(3, ' ').collect();
    let mut status_code = 0;
    if parts.len() >= 2 {
        status_code = parts[1].parse::<i32>().unwrap_or(0);
    }

    if status_code == 101 {
        return Ok(RawWebSocket {
            reader: tokio::sync::Mutex::new(bufreader),
            writer: tokio::sync::Mutex::new(write_half),
            frag: tokio::sync::Mutex::new(Vec::new()),
            closed: AtomicBool::new(false),
        });
    }

    let mut headers = HashMap::new();
    for hl in &response_lines[1..] {
        if let Some(idx) = hl.find(':') {
            headers.insert(
                hl[..idx].trim().to_lowercase(),
                hl[idx + 1..].trim().to_string(),
            );
        }
    }
    let location = headers.get("location").cloned().unwrap_or_default();
    Err(WsError::Handshake(WsHandshakeError {
        status_code,
        status_line: first_line,
        headers,
        location,
    }))
}

pub async fn ws_connect_once(
    dial_addr: &str,
    domain: &str,
    path: &str,
    timeout: Duration,
) -> Result<RawWebSocket, WsError> {
    ws_connect_once_full(dial_addr, domain, path, timeout, None, true).await
}

pub async fn ws_connect_once_with_sni(
    dial_addr: &str,
    domain: &str,
    path: &str,
    timeout: Duration,
    sni_override: Option<&str>,
) -> Result<RawWebSocket, WsError> {
    ws_connect_once_full(dial_addr, domain, path, timeout, sni_override, true).await
}

pub async fn ws_connect_once_http(
    dial_addr: &str,
    domain: &str,
    path: &str,
    timeout: Duration,
) -> Result<RawWebSocket, WsError> {
    ws_connect_once_full(dial_addr, domain, path, timeout, None, false).await
}

async fn read_line<R: AsyncReadExt + Unpin>(reader: &mut R) -> Result<String, WsError> {
    let mut buf = Vec::with_capacity(128);
    let mut byte = [0u8; 1];
    loop {
        let n = reader.read(&mut byte).await?;
        if n == 0 {
            return Err(WsError::Io(std::io::Error::new(
                std::io::ErrorKind::UnexpectedEof,
                "EOF",
            )));
        }
        buf.push(byte[0]);
        if byte[0] == b'\n' {
            break;
        }
        if buf.len() > 16384 {
            return Err(WsError::Other("header line too long".to_string()));
        }
    }
    Ok(String::from_utf8_lossy(&buf).to_string())
}

// wsConnect: пытается ip, при необходимости резолвит DoH
pub async fn ws_connect(
    ip: &str,
    domain: &str,
    path: &str,
    timeout: f64,
) -> Result<RawWebSocket, WsError> {
    ws_connect_with_sni(ip, domain, path, timeout, None).await
}

pub async fn ws_connect_with_sni(
    ip: &str,
    domain: &str,
    path: &str,
    timeout: f64,
    sni_override: Option<&str>,
) -> Result<RawWebSocket, WsError> {
    let path = if path.is_empty() { "/apiws" } else { path };
    let attempt_timeout = ws_connect_timeout(timeout);

    let primary_addr = if ip.trim().is_empty() {
        domain.to_string()
    } else {
        ip.trim().to_string()
    };

    match ws_connect_once_with_sni(&primary_addr, domain, path, attempt_timeout, sni_override).await {
        Ok(ws) => Ok(ws),
        Err(e) => {
            if primary_addr == domain && primary_addr.parse::<IpAddr>().is_err() {
                if let Some(resolved) = crate::cfproxy::resolve_doh(domain).await {
                    if !resolved.is_empty() && resolved != primary_addr {
                        return ws_connect_once_with_sni(&resolved, domain, path, attempt_timeout, sni_override).await;
                    }
                }
            }
            Err(e)
        }
    }
}

// ---------------------------------------------------------------------------
// Happy-eyeballs: прямое подключение стартует сразу, фронтинг-SNI —
// с задержкой и только если прямое ещё не успело. Раньше фронтинг шёл
// строго ДО прямого: 4 SNI x домены x до 6с = десятки секунд ожидания,
// прежде чем вообще пробовался обычный путь.
// ---------------------------------------------------------------------------

pub const FRONTING_START_DELAY: Duration = Duration::from_millis(700);
pub const FRONTING_STAGGER: Duration = Duration::from_millis(300);

/// Возвращает первый успешный WS и ошибки ПРЯМЫХ попыток (для детекта 302).
pub async fn race_ws_connect(
    ip: &str,
    domains: &[String],
    direct_timeout: f64,
) -> (Option<RawWebSocket>, Vec<WsError>) {
    let mut set: tokio::task::JoinSet<(bool, Result<RawWebSocket, WsError>)> =
        tokio::task::JoinSet::new();

    for d in domains {
        let ip = ip.to_string();
        let d = d.clone();
        set.spawn(async move {
            (true, ws_connect(&ip, &d, "/apiws", direct_timeout).await)
        });
    }

    if FRONTING_ENABLED.load(Ordering::Relaxed) {
        let snis = FRONTING_DOMAINS.read().clone();
        let front_timeout = direct_timeout.min(6.0);
        let mut idx: u32 = 0;
        for sni in snis {
            for d in domains {
                let delay = FRONTING_START_DELAY + FRONTING_STAGGER * idx;
                idx += 1;
                let ip = ip.to_string();
                let d = d.clone();
                let sni = sni.clone();
                set.spawn(async move {
                    tokio::time::sleep(delay).await;
                    let r = ws_connect_with_sni(&ip, &d, "/apiws", front_timeout, Some(&sni)).await;
                    if r.is_ok() {
                        crate::linfo!(" DC connect ok via fronting SNI {} -> {}", sni, ip);
                    } else if let Err(e) = &r {
                        ldebug!(" fronting SNI {} -> {} failed: {}", sni, ip, e.compact());
                    }
                    (false, r)
                });
            }
        }
    }

    let mut direct_errors = Vec::new();
    while let Some(joined) = set.join_next().await {
        match joined {
            Ok((_, Ok(ws))) => {
                set.abort_all();
                return (Some(ws), direct_errors);
            }
            Ok((true, Err(e))) => direct_errors.push(e),
            _ => {}
        }
    }
    (None, direct_errors)
}

// connectOneWS: перебор доменов (для пула)
pub async fn connect_one_ws(ip: &str, domains: &[String]) -> Option<RawWebSocket> {
    race_ws_connect(ip, domains, WS_POOL_CONNECT_TIMEOUT).await.0
}
