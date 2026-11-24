package io.github.dovecoteescapee.byedpi.core

import android.content.Context
import android.os.Build
import io.github.dovecoteescapee.byedpi.data.ClassicEngine
import io.github.dovecoteescapee.byedpi.data.FlowsealProfile
import io.github.dovecoteescapee.byedpi.data.StrategyMemory
import io.github.dovecoteescapee.byedpi.services.appStatus
import io.github.dovecoteescapee.byedpi.data.AppStatus
import io.github.dovecoteescapee.byedpi.utility.AppLog
import io.github.dovecoteescapee.byedpi.utility.destroyForciblyCompat
import io.github.dovecoteescapee.byedpi.utility.getPreferences
import io.github.dovecoteescapee.byedpi.utility.isAliveCompat
import io.github.dovecoteescapee.byedpi.utility.waitForTimed
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
    /** Mean time of the successful requests through the proxy (connect..body). */
    val latencyMs: Double? = null,
) {
    /*
     * A content probe (body has to flow) says more than a bare handshake, and a
     * download past the 16-20 KB freeze says the most: a strategy that passes
     * handshakes but freezes is useless for video and media.
     */
    val weight: Int
        get() = when {
            target.minBytes >= 32_000 -> 3
            target.minBytes > 0 -> 2
            else -> 1
        }
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
    /** Network the test ran on (StrategyMemory.networkKey); results of another network are not ranked. */
    val networkKey: String = "",
    val testedAt: Long = 0L,
) {
    /** Real speed of the strategy: mean request time through it (ping bypasses the proxy). */
    val averageLatencyMs: Double?
        get() = targetResults.mapNotNull { it.latencyMs }.takeIf { it.isNotEmpty() }?.average()

    val youtubeScore: Int get() = serviceScore(ServiceCategory.YOUTUBE)
    val discordScore: Int get() = serviceScore(ServiceCategory.DISCORD)
    val balancedScore: Int get() = minOf(youtubeScore, discordScore)
    val combinedScore: Int get() = (youtubeScore + discordScore) / 2

    private fun serviceScore(category: ServiceCategory): Int {
        val checks = targetResults.filter {
            !it.pingOnly && it.target.category == category
        }
        if (checks.isEmpty()) return 0
        val passed = checks.sumOf { it.protocolSuccess * it.weight }
        val total = checks.sumOf { 3 * it.weight }
        return passed * 100 / total
    }
}

/*
 * One ranking for the list, the top and "apply best": weighted service
 * scores first, then the weaker of the two services, then real latency.
 * The old order used the raw check count and then the ping, but the ping
 * is measured once per run around the proxy, so it was the same for every
 * strategy and ties fell out at random.
 */
val profileResultComparator: Comparator<ProfileTestResult> =
    compareByDescending<ProfileTestResult> { it.combinedScore }
        .thenByDescending { it.balancedScore }
        .thenBy { it.averageLatencyMs ?: Double.MAX_VALUE }

fun serviceResultComparator(score: (ProfileTestResult) -> Int): Comparator<ProfileTestResult> =
    compareByDescending<ProfileTestResult> { score(it) }
        .thenByDescending { it.combinedScore }
        .thenBy { it.averageLatencyMs ?: Double.MAX_VALUE }

/**
 * Runs the strategy check loop outside any Activity: rotation or theme changes no
 * longer cancel the search, and the UI re-attaches to [state] when recreated.
 */
object StrategyTestRunner {
    private const val TAG = "StrategyTestRunner"

    private const val PROXY_HOST = "127.0.0.1"
    /*
     * Timings and parallelism follow zapret-discord-youtube 1.10.4 "test zapret.ps1":
     * 4 s per request with a 3 s connect limit, min(16, max(8, cores * 2)) requests
     * at once. The engine start is polled instead of a fixed 500 ms sleep.
     */
    private const val REQUEST_TIMEOUT_MS = 4_000
    private const val CONNECT_TIMEOUT_MS = 3_000
    private const val PROXY_START_MAX_WAIT_MS = 800L
    private const val PROXY_START_POLL_MS = 15L
    private const val PROXY_STOP_TIMEOUT_MS = 2_000L
    private const val BETWEEN_STRATEGIES_DELAY_MS = 30L
    private val MAX_PARALLEL_REQUESTS =
        minOf(16, maxOf(8, Runtime.getRuntime().availableProcessors() * 2))
    private const val RESULT_FORMAT_VERSION = 4
    /** Results older than this, or from another network, are not ranked. */
    private const val RESULT_MAX_AGE_MS = 3L * 24 * 60 * 60 * 1000
    private val PING_TIME = Regex("""time[=<]([\d.]+)\s*ms""")

    sealed interface State {
        data object Idle : State
        data class Testing(
            val total: Int,
            val done: Int,
            val currentName: String,
            val console: String,
            val lastResult: ProfileTestResult?,
            /** Ranking so far on this network, so the top fills in live. */
            val ranked: List<ProfileTestResult> = emptyList(),
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
            val networkKey = io.github.dovecoteescapee.byedpi.data.StrategyMemory.networkKey(appContext)
            // Only this network's results are merged: a Wi-Fi winner says
            // nothing about LTE, and mixing them made the top wrong.
            val savedByProfile = currentNetworkResults(appContext, loadSavedResults(appContext))
                .associateByTo(mutableMapOf()) { it.profile.id }
            if (clearResults) savedByProfile.clear()
            try {
                // Ping goes around the proxy and does not depend on the strategy:
                // measure it once per run instead of once per strategy.
                val pings = measurePings()
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
                    val result = testProfile(appContext, profile, pings) { targetResult ->
                        console.append(consoleLine(targetResult)).append('\n')
                        _state.value = State.Testing(
                            total = profiles.size,
                            done = index,
                            currentName = profile.name,
                            console = console.toString(),
                            lastResult = null,
                        )
                    }
                    val stamped = result.copy(networkKey = networkKey, testedAt = System.currentTimeMillis())
                    savedByProfile[profile.id] = stamped
                    persistResults(appContext, savedByProfile.values.sortedWith(profileResultComparator))
                    AppLog.i(
                        TAG,
                        "Strategy \"${profile.name}\" [${index + 1}/${profiles.size}]: " +
                            "protocols ${result.protocolSuccess}, ping ${result.pingSuccess}, " +
                            "youtube ${result.youtubeScore}%, discord ${result.discordScore}%, " +
                            "avg ${result.averagePingMs?.let { "%.0f ms".format(Locale.US, it) } ?: "n/a"}",
                    )
                    _state.value = State.Testing(
                        total = profiles.size,
                        done = index + 1,
                        currentName = profile.name,
                        console = console.toString(),
                        lastResult = stamped,
                        ranked = savedByProfile.values.sortedWith(profileResultComparator),
                    )
                }

                val ranked = savedByProfile.values.sortedWith(profileResultComparator)
                AppLog.i(
                    TAG,
                    "Strategy test finished: ${ranked.size} profiles ranked, " +
                        "best: ${ranked.firstOrNull()?.profile?.name ?: "none"}",
                )
                _state.value = State.Finished(ranked.size)
            } catch (error: CancellationException) {
                _state.value = State.Cancelled
                throw error
            } catch (error: Throwable) {
                AppLog.e(TAG, "Full strategy test failed", error)
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
        pings: Map<String, Double?>,
        onTargetComplete: (TargetResult) -> Unit,
    ): ProfileTestResult = supervisorScope {
        // A per-run free port keeps the check away from the app's own SOCKS service
        // and from any engine instance left behind by a previous session.
        val port = findFreePort()
        val engine = ByeDpiProxy(RustEngine.isSelected(context))
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
                AppLog.e(TAG, "Strategy ${profile.name} failed to start", error)
            } finally {
                engineExited.set(true)
            }
        }

        try {
            awaitEngine(port, engineExited)
            if (engineExited.get()) {
                return@supervisorScope ProfileTestResult(profile, 0, 0, null, emptyList())
            }

            val requestSlots = Semaphore(MAX_PARALLEL_REQUESTS)
            val checks = TARGETS.map { target ->
                async(Dispatchers.IO) {
                    checkTarget(target, port, requestSlots, pings[target.host])
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
                    runCatching { engine.forceClose() }
                    engineJob.cancel()
                }
                delay(BETWEEN_STRATEGIES_DELAY_MS)
            }
        }
    }

    private fun findFreePort(): Int = runCatching {
        ServerSocket(0).use { it.localPort }
    }.getOrDefault(-1)

    private suspend fun checkTarget(
        target: TestTarget,
        proxyPort: Int,
        requestSlots: Semaphore,
        pingMs: Double?,
    ): TargetResult = supervisorScope {
        if (target.pingOnly) {
            return@supervisorScope TargetResult(target, pingMs = pingMs)
        }

        fun timed(tls: String?) = async(Dispatchers.IO) {
            requestSlots.withPermit {
                val started = System.nanoTime()
                val ok = probeHttps(target, proxyPort, tls)
                ok to (System.nanoTime() - started) / 1_000_000.0
            }
        }
        val http = timed(null)
        val tls12 = timed("TLSv1.2")
        val tls13 = timed("TLSv1.3")
        val probes = listOf(http.await(), tls12.await(), tls13.await())
        TargetResult(
            target = target,
            httpOk = probes[0].first,
            tls12Ok = probes[1].first,
            tls13Ok = probes[2].first,
            pingMs = pingMs,
            latencyMs = probes.filter { it.first }.map { it.second }.takeIf { it.isNotEmpty() }?.average(),
        )
    }

    private suspend fun measurePings(): Map<String, Double?> = supervisorScope {
        TARGETS.map { it.host }.distinct()
            .map { host -> host to async(Dispatchers.IO) { ping(host) } }
            .associate { (host, job) -> host to job.await() }
    }

    /** Waits until the engine accepts connections (or exits), at most PROXY_START_MAX_WAIT_MS. */
    private suspend fun awaitEngine(port: Int, engineExited: AtomicBoolean) {
        val deadline = System.currentTimeMillis() + PROXY_START_MAX_WAIT_MS
        while (!engineExited.get() && System.currentTimeMillis() < deadline) {
            val up = withContext(Dispatchers.IO) {
                runCatching {
                    Socket().use { it.connect(InetSocketAddress(PROXY_HOST, port), 100) }
                }.isSuccess
            }
            if (up) return
            delay(PROXY_START_POLL_MS)
        }
    }

    private fun probeHttps(target: TestTarget, proxyPort: Int, tlsVersion: String?): Boolean =
        runCatching {
            val host = target.host
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(PROXY_HOST, proxyPort))
            Socket(proxy).use { rawSocket ->
                rawSocket.soTimeout = REQUEST_TIMEOUT_MS
                rawSocket.connect(InetSocketAddress.createUnresolved(host, 443), CONNECT_TIMEOUT_MS)
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
                            "Range: bytes=0-${maxOf(16_383, target.minBytes + 4_095)}\r\n" +
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
                    /*
                     * Verdict rules, tuned so a strategy only scores when real
                     * traffic would work:
                     *  - 2xx/3xx: origin reached, always success.
                     *  - 400/404/405 on API/CDN hosts: origin alive and answering
                     *    (these endpoints legitimately reject bare GETs), success.
                     *  - 451/429/5xx (520, 503...): DPI block page or edge
                     *    refusing us - FAIL. The old code counted every 4xx as
                     *    success and reported ~100% YouTube while thumbnails
                     *    never loaded.
                     *  - 403: ambiguous. Only success when the target is a real
                     *    content probe (minBytes > 0) and the body actually flows.
                     */
                    val statusOk = when (statusCode) {
                        in 200..399 -> true
                        400, 404, 405 -> true
                        451, 429 -> false
                        in 500..599 -> false
                        403 -> target.minBytes > 0 && bodyOk
                        else -> false
                    }
                    statusOk && (target.minBytes == 0 || bodyOk)
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
        if (!process.waitForTimed(3, TimeUnit.SECONDS)) {
            process.destroyForciblyCompat()
            process.waitForTimed(1, TimeUnit.SECONDS)
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
                                latencyMs = targetItem.nullableDouble("latency"),
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
                        networkKey = item.optString("network"),
                        testedAt = item.optLong("testedAt"),
                    )
                )
            }
        }
    }.getOrElse {
        AppLog.w(TAG, "Saved strategy results are invalid", it)
        emptyList()
    }

    /** Results that belong to the current network and are fresh enough to rank. */
    fun currentNetworkResults(context: Context, results: List<ProfileTestResult>): List<ProfileTestResult> {
        val key = io.github.dovecoteescapee.byedpi.data.StrategyMemory.networkKey(context)
        val now = System.currentTimeMillis()
        return results.filter { it.networkKey == key && now - it.testedAt < RESULT_MAX_AGE_MS }
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
                        put("network", result.networkKey)
                        put("testedAt", result.testedAt)
                        put("targets", JSONArray().apply {
                            result.targetResults.forEach { target ->
                                put(JSONObject().apply {
                                    put("name", target.target.name)
                                    put("http", target.httpOk ?: JSONObject.NULL)
                                    put("tls12", target.tls12Ok ?: JSONObject.NULL)
                                    put("tls13", target.tls13Ok ?: JSONObject.NULL)
                                    put("ping", target.pingMs ?: JSONObject.NULL)
                                    put("latency", target.latencyMs ?: JSONObject.NULL)
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
        // ТСПУ часто пропускает рукопожатие и первые 16–20 КБ, а потом
        // «замораживает» соединение: стратегия выглядит рабочей, но видео и
        // картинки не грузятся. Крупная превью-картинка ловит это.
        TestTarget(
            "YouTubeFreeze64K",
            "i.ytimg.com",
            ServiceCategory.YOUTUBE,
            path = "/vi/dQw4w9WgXcQ/maxresdefault.jpg",
            minBytes = 40_000,
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
        // Cloudflare: если ТСПУ не прочитал SNI, для IP Cloudflare действует
        // правило «16 КБ и обрыв». Главная discord.com (сотни КБ HTML) ловит это.
        TestTarget(
            "DiscordCFFreeze",
            "discord.com",
            ServiceCategory.DISCORD,
            path = "/",
            minBytes = 32_000,
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
