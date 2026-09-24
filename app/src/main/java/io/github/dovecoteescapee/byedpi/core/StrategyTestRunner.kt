package io.github.dovecoteescapee.byedpi.core

import android.content.Context
import android.os.Build
import android.util.Log
import io.github.dovecoteescapee.byedpi.data.ClassicEngine
import io.github.dovecoteescapee.byedpi.data.FlowsealProfile
import io.github.dovecoteescapee.byedpi.data.StrategyMemory
import io.github.dovecoteescapee.byedpi.services.appStatus
import io.github.dovecoteescapee.byedpi.data.AppStatus
import io.github.dovecoteescapee.byedpi.utility.getPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

enum class ServiceCategory { YOUTUBE, DISCORD, NETWORK }

data class TestTarget(
    val name: String,
    val host: String,
    val category: ServiceCategory,
    val path: String = "/",
    val minBytes: Int = 0,
    val pingOnly: Boolean = false,
)

data class TargetResult(
    val target: TestTarget,
    val httpOk: Boolean? = null,
    val tls12Ok: Boolean? = null,
    val tls13Ok: Boolean? = null,
    val pingMs: Double? = null,
) {
    val pingOnly: Boolean get() = target.pingOnly
    val protocolSuccess: Int
        get() = listOf(httpOk, tls12Ok, tls13Ok).count { it == true }
}

data class ProfileTestResult(
    val profile: FlowsealProfile,
    val protocolSuccess: Int,
    val pingSuccess: Int,
    val averagePingMs: Double?,
    val targetResults: List<TargetResult>,
) {
    val youtubeScore: Int get() = serviceScore(ServiceCategory.YOUTUBE)
    val discordScore: Int get() = serviceScore(ServiceCategory.DISCORD)
    val balancedScore: Int get() = minOf(youtubeScore, discordScore)
    val combinedScore: Int get() = (youtubeScore + discordScore) / 2

    private fun serviceScore(category: ServiceCategory): Int {
        val checks = targetResults.filter {
            !it.pingOnly && it.target.category == category
        }
        if (checks.isEmpty()) return 0
        return checks.sumOf { it.protocolSuccess } * 100 / (checks.size * 3)
    }
}

val profileResultComparator =
    compareByDescending<ProfileTestResult> { it.protocolSuccess }
        .thenByDescending { it.pingSuccess }
        .thenBy { it.averagePingMs ?: Double.MAX_VALUE }

/**
 * Runs the strategy check loop outside any Activity: rotation or theme changes no
 * longer cancel the search, and the UI re-attaches to [state] when recreated.
 */
object StrategyTestRunner {
    private const val TAG = "StrategyTestRunner"

    private const val PROXY_HOST = "127.0.0.1"
    private const val REQUEST_TIMEOUT_MS = 4_000
    private const val PROXY_START_DELAY_MS = 500L
    private const val PROXY_STOP_TIMEOUT_MS = 2_000L
    private const val BETWEEN_STRATEGIES_DELAY_MS = 150L
    private const val MAX_PARALLEL_REQUESTS = 8
    private const val RESULT_FORMAT_VERSION = 3
    private val PING_TIME = Regex("""time[=<]([\d.]+)\s*ms""")

    sealed interface State {
        data object Idle : State
        data class Testing(
            val total: Int,
            val done: Int,
            val currentName: String,
            val console: String,
            val lastResult: ProfileTestResult?,
        ) : State

        data class Finished(val rankedCount: Int) : State
        data object Cancelled : State
        data object Failed : State
    }

    enum class StartResult { Started, AlreadyRunning, ServiceRunning }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private var runJob: Job? = null
    val isRunning: Boolean get() = runJob?.isActive == true

    fun start(
        context: Context,
        profiles: List<FlowsealProfile>,
        clearResults: Boolean,
    ): StartResult {
        if (runJob?.isActive == true) return StartResult.AlreadyRunning
        // The engine keeps process-global state and a fixed default port, so it can
        // never coexist with the app's own VPN/proxy service.
        if (appStatus.first == AppStatus.Running) return StartResult.ServiceRunning

        val appContext = context.applicationContext
        runJob = scope.launch {
            val console = StringBuilder()
            val savedByProfile = loadSavedResults(appContext)
                .associateByTo(mutableMapOf()) { it.profile.id }
            try {
                profiles.forEachIndexed { index, profile ->
                    console.clear()
                    console.append('[').append(index + 1).append('/').append(profiles.size)
                        .append("] ").append(profile.name).append('\n')
                        .append(appContext.getString(io.github.dovecoteescapee.byedpi.R.string.smart_starting))
                        .append('\n')
                    _state.value = State.Testing(
                        total = profiles.size,
                        done = index,
                        currentName = profile.name,
                        console = console.toString(),
                        lastResult = null,
                    )
                    val result = testProfile(appContext, profile) { targetResult ->
                        console.append(consoleLine(targetResult)).append('\n')
                        _state.value = State.Testing(
                            total = profiles.size,
                            done = index,
                            currentName = profile.name,
                            console = console.toString(),
                            lastResult = null,
                        )
                    }
                    savedByProfile[profile.id] = result
                    persistResults(appContext, savedByProfile.values.sortedWith(profileResultComparator))
                    _state.value = State.Testing(
                        total = profiles.size,
                        done = index + 1,
                        currentName = profile.name,
                        console = console.toString(),
                        lastResult = result,
                    )
                }

                val ranked = savedByProfile.values.sortedWith(profileResultComparator)
                _state.value = State.Finished(ranked.size)
            } catch (error: CancellationException) {
                _state.value = State.Cancelled
                throw error
            } catch (error: Throwable) {
                Log.e(TAG, "Full strategy test failed", error)
                _state.value = State.Failed
            }
        }
        return StartResult.Started
    }

    fun stop() {
        runJob?.cancel()
    }

    private suspend fun testProfile(
        context: Context,
        profile: FlowsealProfile,
        onTargetComplete: (TargetResult) -> Unit,
    ): ProfileTestResult = supervisorScope {
        // A per-run free port keeps the check away from the app's own SOCKS service
        // and from any engine instance left behind by a previous session.
        val port = findFreePort()
        val engine = ByeDpiProxy()
        val engineExited = AtomicBoolean(false)
        val engineJob = launch(Dispatchers.IO) {
            try {
                var args = ByeDpiProxyCmdPreferences(profile.arguments).args
                if (ClassicEngine.isEnabled(context.getPreferences())) {
                    args = ClassicEngine.applyToArgs(args)
                }
                engine.startProxy(ByeDpiProxyCmdPreferences(args + arrayOf("-p", port.toString())))
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                Log.e(TAG, "Strategy ${profile.name} failed to start", error)
            } finally {
                engineExited.set(true)
            }
        }

        try {
            delay(PROXY_START_DELAY_MS)
            if (engineExited.get()) {
                return@supervisorScope ProfileTestResult(profile, 0, 0, null, emptyList())
            }

            val requestSlots = Semaphore(MAX_PARALLEL_REQUESTS)
            val checks = TARGETS.map { target ->
                async(Dispatchers.IO) {
                    checkTarget(target, port, requestSlots)
                }
            }

            var protocolSuccess = 0
            var pingSuccess = 0
            val pings = mutableListOf<Double>()
            val targetResults = mutableListOf<TargetResult>()
            checks.forEach { check ->
                val result = check.await()
                targetResults += result
                protocolSuccess += result.protocolSuccess
                if (result.pingMs != null) {
                    pingSuccess++
                    pings += result.pingMs
                }
                withContext(Dispatchers.Main) { onTargetComplete(result) }
            }

            ProfileTestResult(
                profile = profile,
                protocolSuccess = protocolSuccess,
                pingSuccess = pingSuccess,
                averagePingMs = pings.takeIf { it.isNotEmpty() }?.average(),
                targetResults = targetResults,
            )
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { engine.stopProxy() }
                if (withTimeoutOrNull(PROXY_STOP_TIMEOUT_MS) { engineJob.join() } == null) {
                    runCatching { engine.jniForceClose() }
                    engineJob.cancel()
                }
                delay(BETWEEN_STRATEGIES_DELAY_MS)
            }
        }
    }

    private fun findFreePort(): Int = runCatching {
        ServerSocket(0).use { it.localPort }
    }.getOrDefault(1080)

    private suspend fun checkTarget(
        target: TestTarget,
        proxyPort: Int,
        requestSlots: Semaphore,
    ): TargetResult = supervisorScope {
        val ping = async(Dispatchers.IO) {
            requestSlots.withPermit { ping(target.host) }
        }
        if (target.pingOnly) {
            return@supervisorScope TargetResult(target, pingMs = ping.await())
        }

        val http = async(Dispatchers.IO) {
            requestSlots.withPermit { probeHttps(target, proxyPort, null) }
        }
        val tls12 = async(Dispatchers.IO) {
            requestSlots.withPermit { probeHttps(target, proxyPort, "TLSv1.2") }
        }
        val tls13 = async(Dispatchers.IO) {
            requestSlots.withPermit { probeHttps(target, proxyPort, "TLSv1.3") }
        }
        TargetResult(
            target = target,
            httpOk = http.await(),
            tls12Ok = tls12.await(),
            tls13Ok = tls13.await(),
            pingMs = ping.await(),
        )
    }

    private fun probeHttps(target: TestTarget, proxyPort: Int, tlsVersion: String?): Boolean =
        runCatching {
            val host = target.host
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(PROXY_HOST, proxyPort))
            Socket(proxy).use { rawSocket ->
                rawSocket.soTimeout = REQUEST_TIMEOUT_MS
                rawSocket.connect(InetSocketAddress.createUnresolved(host, 443), REQUEST_TIMEOUT_MS)
                val sslContext = SSLContext.getInstance("TLS").apply { init(null, null, null) }
                val sslSocket = sslContext.socketFactory
                    .createSocket(rawSocket, host, 443, true) as SSLSocket
                sslSocket.use { socket ->
                    socket.soTimeout = REQUEST_TIMEOUT_MS
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        socket.sslParameters = socket.sslParameters.apply {
                            endpointIdentificationAlgorithm = "HTTPS"
                        }
                    }
                    if (tlsVersion != null) {
                        if (tlsVersion !in socket.supportedProtocols) return@runCatching false
                        socket.enabledProtocols = arrayOf(tlsVersion)
                    }
                    socket.startHandshake()
                    val writer = OutputStreamWriter(socket.outputStream, Charsets.US_ASCII)
                    writer.write(
                        "GET ${target.path} HTTP/1.1\r\n" +
                            "Host: $host\r\n" +
                            "Range: bytes=0-16383\r\n" +
                            "Accept: */*\r\n" +
                            "Connection: close\r\n\r\n"
                    )
                    writer.flush()
                    val reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.ISO_8859_1))
                    val statusLine = reader.readLine().orEmpty()
                    val statusCode =
                        statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: return@runCatching false
                    while (true) {
                        val header = reader.readLine() ?: return@runCatching false
                        if (header.isEmpty()) break
                    }
                    val bodyOk = target.minBytes == 0 || readAtLeast(reader, target.minBytes)
                    statusCode in 200..499 && bodyOk
                }
            }
        }.getOrDefault(false)

    private fun readAtLeast(reader: BufferedReader, minimum: Int): Boolean {
        var total = 0
        val buffer = CharArray(2048)
        while (total < minimum) {
            val count = reader.read(buffer, 0, minOf(buffer.size, minimum - total))
            if (count < 0) break
            total += count
        }
        return total >= minimum
    }

    private fun ping(host: String): Double? = runCatching {
        val process = ProcessBuilder("/system/bin/ping", "-c", "1", "-W", "2", host)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(3, TimeUnit.SECONDS)) {
            process.destroy()
            return@runCatching null
        }
        PING_TIME.find(output)?.groupValues?.get(1)?.toDoubleOrNull()
    }.getOrNull()

    private fun consoleLine(result: TargetResult): String {
        val serviceMark = when (result.target.category) {
            ServiceCategory.YOUTUBE -> "▶"
            ServiceCategory.DISCORD -> "●"
            ServiceCategory.NETWORK -> "◇"
        }
        return if (result.pingOnly) {
            String.format(
                Locale.US,
                "%s %-20s Ping: %s",
                serviceMark,
                result.target.name,
                formatPing(result.pingMs),
            )
        } else {
            String.format(
                Locale.US,
                "%s %-20s HTTP:%-5s TLS1.2:%-5s TLS1.3:%-5s | %s",
                serviceMark,
                result.target.name,
                okLabel(result.httpOk),
                okLabel(result.tls12Ok),
                okLabel(result.tls13Ok),
                formatPing(result.pingMs),
            )
        }
    }

    private fun okLabel(value: Boolean?): String = if (value == true) "OK" else "ERR"

    private fun formatPing(value: Double?): String =
        value?.let { String.format(Locale.US, "%.0f ms", it) } ?: "timeout"

    fun loadSavedResults(context: Context): List<ProfileTestResult> = runCatching {
        val raw = context.getPreferences().getString(SAVED_RESULTS_KEY, null)
            ?: return@runCatching emptyList()
        val payload = JSONObject(raw)
        if (payload.optInt("version") != RESULT_FORMAT_VERSION) {
            return@runCatching emptyList()
        }
        val profiles = io.github.dovecoteescapee.byedpi.data.FlowsealProfiles
            .catalog(context.getPreferences()).associateBy { it.id }
        val targets = TARGETS.associateBy { it.name }
        val stored = payload.getJSONArray("results")
        buildList {
            for (index in 0 until stored.length()) {
                val item = stored.getJSONObject(index)
                val profile = profiles[item.optString("profileId")] ?: continue
                val targetItems = item.optJSONArray("targets") ?: JSONArray()
                val targetResults = buildList {
                    for (targetIndex in 0 until targetItems.length()) {
                        val targetItem = targetItems.getJSONObject(targetIndex)
                        val target = targets[targetItem.optString("name")] ?: continue
                        add(
                            TargetResult(
                                target = target,
                                httpOk = targetItem.nullableBoolean("http"),
                                tls12Ok = targetItem.nullableBoolean("tls12"),
                                tls13Ok = targetItem.nullableBoolean("tls13"),
                                pingMs = targetItem.nullableDouble("ping"),
                            )
                        )
                    }
                }
                if (targetResults.size != TARGETS.size) continue
                add(
                    ProfileTestResult(
                        profile = profile,
                        protocolSuccess = item.optInt("protocolSuccess"),
                        pingSuccess = item.optInt("pingSuccess"),
                        averagePingMs = item.nullableDouble("averagePingMs"),
                        targetResults = targetResults,
                    )
                )
            }
        }
    }.getOrElse {
        Log.w(TAG, "Saved strategy results are invalid", it)
        emptyList()
    }

    private fun persistResults(context: Context, results: List<ProfileTestResult>) {
        val payload = JSONObject().apply {
            put("version", RESULT_FORMAT_VERSION)
            put("savedAt", System.currentTimeMillis())
            put("protocolTotal", PROTOCOL_TEST_COUNT)
            put("pingTotal", PING_TEST_COUNT)
            put("results", JSONArray().apply {
                results.forEach { result ->
                    put(JSONObject().apply {
                        put("profileId", result.profile.id)
                        put("protocolSuccess", result.protocolSuccess)
                        put("protocolTotal", PROTOCOL_TEST_COUNT)
                        put("pingSuccess", result.pingSuccess)
                        put("pingTotal", PING_TEST_COUNT)
                        put("averagePingMs", result.averagePingMs ?: JSONObject.NULL)
                        put("targets", JSONArray().apply {
                            result.targetResults.forEach { target ->
                                put(JSONObject().apply {
                                    put("name", target.target.name)
                                    put("http", target.httpOk ?: JSONObject.NULL)
                                    put("tls12", target.tls12Ok ?: JSONObject.NULL)
                                    put("tls13", target.tls13Ok ?: JSONObject.NULL)
                                    put("ping", target.pingMs ?: JSONObject.NULL)
                                })
                            }
                        })
                    })
                }
            })
        }
        context.getPreferences().edit().putString(SAVED_RESULTS_KEY, payload.toString()).apply()
    }

    private fun JSONObject.nullableBoolean(key: String): Boolean? =
        if (has(key) && !isNull(key)) getBoolean(key) else null

    private fun JSONObject.nullableDouble(key: String): Double? =
        if (has(key) && !isNull(key)) getDouble(key) else null

    private const val SAVED_RESULTS_KEY = StrategyMemory.RESULTS_KEY

    private val TARGETS = listOf(
        TestTarget(
            "YouTubeWeb",
            "www.youtube.com",
            ServiceCategory.YOUTUBE,
            path = "/generate_204",
        ),
        TestTarget(
            "YouTubeImage",
            "i.ytimg.com",
            ServiceCategory.YOUTUBE,
            path = "/vi/dQw4w9WgXcQ/hqdefault.jpg",
            minBytes = 512,
        ),
        TestTarget(
            "YouTubeAPI",
            "youtubei.googleapis.com",
            ServiceCategory.YOUTUBE,
        ),
        TestTarget(
            "YouTubeAvatar",
            "yt3.ggpht.com",
            ServiceCategory.YOUTUBE,
        ),
        TestTarget(
            "GoogleVideoMap",
            "redirector.googlevideo.com",
            ServiceCategory.YOUTUBE,
            path = "/report_mapping",
            minBytes = 1,
        ),
        TestTarget(
            "GoogleVideoManifest",
            "manifest.googlevideo.com",
            ServiceCategory.YOUTUBE,
        ),
        TestTarget(
            "YouTubeSignaler",
            "signaler-pa.youtube.com",
            ServiceCategory.YOUTUBE,
        ),
        TestTarget(
            "YouTubeJnnApi",
            "jnn-pa.googleapis.com",
            ServiceCategory.YOUTUBE,
        ),
        TestTarget(
            "DiscordVoiceHost",
            "discord.gg",
            ServiceCategory.DISCORD,
        ),
        TestTarget(
            "DiscordAPI",
            "discord.com",
            ServiceCategory.DISCORD,
            path = "/api/v9/gateway",
            minBytes = 16,
        ),
        TestTarget(
            "DiscordGateway",
            "gateway.discord.gg",
            ServiceCategory.DISCORD,
            path = "/?v=9&encoding=json",
        ),
        TestTarget(
            "DiscordCDN",
            "cdn.discordapp.com",
            ServiceCategory.DISCORD,
        ),
        TestTarget(
            "DiscordMedia",
            "media.discordapp.net",
            ServiceCategory.DISCORD,
        ),
        TestTarget(
            "DiscordUpdates",
            "updates.discord.com",
            ServiceCategory.DISCORD,
        ),
        TestTarget(
            "DiscordVoice",
            "discord.media",
            ServiceCategory.DISCORD,
        ),
        TestTarget(
            "CloudflareDNS",
            "1.1.1.1",
            ServiceCategory.NETWORK,
            pingOnly = true,
        ),
        TestTarget(
            "GoogleDNS",
            "8.8.8.8",
            ServiceCategory.NETWORK,
            pingOnly = true,
        ),
        TestTarget(
            "Quad9DNS",
            "9.9.9.9",
            ServiceCategory.NETWORK,
            pingOnly = true,
        ),
    )

    val PROTOCOL_TEST_COUNT = TARGETS.count { !it.pingOnly } * 3
    val PING_TEST_COUNT = TARGETS.size
}
