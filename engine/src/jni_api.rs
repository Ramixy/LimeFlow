//! JNI entry points for `io.github.dovecoteescapee.byedpi.core.RustEngine`.

use jni::objects::{JClass, JObjectArray, JString};
use jni::sys::{jboolean, jint, jlongArray};
use jni::JNIEnv;

fn init_log() {
    android_logger::init_once(
        android_logger::Config::default()
            .with_tag("lfengine")
            .with_max_level(log::LevelFilter::Info),
    );
}

#[no_mangle]
pub extern "system" fn Java_io_github_dovecoteescapee_byedpi_core_RustEngine_nativeStartProxy(
    mut env: JNIEnv,
    _cls: JClass,
    id: jint,
    args: JObjectArray,
) -> jint {
    init_log();
    let n = match env.get_array_length(&args) {
        Ok(n) => n,
        Err(_) => return -1,
    };
    let mut argv = Vec::with_capacity(n as usize);
    for i in 0..n {
        let o = match env.get_object_array_element(&args, i) {
            Ok(o) => o,
            Err(_) => return -1,
        };
        if o.is_null() {
            argv.push(String::new());
            continue;
        }
        let s = JString::from(o);
        let v: String = match env.get_string(&s) {
            Ok(v) => v.into(),
            Err(_) => return -1,
        };
        let _ = env.delete_local_ref(s);
        argv.push(v);
    }
    if argv.iter().any(|a| a == "-x" || a == "--debug") {
        log::set_max_level(log::LevelFilter::Debug);
    }
    crate::runner::run_blocking(id, argv)
}

#[no_mangle]
pub extern "system" fn Java_io_github_dovecoteescapee_byedpi_core_RustEngine_nativeStopProxy(
    _env: JNIEnv,
    _cls: JClass,
    id: jint,
) -> jint {
    crate::runner::stop(id)
}

#[no_mangle]
pub extern "system" fn Java_io_github_dovecoteescapee_byedpi_core_RustEngine_nativeStartTun(
    _env: JNIEnv,
    _cls: JClass,
    fd: jint,
    port: jint,
    mtu: jint,
    ipv6: jboolean,
) -> jint {
    init_log();
    crate::tun::start(fd, port as u16, mtu as u16, ipv6 != 0)
}

#[no_mangle]
pub extern "system" fn Java_io_github_dovecoteescapee_byedpi_core_RustEngine_nativeStopTun(
    _env: JNIEnv,
    _cls: JClass,
) -> jint {
    crate::tun::stop()
}

#[no_mangle]
pub extern "system" fn Java_io_github_dovecoteescapee_byedpi_core_RustEngine_nativeTunStats(
    env: JNIEnv,
    _cls: JClass,
) -> jlongArray {
    let s = crate::tun::stats();
    match env.new_long_array(4) {
        Ok(arr) => {
            let _ = env.set_long_array_region(&arr, 0, &s);
            arr.into_raw()
        }
        Err(_) => std::ptr::null_mut(),
    }
}
