// HTTP/2 мультиплексирование медиа через CF-прокси (порт cf_h2.py из
// Flowseal/tg-ws-proxy 1.11.1). Каждый MTProto-пакет клиента уходит отдельным
// POST https://kwsN.<cf-domain>/api по одному общему H2-соединению на домен,
// тело ответа — пакет для клиента. Вместо пачки WS-подключений на загрузку
// медиа получается одно TCP/TLS-соединение.

use crate::cfproxy::{cfproxy_429_cooldown_remaining, resolve_doh, set_active_domain_and_save};
use crate::config::*;
use crate::crypto::TrackedStream;
use crate::ws::H2_TLS_CONFIG;
use crate::{ldebug, linfo, lwarn};
use bytes::Bytes;
use h2::client::SendRequest;
use once_cell::sync::Lazy;
use rand::RngCore;
use rustls_pki_types::ServerName;
use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::tcp::OwnedReadHalf;
use tokio::net::TcpStream;
use tokio::sync::{mpsc, OwnedSemaphorePermit, Semaphore};
use tokio_rustls::TlsConnector;
use tokio_util::sync::CancellationToken;

const MAX_PACKET: usize = 4 * 1024 * 1024;
const MAX_CHANNEL_REQUESTS: usize = 8;
const MAX_CHANNEL_BYTES: usize = 8 * 1024 * 1024;
const MAX_LANE_REQUESTS: usize = 64;
const STREAM_RECEIVE_WINDOW: u32 = 256 * 1024;
const CONNECTION_RECEIVE_WINDOW: u32 = 4 * 1024 * 1024;
const DOMAIN_COOLDOWN: Duration = Duration::from_secs(30);
const REQUEST_TIMEOUT: Duration = Duration::from_secs(65);
const SETUP_TIMEOUT: Duration = Duration::from_secs(8);
const CONNECT_TIMEOUT: Duration = Duration::from_secs(4);
const REPLAY_IDLE: Duration = Duration::from_secs(1);
const REPLAY_REQUEST: Duration = Duration::from_secs(3);
const REPLAY_CHECK: Duration = Duration::from_millis(250);
const REPLAY_MAX_AGE: Duration = Duration::from_secs(30);
const REPLAY_RETRY: Duration = Duration::from_secs(2);
const REPLAY_HISTORY_BYTES: usize = 64 * 1024;
const REPLAY_MAX_ATTEMPTS: u32 = 3;
const MAX_CHANNEL_REPLAYS: usize = 2;

const PROTO_ABRIDGED: u32 = 0xEFEFEFEF;
const PROTO_PADDED: u32 = 0xDDDDDDDD;

#[derive(Debug)]
enum H2Err {
    // Ошибка, которую нужно передать клиенту как MTProto transport error.
    Transport(i32, String),
    Other(String),
}

impl std::fmt::Display for H2Err {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            H2Err::Transport(c, s) => write!(f, "transport error {} ({})", c, s),
            H2Err::Other(s) => f.write_str(s),
        }
    }
}

fn other<E: std::fmt::Display>(e: E) -> H2Err {
    H2Err::Other(e.to_string())
}

// ---------------------------------------------------------------------------
// Lane: одно H2-соединение к kwsN.<domain>
// ---------------------------------------------------------------------------

static NEXT_LANE: AtomicU64 = AtomicU64::new(0);

pub struct Lane {
    host: String,
    id: u64,
    send: SendRequest<Bytes>,
    dead: Arc<AtomicBool>,
    failed_until: parking_lot::Mutex<Option<Instant>>,
    slots: Arc<Semaphore>,
}

impl Lane {
    fn usable(&self) -> bool {
        if self.dead.load(Ordering::Relaxed) {
            return false;
        }
        match *self.failed_until.lock() {
            Some(t) => Instant::now() >= t,
            None => true,
        }
    }

    fn mark_failed(&self) {
        *self.failed_until.lock() = Some(Instant::now() + DOMAIN_COOLDOWN);
    }

    async fn connect(host: &str) -> Result<Lane, H2Err> {
        let tcp = match tokio::time::timeout(CONNECT_TIMEOUT, TcpStream::connect((host, 443))).await
        {
            Ok(Ok(s)) => s,
            _ => {
                let ip = resolve_doh(host)
                    .await
                    .ok_or_else(|| H2Err::Other("DNS: нет адреса".into()))?;
                match tokio::time::timeout(CONNECT_TIMEOUT, TcpStream::connect((ip.as_str(), 443)))
                    .await
                {
                    Ok(Ok(s)) => s,
                    Ok(Err(e)) => return Err(other(e)),
                    Err(_) => return Err(H2Err::Other("connect timeout".into())),
                }
            }
        };
        let _ = tcp.set_nodelay(true);
        let sni = ServerName::try_from(host.to_string()).map_err(other)?;
        let tls = match tokio::time::timeout(
            CONNECT_TIMEOUT,
            TlsConnector::from(H2_TLS_CONFIG.clone()).connect(sni, tcp),
        )
        .await
        {
            Ok(Ok(s)) => s,
            Ok(Err(e)) => return Err(other(e)),
            Err(_) => return Err(H2Err::Other("TLS timeout".into())),
        };
        if tls.get_ref().1.alpn_protocol() != Some(b"h2") {
            return Err(H2Err::Other("CF не согласовал h2".into()));
        }
        let (send, conn) = h2::client::Builder::new()
            .enable_push(false)
            .initial_window_size(STREAM_RECEIVE_WINDOW)
            .initial_connection_window_size(CONNECTION_RECEIVE_WINDOW)
            .handshake::<_, Bytes>(tls)
            .await
            .map_err(other)?;
        let dead = Arc::new(AtomicBool::new(false));
        let id = NEXT_LANE.fetch_add(1, Ordering::Relaxed) + 1;
        {
            let dead = dead.clone();
            let host = host.to_string();
            tokio::spawn(async move {
                if let Err(e) = conn.await {
                    ldebug!(" H2 lane={} {} закрыт: {}", id, host, e);
                }
                dead.store(true, Ordering::Relaxed);
            });
        }
        let send = send.ready().await.map_err(other)?;
        Ok(Lane {
            host: host.to_string(),
            id,
            send,
            dead,
            failed_until: parking_lot::Mutex::new(None),
            slots: Arc::new(Semaphore::new(MAX_LANE_REQUESTS)),
        })
    }

    fn request(&self, method: &str) -> http::Request<()> {
        http::Request::builder()
            .method(method)
            .uri(format!("https://{}/api", self.host))
            .header("content-type", "application/octet-stream")
            .header("accept-encoding", "identity")
            .header("accept", "*/*")
            .body(())
            .expect("valid request")
    }

    async fn preflight(&self) -> Result<(), H2Err> {
        let started = Instant::now();
        let mut send = self.send.clone().ready().await.map_err(other)?;
        let (resp, _) = send
            .send_request(self.request("HEAD"), true)
            .map_err(other)?;
        let resp = resp.await.map_err(other)?;
        let status = resp.status().as_u16();
        if !((200..300).contains(&status) || status == 405 || status == 501) {
            return Err(H2Err::Other(format!(
                "/api preflight HTTP {} cf_ray={}",
                status,
                header(resp.headers(), "cf-ray")
            )));
        }
        ldebug!(
            " H2 lane={} готов {} status={} ms={}",
            self.id,
            self.host,
            status,
            started.elapsed().as_millis()
        );
        Ok(())
    }

    async fn post(&self, body: Bytes, receiving: &AtomicBool) -> Result<Bytes, H2Err> {
        let res = match tokio::time::timeout(REQUEST_TIMEOUT, self.post_inner(body, receiving)).await
        {
            Ok(r) => r,
            Err(_) => Err(H2Err::Other("request timeout".into())),
        };
        if let Err(H2Err::Other(_)) = &res {
            self.mark_failed();
        }
        res
    }

    async fn post_inner(&self, mut body: Bytes, receiving: &AtomicBool) -> Result<Bytes, H2Err> {
        let mut send = self.send.clone().ready().await.map_err(other)?;
        let (resp, mut stream) = send
            .send_request(self.request("POST"), false)
            .map_err(other)?;
        while !body.is_empty() {
            stream.reserve_capacity(body.len());
            let cap = match std::future::poll_fn(|cx| stream.poll_capacity(cx)).await {
                Some(Ok(c)) => c,
                Some(Err(e)) => return Err(other(e)),
                None => return Err(H2Err::Other("H2 stream closed".into())),
            };
            if cap == 0 {
                continue;
            }
            let chunk = body.split_to(cap.min(body.len()));
            stream.send_data(chunk, body.is_empty()).map_err(other)?;
        }
        let resp = resp.await.map_err(other)?;
        let status = resp.status().as_u16();
        let ray = header(resp.headers(), "cf-ray");
        if matches!(status, 403 | 404 | 429 | 444) {
            return Err(H2Err::Transport(
                -(status as i32),
                format!("HTTP {} cf_ray={}", status, ray),
            ));
        }
        if status != 200 {
            return Err(H2Err::Other(format!("/api HTTP {} cf_ray={}", status, ray)));
        }
        if header(resp.headers(), "content-type")
            .to_ascii_lowercase()
            .contains("text/html")
        {
            return Err(H2Err::Other("/api вернул HTML вместо MTProto".into()));
        }
        let declared = resp
            .headers()
            .get("content-length")
            .and_then(|v| v.to_str().ok())
            .and_then(|v| v.parse::<usize>().ok());
        if declared.map_or(false, |l| l > MAX_PACKET) {
            return Err(H2Err::Other("Content-Length больше пакета".into()));
        }
        receiving.store(true, Ordering::Relaxed);
        let mut rb = resp.into_body();
        let mut content = Vec::with_capacity(declared.unwrap_or(0));
        while let Some(chunk) = rb.data().await {
            let chunk = chunk.map_err(other)?;
            let _ = rb.flow_control().release_capacity(chunk.len());
            if content.len() + chunk.len() > MAX_PACKET {
                return Err(H2Err::Other("ответ больше пакета".into()));
            }
            content.extend_from_slice(&chunk);
        }
        if let Some(l) = declared {
            if l != content.len() {
                return Err(H2Err::Other("неполный H2 ответ".into()));
            }
        }
        if content.len() % 4 != 0 {
            return Err(H2Err::Other("ответ не выровнен как MTProto пакет".into()));
        }
        if content.len() == 4 {
            let code = i32::from_le_bytes([content[0], content[1], content[2], content[3]]);
            if code < 0 {
                return Err(H2Err::Transport(code, "HTTP 200 payload".into()));
            }
        }
        Ok(Bytes::from(content))
    }
}

fn header(h: &http::HeaderMap, name: &str) -> String {
    h.get(name)
        .and_then(|v| v.to_str().ok())
        .unwrap_or("-")
        .chars()
        .take(64)
        .collect()
}

// ---------------------------------------------------------------------------
// Pool
// ---------------------------------------------------------------------------

type LaneSlot = Arc<tokio::sync::Mutex<Option<Arc<Lane>>>>;

static LANES: Lazy<parking_lot::Mutex<HashMap<String, LaneSlot>>> =
    Lazy::new(|| parking_lot::Mutex::new(HashMap::new()));
static HOST_FAILED: Lazy<parking_lot::Mutex<HashMap<String, Instant>>> =
    Lazy::new(|| parking_lot::Mutex::new(HashMap::new()));
static SETUP_RETRY: Lazy<parking_lot::Mutex<HashMap<i32, Instant>>> =
    Lazy::new(|| parking_lot::Mutex::new(HashMap::new()));
static NEXT_CHANNEL: AtomicU64 = AtomicU64::new(0);

pub fn reset() {
    LANES.lock().clear();
    HOST_FAILED.lock().clear();
    SETUP_RETRY.lock().clear();
}

pub async fn open(dc: i32) -> Option<Arc<Lane>> {
    if !DC_DEFAULT_IPS.contains_key(&dc) {
        return None;
    }
    if let Some(t) = SETUP_RETRY.lock().get(&dc) {
        if Instant::now() < *t {
            return None;
        }
    }
    match tokio::time::timeout(SETUP_TIMEOUT, open_inner(dc)).await {
        Ok(r) => r,
        Err(_) => {
            SETUP_RETRY.lock().insert(dc, Instant::now() + DOMAIN_COOLDOWN);
            lwarn!(" H2 DC{}: настройка не успела, {}с через WS", dc, DOMAIN_COOLDOWN.as_secs());
            None
        }
    }
}

async fn open_inner(dc: i32) -> Option<Arc<Lane>> {
    let (active, domains) = {
        let cfg = CFPROXY.read();
        (cfg.active.clone(), cfg.domains.clone())
    };
    let mut ordered = Vec::new();
    if !active.is_empty() {
        ordered.push(active.clone());
    }
    ordered.extend(domains.into_iter().filter(|d| *d != active));

    for base in ordered {
        if cfproxy_429_cooldown_remaining(&base) > Duration::ZERO {
            continue;
        }
        let host = format!("kws{}.{}", dc, base);
        if HOST_FAILED.lock().get(&host).map_or(false, |t| Instant::now() < *t) {
            continue;
        }
        let slot = LANES.lock().entry(host.clone()).or_default().clone();
        let mut guard = slot.lock().await;
        if let Some(l) = guard.as_ref() {
            if l.usable() {
                return Some(l.clone());
            }
            if !l.dead.load(Ordering::Relaxed) {
                continue; // соединение живо, но домен на кулдауне после ошибки
            }
        }
        let lane = match Lane::connect(&host).await {
            Ok(l) => l,
            Err(e) => {
                HOST_FAILED.lock().insert(host.clone(), Instant::now() + DOMAIN_COOLDOWN);
                ldebug!(" H2 {} недоступен: {}", host, e);
                *guard = None;
                continue;
            }
        };
        if let Err(e) = lane.preflight().await {
            HOST_FAILED.lock().insert(host.clone(), Instant::now() + DOMAIN_COOLDOWN);
            lwarn!(" H2 lane={} preflight {} не прошёл: {}", lane.id, host, e);
            *guard = None;
            continue;
        }
        let lane = Arc::new(lane);
        *guard = Some(lane.clone());
        set_active_domain_and_save(&base);
        return Some(lane);
    }
    ldebug!(" H2 DC{} недоступен, используем WS", dc);
    None
}

// ---------------------------------------------------------------------------
// MTProto framing
// ---------------------------------------------------------------------------

async fn read_plain(r: &mut OwnedReadHalf, dec: &mut TrackedStream, buf: &mut [u8]) -> std::io::Result<()> {
    r.read_exact(buf).await?;
    dec.xor(buf);
    Ok(())
}

fn invalid(msg: String) -> std::io::Error {
    std::io::Error::new(std::io::ErrorKind::InvalidData, msg)
}

async fn read_packet(r: &mut OwnedReadHalf, dec: &mut TrackedStream, proto: u32) -> std::io::Result<Vec<u8>> {
    let length = if proto == PROTO_ABRIDGED {
        let mut b = [0u8; 1];
        read_plain(r, dec, &mut b).await?;
        let mut words = (b[0] & 0x7f) as usize;
        if words == 0x7f {
            let mut b3 = [0u8; 3];
            read_plain(r, dec, &mut b3).await?;
            words = b3[0] as usize | (b3[1] as usize) << 8 | (b3[2] as usize) << 16;
        }
        words * 4
    } else {
        let mut b = [0u8; 4];
        read_plain(r, dec, &mut b).await?;
        (u32::from_le_bytes(b) & 0x7fff_ffff) as usize
    };
    let max = MAX_PACKET + if proto == PROTO_PADDED { 15 } else { 0 };
    if !(24..=max).contains(&length) {
        return Err(invalid(format!("длина пакета вне диапазона: {}", length)));
    }
    let mut body = vec![0u8; length];
    read_plain(r, dec, &mut body).await?;
    if proto == PROTO_PADDED {
        let packet_length = if body[..8] == [0u8; 8] {
            20 + u32::from_le_bytes([body[16], body[17], body[18], body[19]]) as usize
        } else {
            24 + ((length - 24) / 16) * 16
        };
        if packet_length < 24 || packet_length > length || length - packet_length > 15 {
            return Err(invalid("неверный padded пакет".into()));
        }
        body.truncate(packet_length);
    }
    if body.len() % 4 != 0 || body.len() > MAX_PACKET {
        return Err(invalid("пакет не выровнен".into()));
    }
    Ok(body)
}

fn encode_reply(body: &[u8], proto: u32) -> Vec<u8> {
    let mut out = Vec::with_capacity(body.len() + 8);
    if proto == PROTO_ABRIDGED {
        let words = body.len() / 4;
        if words < 0x7f {
            out.push(words as u8);
        } else {
            out.push(0x7f);
            out.extend_from_slice(&(words as u32).to_le_bytes()[..3]);
        }
        out.extend_from_slice(body);
        return out;
    }
    let mut padding = Vec::new();
    if proto == PROTO_PADDED {
        let mut n = [0u8; 1];
        rand::thread_rng().fill_bytes(&mut n);
        padding = vec![0u8; (n[0] % 4) as usize];
        rand::thread_rng().fill_bytes(&mut padding);
    }
    out.extend_from_slice(&((body.len() + padding.len()) as u32).to_le_bytes());
    out.extend_from_slice(body);
    out.extend_from_slice(&padding);
    out
}

// ---------------------------------------------------------------------------
// Channel: одно клиентское соединение поверх lane
// ---------------------------------------------------------------------------

enum Reply {
    Data(Bytes),
    Fail(H2Err),
}

struct ReqInfo {
    started: Instant,
    receiving: Arc<AtomicBool>,
    replay: bool,
    abort: Option<tokio::task::AbortHandle>,
}

struct Packet {
    body: Bytes,
    original: u64,
    sent_at: Instant,
    attempts: u32,
    last_attempt: Instant,
    replay_req: Option<u64>,
}

struct ChState {
    reqs: HashMap<u64, ReqInfo>,
    next_id: u64,
    last_progress: Instant,
    key: [u8; 8],
    packet: Option<Packet>,
}

struct Channel {
    id: u64,
    lane: Arc<Lane>,
    st: parking_lot::Mutex<ChState>,
    slots: Arc<Semaphore>,
    tx: mpsc::UnboundedSender<Reply>,
    reply_bytes: AtomicUsize,
}

impl Channel {
    // Регистрирует запрос и запускает его. Permits держатся до конца запроса.
    fn spawn_request(
        self: &Arc<Self>,
        body: Bytes,
        replay: bool,
        permits: (OwnedSemaphorePermit, OwnedSemaphorePermit),
    ) -> u64 {
        let receiving = Arc::new(AtomicBool::new(false));
        let id = {
            let mut st = self.st.lock();
            st.next_id += 1;
            let id = st.next_id;
            st.reqs.insert(
                id,
                ReqInfo {
                    started: Instant::now(),
                    receiving: receiving.clone(),
                    replay,
                    abort: None,
                },
            );
            id
        };
        let ch = self.clone();
        let task = tokio::spawn(async move {
            let _permits = permits;
            let res = ch.lane.post(body, &receiving).await;
            {
                let mut st = ch.st.lock();
                st.reqs.remove(&id);
                st.last_progress = Instant::now();
                if let Some(p) = st.packet.as_mut() {
                    if p.replay_req == Some(id) {
                        p.replay_req = None;
                    }
                }
            }
            match res {
                Ok(reply) if !reply.is_empty() => {
                    let total = ch.reply_bytes.fetch_add(reply.len(), Ordering::Relaxed) + reply.len();
                    if total > MAX_CHANNEL_BYTES {
                        let _ = ch.tx.send(Reply::Fail(H2Err::Other("переполнен буфер ответов".into())));
                    } else {
                        let _ = ch.tx.send(Reply::Data(reply));
                    }
                }
                Ok(_) => {}
                Err(e) => {
                    lwarn!(
                        " H2 lane={} channel={} {}: запрос не удался: {}",
                        ch.lane.id,
                        ch.id,
                        ch.lane.host,
                        e
                    );
                    let _ = ch.tx.send(Reply::Fail(e));
                }
            }
        });
        if let Some(r) = self.st.lock().reqs.get_mut(&id) {
            r.abort = Some(task.abort_handle());
        }
        id
    }

    fn remember(&self, body: &Bytes, id: u64) {
        let mut st = self.st.lock();
        let key: [u8; 8] = body[..8].try_into().unwrap();
        if key == [0u8; 8] {
            return;
        }
        if st.key != key {
            st.key = key;
            st.packet = None;
        }
        if body.len() > REPLAY_HISTORY_BYTES {
            return;
        }
        if st.packet.as_ref().map_or(false, |p| p.body == *body) {
            return;
        }
        let now = Instant::now();
        st.packet = Some(Packet {
            body: body.clone(),
            original: id,
            sent_at: now,
            attempts: 0,
            last_attempt: now,
            replay_req: None,
        });
    }

    // Повтор последнего пакета: опрос сервера, когда канал простаивает, и
    // восстановление, когда исходный запрос завис без заголовков ответа.
    fn try_replay(self: &Arc<Self>) {
        if self.reply_bytes.load(Ordering::Relaxed) > 0 {
            return;
        }
        let now = Instant::now();
        let body = {
            let st = self.st.lock();
            let p = match st.packet.as_ref() {
                Some(p) => p,
                None => return,
            };
            if now - p.sent_at > REPLAY_MAX_AGE
                || p.attempts >= REPLAY_MAX_ATTEMPTS
                || p.replay_req.is_some()
            {
                return;
            }
            if st.reqs.values().any(|r| r.receiving.load(Ordering::Relaxed)) {
                return;
            }
            if p.attempts > 0 && now - p.last_attempt < REPLAY_RETRY {
                return;
            }
            let overdue = st
                .reqs
                .get(&p.original)
                .map_or(false, |r| now - r.started >= REPLAY_REQUEST);
            if !overdue {
                if !st.reqs.is_empty() {
                    return;
                }
                if p.attempts > 0 && now - st.last_progress < REPLAY_IDLE {
                    return;
                }
            }
            if st.reqs.values().filter(|r| r.replay).count() >= MAX_CHANNEL_REPLAYS {
                return;
            }
            p.body.clone()
        };
        let a = match self.slots.clone().try_acquire_owned() {
            Ok(p) => p,
            Err(_) => return,
        };
        let b = match self.lane.slots.clone().try_acquire_owned() {
            Ok(p) => p,
            Err(_) => return,
        };
        let id = self.spawn_request(body, true, (a, b));
        let mut st = self.st.lock();
        // Запрос мог уже завершиться — тогда не помечаем его как висящий повтор.
        let still_running = st.reqs.contains_key(&id);
        if let Some(p) = st.packet.as_mut() {
            p.attempts += 1;
            p.last_attempt = now;
            if still_running {
                p.replay_req = Some(id);
            }
        }
    }
}

pub async fn bridge_h2(
    conn: TcpStream,
    lane: Arc<Lane>,
    dc: i32,
    proto: u32,
    mut clt_dec: TrackedStream,
    mut clt_enc: TrackedStream,
    cancel_token: CancellationToken,
) {
    let (tx, mut rx) = mpsc::unbounded_channel();
    let ch = Arc::new(Channel {
        id: NEXT_CHANNEL.fetch_add(1, Ordering::Relaxed) + 1,
        lane: lane.clone(),
        st: parking_lot::Mutex::new(ChState {
            reqs: HashMap::new(),
            next_id: 0,
            last_progress: Instant::now(),
            key: [0u8; 8],
            packet: None,
        }),
        slots: Arc::new(Semaphore::new(MAX_CHANNEL_REQUESTS)),
        tx,
        reply_bytes: AtomicUsize::new(0),
    });
    linfo!(" DC{}m -> H2 lane={} channel={} {}", dc, lane.id, ch.id, lane.host);

    let session = cancel_token.child_token();
    let (mut c_read, mut c_write) = conn.into_split();

    let up_ch = ch.clone();
    let up_sess = session.clone();
    let up = tokio::spawn(async move {
        loop {
            let body = tokio::select! {
                _ = up_sess.cancelled() => break,
                r = read_packet(&mut c_read, &mut clt_dec, proto) => match r {
                    Ok(b) => Bytes::from(b),
                    Err(e) => {
                        if e.kind() != std::io::ErrorKind::UnexpectedEof {
                            ldebug!(" H2 channel={}: {}", up_ch.id, e);
                        }
                        break;
                    }
                },
            };
            let permits = tokio::select! {
                _ = up_sess.cancelled() => break,
                p = tokio::time::timeout(REQUEST_TIMEOUT, async {
                    let a = up_ch.slots.clone().acquire_owned().await.ok()?;
                    let b = up_ch.lane.slots.clone().acquire_owned().await.ok()?;
                    Some((a, b))
                }) => match p {
                    Ok(Some(p)) => p,
                    _ => break,
                },
            };
            STATS.bytes_up.fetch_add(body.len() as i64, Ordering::Relaxed);
            let id = up_ch.spawn_request(body.clone(), false, permits);
            up_ch.remember(&body, id);
        }
        up_sess.cancel();
    });

    let down_ch = ch.clone();
    let down_sess = session.clone();
    let down = tokio::spawn(async move {
        loop {
            let reply = tokio::select! {
                _ = down_sess.cancelled() => break,
                r = rx.recv() => match r { Some(r) => r, None => break },
            };
            let (body, last) = match reply {
                Reply::Data(b) => {
                    down_ch.reply_bytes.fetch_sub(b.len(), Ordering::Relaxed);
                    (b, false)
                }
                Reply::Fail(H2Err::Transport(code, _)) => {
                    (Bytes::copy_from_slice(&code.to_le_bytes()), true)
                }
                Reply::Fail(H2Err::Other(_)) => break,
            };
            let mut data = encode_reply(&body, proto);
            clt_enc.xor(&mut data);
            if c_write.write_all(&data).await.is_err() {
                break;
            }
            STATS.bytes_down.fetch_add(body.len() as i64, Ordering::Relaxed);
            down_ch.st.lock().last_progress = Instant::now();
            if last {
                break;
            }
        }
        down_sess.cancel();
    });

    let rec_ch = ch.clone();
    let rec_sess = session.clone();
    let recover = tokio::spawn(async move {
        let mut tick = tokio::time::interval(REPLAY_CHECK);
        loop {
            tokio::select! {
                _ = rec_sess.cancelled() => break,
                _ = tick.tick() => rec_ch.try_replay(),
            }
        }
    });

    let _ = up.await;
    let _ = down.await;
    session.cancel();
    let _ = recover.await;
    let reqs: Vec<_> = ch.st.lock().reqs.drain().collect();
    for (_, r) in reqs {
        if let Some(a) = r.abort {
            a.abort();
        }
    }
    ldebug!(" H2 channel={} закрыт", ch.id);
}
