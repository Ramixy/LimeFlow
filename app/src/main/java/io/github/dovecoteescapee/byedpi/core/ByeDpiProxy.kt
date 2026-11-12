package io.github.dovecoteescapee.byedpi.core

import java.util.concurrent.atomic.AtomicInteger

/**
 * DPI bypass proxy. [useRust] picks the Rust engine (liblfengine) instead of
 * the classic C ByeDPI fork; both take the same command line.
 */
class ByeDpiProxy(private val useRust: Boolean = false) {
    companion object {
        init {
            System.loadLibrary("byedpi")
        }

        private val nextId = AtomicInteger(1)
    }

    private val rustId = nextId.getAndIncrement()

    fun startProxy(preferences: ByeDpiProxyPreferences): Int {
        val args = prepareArgs(preferences)
        return if (useRust) RustEngine.nativeStartProxy(rustId, args) else jniStartProxy(args)
    }

    fun stopProxy(): Int =
        if (useRust) RustEngine.nativeStopProxy(rustId) else jniStopProxy()

    /** Last-resort stop; the Rust engine has no listening fd to close by hand. */
    fun forceClose(): Int =
        if (useRust) RustEngine.nativeStopProxy(rustId) else jniForceClose()

    private fun prepareArgs(preferences: ByeDpiProxyPreferences): Array<String> =
        when (preferences) {
            is ByeDpiProxyCmdPreferences -> preferences.args
            is ByeDpiProxyUIPreferences -> ByeDpiProxyPreferences.withBlockQuic(
                arrayOf(
                    "ciadpi",
                    "--ip", preferences.ip,
                    "--port", preferences.port.toString(),
                    "--split", "1+s",
                    "--disorder", "3+s",
                    "--udp-fake", "1",
                ),
                preferences.blockQuic,
            )
        }

    private external fun jniStartProxy(args: Array<String>): Int
    private external fun jniStopProxy(): Int
    private external fun jniForceClose(): Int
}
