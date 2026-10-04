package io.github.dovecoteescapee.byedpi.utility

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import io.github.dovecoteescapee.byedpi.data.AppStatus
import io.github.dovecoteescapee.byedpi.services.appStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/**
 * One-shot connectivity probe. While LimeFlow is running the request goes
 * through the engine's SOCKS port: the app's own traffic is excluded from the
 * VPN tunnel, so a plain request would measure the unprotected path instead
 * of the bypass.
 */
object ConnectionTester {
    private const val CHECK_URL = "https://www.gstatic.com/generate_204"
    private const val TIMEOUT_MS = 6_000

    sealed class Result {
        data class Reachable(val latencyMs: Long) : Result()
        data class CaptivePortal(val latencyMs: Long) : Result()
        object Unreachable : Result()
        object NoNetwork : Result()
    }

    fun hasNetwork(context: Context): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = manager.activeNetwork ?: return false
        val caps = manager.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    suspend fun test(context: Context): Result = withContext(Dispatchers.IO) {
        if (!hasNetwork(context)) return@withContext Result.NoNetwork

        val throughEngine = appStatus.first == AppStatus.Running
        runCatching {
            val connection = openConnection(context, throughEngine)
            val startedAt = SystemClock.elapsedRealtime()
            val code = connection.responseCode
            val latency = SystemClock.elapsedRealtime() - startedAt
            runCatching { connection.inputStream?.close() }
            connection.disconnect()
            when {
                code == 204 -> Result.Reachable(latency)
                code in 200..299 -> Result.CaptivePortal(latency)
                else -> Result.Unreachable
            }
        }.getOrElse { Result.Unreachable }
    }

    private fun openConnection(context: Context, throughEngine: Boolean): HttpURLConnection {
        val url = URL(CHECK_URL)
        val connection = if (throughEngine) {
            val port = resolveSocksPort(context)
            url.openConnection(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)))
        } else {
            url.openConnection()
        } as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("User-Agent", "LimeFlow")
        return connection
    }

    /*
     * Command-line mode carries the port inside the strategy arguments; the
     * visual mode keeps it in a dedicated preference. Port flags are plain
     * tokens even in arguments that embed quoted host lists, so a naive
     * whitespace split is enough.
     */
    private fun resolveSocksPort(context: Context): Int {
        val preferences = context.getPreferences()
        val fallback = preferences.getStringNotNull("byedpi_proxy_port", "1080").toIntOrNull() ?: 1080
        if (!preferences.getBoolean("byedpi_enable_cmd_settings", true)) return fallback
        return extractCmdPort(preferences.getStringNotNull("byedpi_cmd_args", "")) ?: fallback
    }

    private fun extractCmdPort(args: String): Int? {
        val tokens = args.split(Regex("\\s+"))
        var index = 0
        var port: Int? = null
        while (index < tokens.size) {
            val token = tokens[index]
            val value = when {
                token == "-p" || token == "--port" -> tokens.getOrNull(index + 1)
                token.startsWith("--port=") -> token.substringAfter('=')
                token.startsWith("-p") && token.length > 2 -> token.substring(2)
                else -> null
            }
            value?.toIntOrNull()?.let { port = it }
            index++
        }
        return port
    }
}
