//! tun2socks for the VPN mode: tun2proxy (Rust) feeding the local SOCKS5
//! engine. Replaces hev-socks5-tunnel when the Rust engine is selected.

use std::os::raw::c_void;
use std::sync::atomic::{AtomicU64, Ordering};

use once_cell::sync::Lazy;
use parking_lot::Mutex;
use tokio_util::sync::CancellationToken;
use tun2proxy::{ArgDns, Args, ProxyParameters, TrafficStatus};

use crate::runner::RT;

static TX: AtomicU64 = AtomicU64::new(0);
static RX: AtomicU64 = AtomicU64::new(0);
static TOKEN: Lazy<Mutex<Option<CancellationToken>>> = Lazy::new(|| Mutex::new(None));

unsafe extern "C" fn on_traffic(s: *const TrafficStatus, _ctx: *mut c_void) {
    if let Some(s) = unsafe { s.as_ref() } {
        TX.store(s.tx, Ordering::Relaxed);
        RX.store(s.rx, Ordering::Relaxed);
    }
}

/// Starts the bridge in the background. The tun fd stays owned by Java.
pub fn start(fd: i32, port: u16, mtu: u16, ipv6: bool) -> i32 {
    let mut slot = TOKEN.lock();
    if slot.is_some() {
        log::warn!("tun2proxy already running");
        return -1;
    }
    let proxy = match ProxyParameters::try_from(format!("socks5://127.0.0.1:{}", port).as_str()) {
        Ok(p) => p,
        Err(e) => {
            log::error!("proxy url: {:?}", e);
            return -1;
        }
    };
    let mut args = Args::default();
    args.proxy(proxy)
        .tun_fd(Some(fd))
        .close_fd_on_drop(false)
        .dns(ArgDns::Direct)
        .ipv6_enabled(ipv6)
        .setup(false);
    TX.store(0, Ordering::Relaxed);
    RX.store(0, Ordering::Relaxed);
    unsafe { tun2proxy::tun2proxy_set_traffic_status_callback(1, Some(on_traffic), std::ptr::null_mut()) };
    let token = CancellationToken::new();
    *slot = Some(token.clone());
    RT.spawn(async move {
        match tun2proxy::general_run_async(args, mtu, false, token).await {
            Ok(n) => log::info!("tun2proxy finished, {} sessions", n),
            Err(e) => log::error!("tun2proxy failed: {}", e),
        }
        TOKEN.lock().take();
    });
    0
}

pub fn stop() -> i32 {
    match TOKEN.lock().take() {
        Some(t) => {
            t.cancel();
            0
        }
        None => -1,
    }
}

/// [tx packets, tx bytes, rx packets, rx bytes], like TProxyGetStats.
pub fn stats() -> [i64; 4] {
    [0, TX.load(Ordering::Relaxed) as i64, 0, RX.load(Ordering::Relaxed) as i64]
}
