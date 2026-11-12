package io.github.dovecoteescapee.byedpi.core

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager

/**
 * Rust engine (liblfengine): ByeDPI-compatible proxy with desync plus a
 * tun2proxy bridge. Selected in settings as "Движок: Rust"; the classic C
 * engine stays the default until the Rust one is proven on real networks.
 */
object RustEngine {
    const val PREF_KEY = "engine_impl"
    const val VALUE_RUST = "rust"
    const val VALUE_C = "c"

    private const val TAG = "RustEngine"

    /** False when the library is missing for this ABI (e.g. x86 emulators). */
    val available: Boolean by lazy {
        runCatching { System.loadLibrary("lfengine") }
            .onFailure { Log.w(TAG, "liblfengine unavailable, using the C engine", it) }
            .isSuccess
    }

    fun isSelected(context: Context): Boolean =
        available && PreferenceManager.getDefaultSharedPreferences(context)
            .getString(PREF_KEY, VALUE_C) == VALUE_RUST

    @JvmStatic external fun nativeStartProxy(id: Int, args: Array<String>): Int
    @JvmStatic external fun nativeStopProxy(id: Int): Int
    @JvmStatic external fun nativeStartTun(fd: Int, port: Int, mtu: Int, ipv6: Boolean): Int
    @JvmStatic external fun nativeStopTun(): Int
    @JvmStatic external fun nativeTunStats(): LongArray
}
