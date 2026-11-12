//! LimeFlow DPI bypass engine in Rust: ByeDPI-compatible SOCKS proxy with
//! desync, plus a tun2socks bridge (tun2proxy) for the VPN mode.

pub mod packets;
pub mod params;

#[cfg(any(target_os = "linux", target_os = "android"))]
pub mod desync;
#[cfg(any(target_os = "linux", target_os = "android"))]
pub mod proxy;
#[cfg(target_os = "android")]
pub mod tun;
#[cfg(target_os = "android")]
mod jni_api;

#[cfg(any(target_os = "linux", target_os = "android"))]
pub mod runner {
    use std::collections::HashMap;
    use std::sync::Arc;

    use once_cell::sync::Lazy;
    use parking_lot::Mutex;
    use tokio_util::sync::CancellationToken;

    use crate::params::{parse_args, ParseOutcome};
    use crate::proxy::Engine;

    pub static RT: Lazy<tokio::runtime::Runtime> = Lazy::new(|| {
        tokio::runtime::Builder::new_multi_thread()
            .worker_threads(4)
            .thread_name("lfengine")
            .enable_all()
            .build()
            .expect("tokio runtime")
    });

    static RUNNING: Lazy<Mutex<HashMap<i32, CancellationToken>>> = Lazy::new(|| Mutex::new(HashMap::new()));

    /// Runs the proxy until [`stop`] is called for the same id. Returns the
    /// exit code like ByeDPI's main(): 0 on a clean stop.
    pub fn run_blocking(id: i32, argv: Vec<String>) -> i32 {
        let params = match parse_args(&argv) {
            Ok(ParseOutcome::Run(p)) => p,
            Ok(ParseOutcome::Info(s)) => {
                log::info!("{}", s);
                return 0;
            }
            Err(e) => {
                log::error!("bad arguments: {}", e);
                return -1;
            }
        };
        let token = CancellationToken::new();
        {
            let mut r = RUNNING.lock();
            if r.contains_key(&id) {
                log::error!("engine {} already running", id);
                return -1;
            }
            r.insert(id, token.clone());
        }
        let eng: Arc<Engine> = Engine::new(params);
        let res = RT.block_on(eng.run(token));
        RUNNING.lock().remove(&id);
        match res {
            Ok(()) => 0,
            Err(e) => {
                log::error!("proxy failed: {}", e);
                -1
            }
        }
    }

    pub fn stop(id: i32) -> i32 {
        match RUNNING.lock().get(&id) {
            Some(t) => {
                t.cancel();
                0
            }
            None => -1,
        }
    }
}
