package io.github.dovecoteescapee.byedpi.activities

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import androidx.transition.TransitionManager
import android.util.Log
import android.view.LayoutInflater

import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.dovecoteescapee.byedpi.R
import io.github.dovecoteescapee.byedpi.core.ProfileTestResult
import io.github.dovecoteescapee.byedpi.core.ServiceCategory
import io.github.dovecoteescapee.byedpi.core.StrategyTestRunner
import io.github.dovecoteescapee.byedpi.core.profileResultComparator
import io.github.dovecoteescapee.byedpi.data.FlowsealProfile
import io.github.dovecoteescapee.byedpi.data.FlowsealProfiles
import io.github.dovecoteescapee.byedpi.data.ProfileKind
import io.github.dovecoteescapee.byedpi.data.StrategyMemory
import io.github.dovecoteescapee.byedpi.databinding.ActivityProfilePickerBinding
import io.github.dovecoteescapee.byedpi.databinding.ItemProfileBinding
import io.github.dovecoteescapee.byedpi.utility.applyLimeFlowPalette
import io.github.dovecoteescapee.byedpi.utility.getPreferences
import kotlinx.coroutines.launch
import java.util.Locale

class ProfilePickerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityProfilePickerBinding
    private lateinit var adapter: ProfileAdapter
    private var topsExpanded = false
    private var pendingBest: FlowsealProfile? = null
    private var wasTesting = false
    private var lastRenderedResult: ProfileTestResult? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        applyLimeFlowPalette()
        super.onCreate(savedInstanceState)
        binding = ActivityProfilePickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = ProfileAdapter(
            profiles = FlowsealProfiles.catalog(getPreferences()),
            pinnedIds = StrategyMemory.pinned(getPreferences()),
            onClick = select@ { profile ->
                if (StrategyTestRunner.isRunning) return@select
                applyProfile(profile)
            },
            onTest = test@ { profile ->
                if (StrategyTestRunner.isRunning) return@test
                startTest(listOf(profile), clearResults = false)
            },
            onPin = { profile ->
                StrategyMemory.togglePin(getPreferences(), profile.id)
                adapter.setPinned(StrategyMemory.pinned(getPreferences()))
            },
            onEdit = { profile -> showCustomStrategyDialog(profile) },
            onDelete = { profile -> confirmDeleteCustom(profile) },
        )
        binding.profileList.layoutManager = LinearLayoutManager(this)
        binding.profileList.adapter = adapter
        binding.youtubeFeaturedCard.setOnClickListener {
            if (StrategyTestRunner.isRunning) return@setOnClickListener
            val youtube = FlowsealProfiles.catalog(getPreferences())
                .firstOrNull { it.id == "limeflow_youtube" } ?: return@setOnClickListener
            applyProfile(youtube)
        }
        binding.chipAll.setOnClickListener { setCatalogFilter(CatalogFilter.ALL) }
        binding.chipYoutube.setOnClickListener { setCatalogFilter(CatalogFilter.YOUTUBE) }
        binding.chipDiscord.setOnClickListener { setCatalogFilter(CatalogFilter.DISCORD) }
        binding.chipOperator.setOnClickListener { setCatalogFilter(CatalogFilter.OPERATOR) }
        binding.chipOther.setOnClickListener { setCatalogFilter(CatalogFilter.OTHER) }
        setCatalogFilter(CatalogFilter.ALL)
        topsExpanded = getPreferences().getBoolean(TOPS_EXPANDED_KEY, false)
        binding.serviceTopsCard.setOnClickListener {
            topsExpanded = !topsExpanded
            getPreferences().edit().putBoolean(TOPS_EXPANDED_KEY, topsExpanded).apply()
            updateTopsExpansion(animate = true)
        }
        restoreSavedResults()
        binding.backButton.setOnClickListener { finish() }
        binding.hostsButton.setOnClickListener {
            startActivity(Intent(this, HostListActivity::class.java))
        }
        binding.copyStrategyButton.setOnClickListener {
            copyStrategy(FlowsealProfiles.selected(getPreferences()))
        }
        binding.applyBestButton.setOnClickListener {
            pendingBest?.let { applyProfile(it) }
        }
        binding.applyBestCard.setOnClickListener {
            pendingBest?.let { applyProfile(it) }
        }
        binding.smartTestButton.setOnClickListener {
            if (StrategyTestRunner.isRunning) {
                StrategyTestRunner.stop()
            } else {
                startTest(FlowsealProfiles.catalog(getPreferences()), clearResults = true)
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
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                StrategyTestRunner.state.collect { state -> render(state) }
            }
        }
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
            adapter.setPinned(StrategyMemory.pinned(getPreferences()))
        }
    }

    private fun startTest(profiles: List<FlowsealProfile>, clearResults: Boolean) {
        when (StrategyTestRunner.start(applicationContext, profiles, clearResults)) {
            StrategyTestRunner.StartResult.Started -> {
                wasTesting = false
                lastRenderedResult = null
                adapter.beginTesting(clearResults)
            }

            StrategyTestRunner.StartResult.AlreadyRunning -> Unit

            StrategyTestRunner.StartResult.ServiceRunning ->
                Toast.makeText(this, R.string.smart_service_running, Toast.LENGTH_LONG).show()
        }
    }

    private fun render(state: StrategyTestRunner.State) {
        when (state) {
            is StrategyTestRunner.State.Testing -> {
                if (!wasTesting) {
                    wasTesting = true
                    lastRenderedResult = null
                    binding.testConsoleCard.visibility = View.VISIBLE
                    binding.applyBestCard.visibility = View.GONE
                    binding.searchInput.isEnabled = false
                    adapter.setTesting(true)
                }
                binding.smartProgress.visibility = View.VISIBLE
                binding.smartProgress.max = state.total
                binding.smartProgress.progress = state.done
                binding.smartTestTitle.setText(R.string.smart_test_stop)
                binding.smartTestStatus.text = getString(
                    R.string.smart_testing,
                    state.currentName,
                    state.done + 1,
                    state.total,
                )
                binding.testConsole.text = state.console
                val last = state.lastResult
                if (last != null && last != lastRenderedResult) {
                    lastRenderedResult = last
                    adapter.updateResult(last)
                }
            }

            is StrategyTestRunner.State.Finished ->
                finishTesting(finished = true) { getString(R.string.smart_results, state.rankedCount) }

            is StrategyTestRunner.State.Cancelled ->
                finishTesting { getString(R.string.smart_cancelled) }

            is StrategyTestRunner.State.Failed -> {
                Log.e(TAG, "Strategy test failed")
                finishTesting { getString(R.string.smart_failed) }
            }

            is StrategyTestRunner.State.Idle -> {
                wasTesting = false
                binding.smartProgress.visibility = View.GONE
            }
        }
    }

    private fun finishTesting(finished: Boolean = false, status: () -> CharSequence) {
        wasTesting = false
        lastRenderedResult = null
        binding.smartProgress.visibility = View.GONE
        binding.smartTestTitle.setText(R.string.smart_test)
        binding.searchInput.isEnabled = true
        val saved = StrategyTestRunner.loadSavedResults(this).sortedWith(profileResultComparator)
        adapter.showRanked(saved)
        updateTopResults(saved)
        binding.smartTestStatus.text = status()
        if (finished) {
            showApplyBest(saved)
            showFinalConsole(saved)
        }
    }

    private fun applyProfile(profile: FlowsealProfile) {
        val preferences = getPreferences()
        FlowsealProfiles.select(preferences, profile)
        StrategyMemory.rememberForCurrentNetwork(this, preferences, profile.id)
        setResult(RESULT_OK)
        finish()
    }

    private fun copyStrategy(profile: FlowsealProfile) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(profile.name, profile.arguments))
        Toast.makeText(this, R.string.strategy_copied, Toast.LENGTH_SHORT).show()
    }

    private fun showApplyBest(ranked: List<ProfileTestResult>) {
        // The list is sorted best-first; the first profile that passed at least one
        // protocol check is the best one.
        val best = ranked.firstOrNull { it.protocolSuccess > 0 } ?: run {
            binding.applyBestCard.visibility = View.GONE
            pendingBest = null
            return
        }
        pendingBest = best.profile
        binding.applyBestCard.visibility = View.VISIBLE
        binding.applyBestLabel.text = getString(
            R.string.apply_best_summary,
            best.profile.name,
            best.protocolSuccess,
            StrategyTestRunner.PROTOCOL_TEST_COUNT,
        )
    }

    private fun setCatalogFilter(filter: CatalogFilter) {
        adapter.setKindFilter(filter)
        binding.youtubeFeaturedCard.visibility =
            if (filter == CatalogFilter.ALL || filter == CatalogFilter.YOUTUBE) {
                View.VISIBLE
            } else {
                View.GONE
            }
        styleChip(binding.chipAll, filter == CatalogFilter.ALL)
        styleChip(binding.chipYoutube, filter == CatalogFilter.YOUTUBE, youtube = true)
        styleChip(binding.chipDiscord, filter == CatalogFilter.DISCORD)
        styleChip(binding.chipOperator, filter == CatalogFilter.OPERATOR)
        styleChip(binding.chipOther, filter == CatalogFilter.OTHER)
    }

    private fun styleChip(chip: MaterialButton, active: Boolean, youtube: Boolean = false) {
        val primary = MaterialColors.getColor(chip, com.google.android.material.R.attr.colorPrimary)
        val onPrimary = MaterialColors.getColor(chip, com.google.android.material.R.attr.colorOnPrimary)
        val muted = ContextCompat.getColor(this, R.color.app_text)
        val surface = ContextCompat.getColor(this, R.color.app_surface)
        val youtubeRed = ContextCompat.getColor(this, R.color.youtube_red)
        when {
            active && youtube -> {
                chip.backgroundTintList = android.content.res.ColorStateList.valueOf(youtubeRed)
                chip.setTextColor(onPrimary)
                chip.strokeWidth = 0
            }
            active -> {
                chip.backgroundTintList = android.content.res.ColorStateList.valueOf(primary)
                chip.setTextColor(onPrimary)
                chip.strokeWidth = 0
            }
            else -> {
                chip.backgroundTintList = android.content.res.ColorStateList.valueOf(surface)
                chip.setTextColor(muted)
                chip.strokeColor = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.app_stroke)
                )
                chip.strokeWidth = 2
            }
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

    private fun restoreSavedResults() {
        val saved = StrategyTestRunner.loadSavedResults(this)
        if (saved.isEmpty()) return
        val ranked = saved.sortedWith(profileResultComparator)
        adapter.showRanked(ranked)
        updateTopResults(ranked)
        showApplyBest(ranked)
        binding.smartTestStatus.text = getString(R.string.smart_saved_results, ranked.size)
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

    private fun formatPing(value: Double?): String =
        value?.let { String.format(Locale.US, "%.0f ms", it) } ?: "timeout"

    private class ProfileAdapter(
        profiles: List<FlowsealProfile>,
        private var pinnedIds: Set<String>,
        private val onClick: (FlowsealProfile) -> Unit,
        private val onTest: (FlowsealProfile) -> Unit,
        private val onPin: (FlowsealProfile) -> Unit,
        private val onEdit: (FlowsealProfile) -> Unit,
        private val onDelete: (FlowsealProfile) -> Unit,
    ) : RecyclerView.Adapter<ProfileAdapter.Holder>() {
        private var catalog = profiles
        private var query = ""
        private var testing = false
        private var kindFilter = CatalogFilter.ALL
        private val expandedIds = mutableSetOf<String>()
        private val rowComparator =
            compareByDescending<ProfileRow> { it.profile.id in pinnedIds }
                .thenByDescending { it.result != null }
                .thenByDescending { it.result?.protocolSuccess ?: -1 }
                .thenByDescending { it.result?.pingSuccess ?: -1 }
                .thenBy { it.result?.averagePingMs ?: Double.MAX_VALUE }
        private var source = profiles.map { ProfileRow(it) }.sortedWith(rowComparator)
        private var visible = source

        fun setKindFilter(filter: CatalogFilter) {
            kindFilter = filter
            rebuildVisible()
        }

        fun setPinned(ids: Set<String>) {
            pinnedIds = ids
            source = source.sortedWith(rowComparator)
            rebuildVisible()
        }

        fun replaceProfiles(profiles: List<FlowsealProfile>) {
            val results = source.mapNotNull { row -> row.result?.let { row.profile.id to it } }.toMap()
            catalog = profiles
            source = profiles.map { profile -> ProfileRow(profile, results[profile.id]) }
                .sortedWith(rowComparator)
            rebuildVisible()
        }

        fun filter(value: String) {
            query = value.trim().lowercase()
            rebuildVisible()
        }

        fun setTesting(value: Boolean) {
            testing = value
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
            }.sortedWith(rowComparator)
            rebuildVisible()
        }

        fun showRanked(results: List<ProfileTestResult>) {
            testing = false
            val byProfile = results.associateBy { it.profile.id }
            source = catalog.map { profile ->
                ProfileRow(profile, byProfile[profile.id])
            }.sortedWith(rowComparator)
            rebuildVisible()
        }

        private fun rebuildVisible() {
            visible = source.filter { row ->
                matchesKind(row.profile) &&
                    (query.isEmpty() ||
                        "${row.profile.name} ${row.profile.method} ${row.profile.description} ${row.profile.badge}"
                            .lowercase()
                            .contains(query))
            }
            notifyDataSetChanged()
        }

        private fun matchesKind(profile: FlowsealProfile): Boolean = when (kindFilter) {
            CatalogFilter.ALL -> true
            CatalogFilter.YOUTUBE -> profile.kind == ProfileKind.YOUTUBE
            CatalogFilter.DISCORD -> profile.kind == ProfileKind.DISCORD
            CatalogFilter.OPERATOR -> profile.kind == ProfileKind.OPERATOR
            CatalogFilter.OTHER -> profile.kind == ProfileKind.UNIVERSAL ||
                profile.kind == ProfileKind.GENERAL ||
                profile.kind == ProfileKind.CUSTOM
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
                    source.mapNotNull { candidate -> candidate.result }
                        .sortedWith(profileResultComparator)
                        .indexOfFirst { scored -> scored.profile.id == row.profile.id } + 1
                },
                testing = testing,
                pinned = row.profile.id in pinnedIds,
                expanded = row.profile.id in expandedIds,
                onClick = onClick,
                onTest = onTest,
                onPin = onPin,
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
                pinned: Boolean,
                expanded: Boolean,
                onClick: (FlowsealProfile) -> Unit,
                onTest: (FlowsealProfile) -> Unit,
                onPin: (FlowsealProfile) -> Unit,
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
                if (profile.badge.isNotEmpty()) {
                    binding.profileBadge.visibility = View.VISIBLE
                    binding.profileBadge.text = profile.badge
                } else {
                    binding.profileBadge.visibility = View.GONE
                }
                val youtubeAccent = profile.kind == ProfileKind.YOUTUBE
                val discordAccent = profile.kind == ProfileKind.DISCORD
                binding.root.strokeColor = ContextCompat.getColor(
                    binding.root.context,
                    when {
                        youtubeAccent -> R.color.youtube_red
                        discordAccent -> R.color.discord_blurple
                        else -> R.color.app_stroke
                    },
                )
                binding.root.strokeWidth = if (youtubeAccent || discordAccent) 2 else 1
                binding.profileMethod.visibility =
                    if (result == null && !testing) View.GONE else View.VISIBLE
                binding.profileMethod.text = if (result == null) {
                    if (testing) binding.root.context.getString(R.string.smart_waiting) else profile.method
                } else {
                    "HTTP/TLS ${result.protocolSuccess}/${StrategyTestRunner.PROTOCOL_TEST_COUNT} · " +
                        "Ping ${result.pingSuccess}/${StrategyTestRunner.PING_TEST_COUNT}"
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
                binding.youtubeScore.setTextColor(
                    ContextCompat.getColor(binding.root.context, R.color.youtube_red)
                )
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
                binding.profilePin.imageTintList = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(
                        binding.root.context,
                        if (pinned) R.color.lime_primary else R.color.app_text_muted,
                    )
                )
                binding.profilePin.alpha = if (pinned) 1f else 0.55f
                binding.profilePin.setOnClickListener {
                    if (!testing) onPin(profile)
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
    }

    private enum class CatalogFilter { ALL, YOUTUBE, DISCORD, OPERATOR, OTHER }

    companion object {
        private const val TAG = "ProfilePicker"
        private const val TOPS_EXPANDED_KEY = "strategy_tops_expanded"
    }
}
