package io.github.dovecoteescapee.byedpi.core

class ByeDpiProxy {
    companion object {
        init {
            System.loadLibrary("byedpi")
        }
    }

    fun startProxy(preferences: ByeDpiProxyPreferences): Int {
        val args = prepareArgs(preferences)
        return jniStartProxy(args)
    }

    fun stopProxy(): Int {
        return jniStopProxy()
    }

    private fun prepareArgs(preferences: ByeDpiProxyPreferences): Array<String> =
        when (preferences) {
            is ByeDpiProxyCmdPreferences -> preferences.args
            is ByeDpiProxyUIPreferences -> arrayOf(
                "ciadpi",
                "--ip", preferences.ip,
                "--port", preferences.port.toString(),
                "--split", "1+s",
                "--disorder", "3+s",
                "--udp-fake", "1",
            )
        }

    private external fun jniStartProxy(args: Array<String>): Int
    private external fun jniStopProxy(): Int
    external fun jniForceClose(): Int
}
