package io.github.dovecoteescapee.byedpi.activities

import android.os.Bundle
import android.os.Build
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.transition.TransitionManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.dovecoteescapee.byedpi.R
import io.github.dovecoteescapee.byedpi.core.ByeDpiProxy
import io.github.dovecoteescapee.byedpi.core.ByeDpiProxyCmdPreferences
import io.github.dovecoteescapee.byedpi.data.FlowsealProfile
import io.github.dovecoteescapee.byedpi.data.FlowsealProfiles
import io.github.dovecoteescapee.byedpi.databinding.ActivityProfilePickerBinding
import io.github.dovecoteescapee.byedpi.databinding.ItemProfileBinding
import io.github.dovecoteescapee.byedpi.utility.getPreferences
import io.github.dovecoteescapee.byedpi.utility.applyLimeFlowPalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import org.json.JSONArray
import org.json.JSONObject

class ProfilePickerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityProfilePickerBinding
    private lateinit var adapter: ProfileAdapter
    private var testJob: Job? = null
    private var topsExpanded = true

    override fun onCreate(savedInstanceState: Bundle?) {
        applyLimeFlowPalette()
        super.onCreate(savedInstanceState)
        binding = ActivityProfilePickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = ProfileAdapter(
            profiles = FlowsealProfiles.catalog(getPreferences()),
            onClick = select@ { profile ->
                if (testJob?.isActive == true) return@select
                FlowsealProfiles.select(getPreferences(), profile)
                setResult(RESULT_OK)
                finish()
            },
            onTest = test@ { profile ->
                if (testJob?.isActive == true) return@test
                runStrategyTest(listOf(profile))
            },
            onEdit = { profile -> showCustomStrategyDialog(profile) },
            onDelete = { profile -> confirmDeleteCustom(profile) },
        )
        binding.profileList.layoutManager = LinearLayoutManager(this)
        binding.profileList.adapter = adapter
        topsExpanded = getPreferences().getBoolean(TOPS_EXPANDED_KEY, true)
        binding.serviceTopsCard.setOnClickListener {
            topsExpanded = !topsExpanded
            getPreferences().edit().putBoolean(TOPS_EXPANDED_KEY, topsExpanded).apply()
            updateTopsExpansion(animate = true)
        }
        restoreSavedResults()
        binding.backButton.setOnClickListener { finish() }
        binding.smartTestButton.setOnClickListener {
            if (testJob?.isActive == true) {
                testJob?.cancel()
            } else {
                runStrategyTest(FlowsealProfiles.catalog(getPreferences()))
            }
        }
        binding.addStrategyButton.setOnClickListener {
            showCustomStrategyDialog(null)
        }
        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter.filter(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        binding.root.alpha = 0f
        binding.root.translationY = 24f
        binding.root.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(420)
            .start()
    }

    override fun onResume() {
        super.onResume()
        if (::adapter.isInitialized) {
            adapter.replaceProfiles(FlowsealProfiles.catalog(getPreferences()))
        }
    }

    private fun showCustomStrategyDialog(profile: FlowsealProfile?) {
        startActivity(StrategyBuilderActivity.intent(this, profile))
    }

    private fun confirmDeleteCustom(profile: FlowsealProfile) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.custom_strategy_delete_title, profile.name))
            .setMessage(R.string.custom_strategy_delete_message)
            .setNegativeButton(R.string.custom_strategy_cancel, null)
            .setPositiveButton(R.string.custom_strategy_delete) { _, _ ->
                FlowsealProfiles.deleteCustom(getPreferences(), profile.id)
                adapter.replaceProfiles(FlowsealProfiles.catalog(getPreferences()))
            }
            .show()
    }

    private fun runStrategyTest(profiles: List<FlowsealProfile>) {
        binding.testConsoleCard.visibility = View.VISIBLE
        binding.smartProgress.visibility = View.VISIBLE
        binding.smartProgress.max = profiles.size
        binding.smartProgress.progress = 0
        binding.smartTestStatus.setText(R.string.smart_stop_hint)
        binding.searchInput.isEnabled = false
        adapter.beginTesting(clearResults = profiles.size > 1)

        testJob = lifecycleScope.launch {
            val savedByProfile = loadSavedResults()
                .associateByTo(mutableMapOf()) { it.profile.id }
            try {
                profiles.forEachIndexed { index, profile ->
                    binding.smartProgress.progress = index
                    binding.smartTestStatus.text = getString(
                        R.string.smart_testing,
                        profile.name,
                        index + 1,
                        profiles.size,
                    )
                    showConsoleHeader(profile, index, profiles.size)
                    val result = testProfile(profile) { targetResult ->
                        appendConsole(targetResult)
                    }
                    savedByProfile[profile.id] = result
                    adapter.updateResult(result)
                    persistResults(
                        savedByProfile.values.sortedWith(profileResultComparator)
                    )
                    updateTopResults(savedByProfile.values.toList())
                }

                val ranked = savedByProfile.values.sortedWith(profileResultComparator)
                adapter.showRanked(ranked)
                persistResults(ranked)
                updateTopResults(ranked)
                binding.smartProgress.progress = profiles.size
                binding.smartTestStatus.text = getString(
                    R.string.smart_results,
                    ranked.size,
                )
                showFinalConsole(ranked)
            } catch (error: CancellationException) {
                binding.smartTestStatus.setText(R.string.smart_cancelled)
                throw error
            } catch (error: Throwable) {
                Log.e(TAG, "Full strategy test failed", error)
                binding.smartTestStatus.setText(R.string.smart_failed)
            } finally {
                binding.smartProgress.visibility = View.GONE
                binding.searchInput.isEnabled = true
                val savedResults = loadSavedResults().sortedWith(profileResultComparator)
                adapter.showRanked(savedResults)
                updateTopResults(savedResults)
                testJob = null
            }
        }
    }

    private fun restoreSavedResults() {
        val saved = loadSavedResults()
        if (saved.isEmpty()) return
        val ranked = saved.sortedWith(profileResultComparator)
        adapter.showRanked(ranked)
        updateTopResults(ranked)
        binding.smartTestStatus.text = getString(R.string.smart_saved_results, ranked.size)
    }

    private fun persistResults(results: List<ProfileTestResult>) {
        val payload = JSONObject().apply {
            put("version", RESULT_FORMAT_VERSION)
            put("savedAt", System.currentTimeMillis())
            put("results", JSONArray().apply {
                results.forEach { result ->
                    put(JSONObject().apply {
                        put("profileId", result.profile.id)
                        put("protocolSuccess", result.protocolSuccess)
                        put("pingSuccess", result.pingSuccess)
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
        getPreferences().edit().putString(SAVED_RESULTS_KEY, payload.toString()).apply()
    }

    private fun loadSavedResults(): List<ProfileTestResult> = runCatching {
        val raw = getPreferences().getString(SAVED_RESULTS_KEY, null)
            ?: return@runCatching emptyList()
        val payload = JSONObject(raw)
        if (payload.optInt("version") != RESULT_FORMAT_VERSION) {
            return@runCatching emptyList()
        }
        val profiles = FlowsealProfiles.catalog(getPreferences()).associateBy { it.id }
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

    private fun JSONObject.nullableBoolean(key: String): Boolean? =
        if (has(key) && !isNull(key)) getBoolean(key) else null

    private fun JSONObject.nullableDouble(key: String): Double? =
        if (has(key) && !isNull(key)) getDouble(key) else null

    private fun showConsoleHeader(profile: FlowsealProfile, index: Int, total: Int) {
        binding.testConsole.text = buildString {
            append('[').append(index + 1).append('/').append(total).append("] ")
            append(profile.name).append('\n')
            append(getString(R.string.smart_starting)).append('\n')
        }
    }

    private fun appendConsole(result: TargetResult) {
        val serviceMark = when (result.target.category) {
            ServiceCategory.YOUTUBE -> "▶"
            ServiceCategory.DISCORD -> "●"
            ServiceCategory.NETWORK -> "◇"
        }
        val line = if (result.pingOnly) {
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
        binding.testConsole.append("$line\n")
    }

    private fun showFinalConsole(results: List<ProfileTestResult>) {
        binding.testConsole.text = buildString {
            append(getString(R.string.smart_best_title)).append('\n')
            append('\n').append(getString(R.string.smart_top_youtube)).append('\n')
            serviceRanking(results) { it.youtubeScore }.take(5).forEachIndexed { index, result ->
                append(index + 1)
                    .append(". ")
                    .append(result.profile.name)
                    .append(' ')
                    .append(result.youtubeScore)
                    .append("%")
                    .append('\n')
            }
            append('\n').append(getString(R.string.smart_top_discord)).append('\n')
            serviceRanking(results) { it.discordScore }.take(5).forEachIndexed { index, result ->
                append(index + 1)
                    .append(". ")
                    .append(result.profile.name)
                    .append(' ')
                    .append(result.discordScore)
                    .append("%")
                    .append('\n')
            }
            append('\n').append(getString(R.string.smart_choose_result))
        }
    }

    private fun updateTopResults(results: List<ProfileTestResult>) {
        if (results.isEmpty()) {
            binding.serviceTopsCard.visibility = View.GONE
            return
        }
        binding.serviceTopsCard.visibility = View.VISIBLE
        binding.youtubeTop.text = formatServiceTop(results) { it.youtubeScore }
        binding.discordTop.text = formatServiceTop(results) { it.discordScore }
        updateTopsExpansion(animate = false)
    }

    private fun updateTopsExpansion(animate: Boolean) {
        if (animate) {
            TransitionManager.beginDelayedTransition(binding.root)
        }
        binding.serviceTopsContent.visibility = if (topsExpanded) View.VISIBLE else View.GONE
        binding.serviceTopsToggle.setText(
            if (topsExpanded) R.string.smart_tops_hide else R.string.smart_tops_show
        )
    }

    private fun formatServiceTop(
        results: List<ProfileTestResult>,
        score: (ProfileTestResult) -> Int,
    ): String = serviceRanking(results, score)
        .take(3)
        .mapIndexed { index, result ->
            "${index + 1}. ${result.profile.name}\n   ${score(result)}% · ${formatPing(result.averagePingMs)}"
        }
        .joinToString("\n")

    private fun serviceRanking(
        results: List<ProfileTestResult>,
        score: (ProfileTestResult) -> Int,
    ): List<ProfileTestResult> = results.sortedWith(
        compareByDescending<ProfileTestResult> { score(it) }
            .thenByDescending { it.combinedScore }
            .thenByDescending { it.pingSuccess }
            .thenBy { it.averagePingMs ?: Double.MAX_VALUE }
    )

    private suspend fun testProfile(
        profile: FlowsealProfile,
        onTargetComplete: (TargetResult) -> Unit,
    ): ProfileTestResult = supervisorScope {
        val engine = ByeDpiProxy()
        val engineExited = AtomicBoolean(false)
        val engineJob = launch(Dispatchers.IO) {
            try {
                engine.startProxy(ByeDpiProxyCmdPreferences(profile.arguments))
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
                    checkTarget(target, requestSlots)
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

    private suspend fun checkTarget(
        target: TestTarget,
        requestSlots: Semaphore,
    ): TargetResult = supervisorScope {
        val ping = async(Dispatchers.IO) {
            requestSlots.withPermit { ping(target.host) }
        }
        if (target.pingOnly) {
            return@supervisorScope TargetResult(target, pingMs = ping.await())
        }

        val http = async(Dispatchers.IO) {
            requestSlots.withPermit { probeHttps(target, null) }
        }
        val tls12 = async(Dispatchers.IO) {
            requestSlots.withPermit { probeHttps(target, "TLSv1.2") }
        }
        val tls13 = async(Dispatchers.IO) {
            requestSlots.withPermit { probeHttps(target, "TLSv1.3") }
        }
        TargetResult(
            target = target,
            httpOk = http.await(),
            tls12Ok = tls12.await(),
            tls13Ok = tls13.await(),
            pingMs = ping.await(),
        )
    }

    private fun probeHttps(target: TestTarget, tlsVersion: String?): Boolean = runCatching {
        val host = target.host
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(PROXY_HOST, PROXY_PORT))
        val rawSocket = Socket(proxy).apply {
            soTimeout = REQUEST_TIMEOUT_MS
            connect(InetSocketAddress.createUnresolved(host, 443), REQUEST_TIMEOUT_MS)
        }
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
        process.waitFor()
        PING_TIME.find(output)?.groupValues?.get(1)?.toDoubleOrNull()
    }.getOrNull()

    private fun okLabel(value: Boolean?): String = if (value == true) "OK" else "ERR"

    private fun formatPing(value: Double?): String =
        value?.let { String.format(Locale.US, "%.0f ms", it) } ?: "timeout"

    private data class TestTarget(
        val name: String,
        val host: String,
        val category: ServiceCategory,
        val path: String = "/",
        val minBytes: Int = 0,
        val pingOnly: Boolean = false,
    )

    private enum class ServiceCategory { YOUTUBE, DISCORD, NETWORK }

    private data class TargetResult(
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

    private data class ProfileTestResult(
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

    private class ProfileAdapter(
        profiles: List<FlowsealProfile>,
        private val onClick: (FlowsealProfile) -> Unit,
        private val onTest: (FlowsealProfile) -> Unit,
        private val onEdit: (FlowsealProfile) -> Unit,
        private val onDelete: (FlowsealProfile) -> Unit,
    ) : RecyclerView.Adapter<ProfileAdapter.Holder>() {
        private var catalog = profiles
        private var query = ""
        private var testing = false
        private var source = profiles.map { ProfileRow(it) }
        private var visible = source
        private val expandedIds = mutableSetOf<String>()

        fun replaceProfiles(profiles: List<FlowsealProfile>) {
            val results = source.mapNotNull { row -> row.result?.let { row.profile.id to it } }.toMap()
            catalog = profiles
            source = profiles.map { profile -> ProfileRow(profile, results[profile.id]) }
            rebuildVisible()
        }

        fun filter(value: String) {
            query = value.trim().lowercase()
            rebuildVisible()
        }

        fun beginTesting(clearResults: Boolean) {
            testing = true
            if (clearResults) {
                source = source.map { it.copy(result = null) }
            }
            rebuildVisible()
        }

        fun updateResult(result: ProfileTestResult) {
            source = source.map {
                if (it.profile.id == result.profile.id) it.copy(result = result) else it
            }.sortedWith(resultComparator)
            rebuildVisible()
        }

        fun showRanked(results: List<ProfileTestResult>) {
            testing = false
            val byProfile = results.associateBy { it.profile.id }
            source = catalog.map { profile ->
                ProfileRow(profile, byProfile[profile.id])
            }.sortedWith(resultComparator)
            rebuildVisible()
        }

        private fun rebuildVisible() {
            visible = if (query.isEmpty()) {
                source
            } else {
                source.filter {
                    "${it.profile.name} ${it.profile.method} ${it.profile.description}"
                        .lowercase()
                        .contains(query)
                }
            }
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val binding = ItemProfileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return Holder(binding)
        }

        override fun getItemCount() = visible.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val row = visible[position]
            holder.bind(
                row = row,
                rank = row.result?.let {
                    source.indexOfFirst { candidate -> candidate.profile.id == row.profile.id } + 1
                },
                testing = testing,
                expanded = row.profile.id in expandedIds,
                onClick = onClick,
                onTest = onTest,
                onEdit = onEdit,
                onDelete = onDelete,
                onToggle = {
                    if (!expandedIds.add(row.profile.id)) expandedIds.remove(row.profile.id)
                    notifyItemChanged(position)
                },
            )
        }

        data class ProfileRow(
            val profile: FlowsealProfile,
            val result: ProfileTestResult? = null,
        )

        class Holder(private val binding: ItemProfileBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind(
                row: ProfileRow,
                rank: Int?,
                testing: Boolean,
                expanded: Boolean,
                onClick: (FlowsealProfile) -> Unit,
                onTest: (FlowsealProfile) -> Unit,
                onEdit: (FlowsealProfile) -> Unit,
                onDelete: (FlowsealProfile) -> Unit,
                onToggle: () -> Unit,
            ) {
                val profile = row.profile
                val result = row.result
                binding.profileName.text = if (rank == null) {
                    profile.name
                } else {
                    binding.root.context.getString(R.string.smart_rank, rank, profile.name)
                }
                binding.profileMethod.text = if (result == null) {
                    if (testing) binding.root.context.getString(R.string.smart_waiting) else profile.method
                } else {
                    "HTTP/TLS ${result.protocolSuccess}/$PROTOCOL_TEST_COUNT · " +
                        "Ping ${result.pingSuccess}/$PING_TEST_COUNT"
                }
                binding.profileDescription.text = if (result == null) {
                    profile.description
                } else {
                    binding.root.context.getString(
                        R.string.smart_average_ping,
                        result.averagePingMs?.let {
                            String.format(Locale.US, "%.0f ms", it)
                        } ?: "timeout",
                    )
                }
                binding.profileProgress.visibility = if (result == null) View.GONE else View.VISIBLE
                binding.profileProgress.max = 100
                binding.profileProgress.progress = result?.combinedScore ?: 0
                binding.serviceScores.visibility = if (result == null) View.GONE else View.VISIBLE
                binding.youtubeScore.text = result?.let {
                    binding.root.context.getString(R.string.smart_youtube_score, it.youtubeScore)
                }.orEmpty()
                binding.discordScore.text = result?.let {
                    binding.root.context.getString(R.string.smart_discord_score, it.discordScore)
                }.orEmpty()
                binding.focusLabel.setText(
                    when {
                        result == null -> R.string.smart_focus_balanced
                        result.youtubeScore >= result.discordScore + 10 ->
                            R.string.smart_focus_youtube
                        result.discordScore >= result.youtubeScore + 10 ->
                            R.string.smart_focus_discord
                        else -> R.string.smart_focus_balanced
                    }
                )
                binding.profileExpand.visibility = if (result == null) View.GONE else View.VISIBLE
                binding.profileExpand.setText(
                    if (expanded) R.string.smart_hide_details else R.string.smart_show_details
                )
                binding.profileExpand.setOnClickListener {
                    TransitionManager.beginDelayedTransition(binding.root)
                    onToggle()
                }
                binding.profileTestSingle.isEnabled = !testing
                binding.profileTestSingle.setOnClickListener {
                    if (!testing) onTest(profile)
                }
                binding.customActions.visibility = if (profile.custom) View.VISIBLE else View.GONE
                binding.profileEdit.setOnClickListener { if (!testing) onEdit(profile) }
                binding.profileDelete.setOnClickListener { if (!testing) onDelete(profile) }
                binding.profileDetails.visibility =
                    if (result != null && expanded) View.VISIBLE else View.GONE
                binding.profileDetails.text = result?.let { details(it) }.orEmpty()
                binding.root.isEnabled = !testing
                binding.root.alpha = if (testing && result == null) 0.65f else 1f
                binding.root.setOnClickListener { if (!testing) onClick(profile) }
                if (bindingAdapterPosition != RecyclerView.NO_POSITION) {
                    binding.root.translationY = 18f
                    binding.root.animate().translationY(0f).setDuration(240).start()
                }
            }

            private fun flag(value: Boolean?): String = if (value == true) "OK" else "ERR"

            private fun pingLabel(value: Double?): String =
                value?.let { String.format(Locale.US, "%.0fms", it) } ?: "timeout"

            private fun details(result: ProfileTestResult): String = buildString {
                ServiceCategory.values().forEach { category ->
                    val rows = result.targetResults.filter { it.target.category == category }
                    if (rows.isEmpty()) return@forEach
                    if (isNotEmpty()) append('\n')
                    append(
                        when (category) {
                            ServiceCategory.YOUTUBE -> "▶ YOUTUBE"
                            ServiceCategory.DISCORD -> "● DISCORD"
                            ServiceCategory.NETWORK -> "◇ СЕТЬ"
                        }
                    ).append('\n')
                    rows.forEach {
                        append(it.target.name).append("  ")
                        if (it.pingOnly) {
                            append("Ping:").append(pingLabel(it.pingMs))
                        } else {
                            append("HTTP:").append(flag(it.httpOk))
                                .append("  TLS1.2:").append(flag(it.tls12Ok))
                                .append("  TLS1.3:").append(flag(it.tls13Ok))
                                .append("  Ping:").append(pingLabel(it.pingMs))
                        }
                        append('\n')
                    }
                }
            }.trimEnd()
        }

        companion object {
            private val resultComparator =
                compareByDescending<ProfileRow> { it.result != null }
                    .thenByDescending { it.result?.balancedScore ?: -1 }
                    .thenByDescending { it.result?.combinedScore ?: -1 }
                    .thenByDescending { it.result?.pingSuccess ?: -1 }
                    .thenBy { it.result?.averagePingMs ?: Double.MAX_VALUE }
        }
    }

    companion object {
        private const val TAG = "ProfilePicker"
        private const val PROXY_HOST = "127.0.0.1"
        private const val PROXY_PORT = 1080
        private const val REQUEST_TIMEOUT_MS = 4_000
        private const val PROXY_START_DELAY_MS = 500L
        private const val PROXY_STOP_TIMEOUT_MS = 2_000L
        private const val BETWEEN_STRATEGIES_DELAY_MS = 150L
        private const val MAX_PARALLEL_REQUESTS = 8
        private const val SAVED_RESULTS_KEY = "strategy_test_results_v3"
        private const val TOPS_EXPANDED_KEY = "strategy_tops_expanded"
        private const val RESULT_FORMAT_VERSION = 3
        private val PING_TIME = Regex("""time[=<]([\d.]+)\s*ms""")

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
        private val PROTOCOL_TEST_COUNT = TARGETS.count { !it.pingOnly } * 3
        private val PING_TEST_COUNT = TARGETS.size
        private val profileResultComparator =
            compareByDescending<ProfileTestResult> { it.balancedScore }
                .thenByDescending { it.combinedScore }
                .thenByDescending { it.pingSuccess }
                .thenBy { it.averagePingMs ?: Double.MAX_VALUE }
    }
}
