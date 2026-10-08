package io.github.dovecoteescapee.byedpi.activities

import android.Manifest
import android.animation.ValueAnimator
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.res.ColorStateList
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import androidx.appcompat.app.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.lifecycleScope
import io.github.dovecoteescapee.byedpi.R
import io.github.dovecoteescapee.byedpi.data.*
import io.github.dovecoteescapee.byedpi.databinding.ActivityMainBinding
import io.github.dovecoteescapee.byedpi.databinding.DialogAppearanceBinding
import io.github.dovecoteescapee.byedpi.services.ServiceManager
import io.github.dovecoteescapee.byedpi.services.appStatus
import io.github.dovecoteescapee.byedpi.utility.*
import io.github.dovecoteescapee.byedpi.widget.VpnWidgets
import com.amurcanov.tgwsproxy.ProxyUiNavigation
import com.amurcanov.tgwsproxy.SettingsStore
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val appearanceStore by lazy { SettingsStore(applicationContext) }
    private var appliedAppearanceSignature = ""
    private var receiverRegistered = false
    private var powerAnimator: ValueAnimator? = null
    private var logoAnimator: ObjectAnimator? = null
    private var orbitAnimator: ObjectAnimator? = null
    private var startingPulseAnimator: ValueAnimator? = null
    private var startingLogoAnimator: ObjectAnimator? = null
    private var isStartingVisual = false
    private var startingWatchdog: Job? = null
    private var lastPowerState: Boolean? = null
    private var tickerJob: Job? = null

    companion object {
        private val TAG: String = MainActivity::class.java.simpleName

        private fun collectLogs(): String? =
            try {
                Runtime.getRuntime()
                    .exec("logcat *:D -d")
                    .inputStream.bufferedReader()
                    .use { it.readText() }
            } catch (e: Exception) {
                AppLog.e(TAG, "Failed to collect logs", e)
                null
            }
    }

    private val vpnRegister =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) {
                ServiceManager.start(this, Mode.VPN)
            } else {
                Toast.makeText(this, R.string.vpn_permission_denied, Toast.LENGTH_SHORT).show()
                updateStatus()
            }
        }
    private val logsRegister =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            lifecycleScope.launch(Dispatchers.IO) {
                val logs = collectLogs()

                if (logs == null) {
                    AppLog.e(TAG, "Failed to collect logs")
                    // Toast.show() must run on a Looper thread; this block is on IO.
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@MainActivity,
                            R.string.logs_failed,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } else {
                    val uri = it.data?.data ?: run {
                        AppLog.e(TAG, "No data in result")
                        return@launch
                    }
                    contentResolver.openOutputStream(uri)?.use {
                        try {
                            it.write(logs.toByteArray())
                        } catch (e: IOException) {
                            AppLog.e(TAG, "Failed to save logs", e)
                        }
                    } ?: run {
                        AppLog.e(TAG, "Failed to open output stream")
                    }
                }
            }
        }

    private val startWatchdog = Runnable {
        if (isStartingVisual) updateStatus()
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            AppLog.d(TAG, "Received intent: ${intent?.action}")

            if (intent == null) {
                AppLog.w(TAG, "Received null intent")
                return
            }

            val senderOrd = intent.getIntExtra(SENDER, -1)
            val sender = Sender.entries.getOrNull(senderOrd)
            if (sender == null) {
                AppLog.w(TAG, "Received intent with unknown sender: $senderOrd")
                return
            }

            when (val action = intent.action) {
                STARTED_BROADCAST,
                STOPPED_BROADCAST -> updateStatus()

                FAILED_BROADCAST -> {
                    Toast.makeText(
                        context,
                        getString(R.string.failed_to_start, sender.name),
                        Toast.LENGTH_SHORT,
                    ).show()
                    updateStatus()
                }

                else -> AppLog.w(TAG, "Unknown action: $action")
            }
        }
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(newBase.withTextScale())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppLog.verbose = getPreferences().getBoolean("developer_mode", false)
        ensureUnifiedAppearanceDefaults()
        appliedAppearanceSignature = appearanceSignature()
        applyStoredTheme()
        applyLimeFlowPalette()
        super.onCreate(savedInstanceState)

        installEngineDefaults()
        AppFilterActivity.ensureTelegramExcludedByDefault(getPreferences())

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        animateScreenEntry()

        val intentFilter = IntentFilter().apply {
            addAction(STARTED_BROADCAST)
            addAction(STOPPED_BROADCAST)
            addAction(FAILED_BROADCAST)
        }

        @SuppressLint("UnspecifiedRegisterReceiverFlag")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Status broadcasts come only from this app's services; an exported
            // receiver would let other apps spoof them.
            registerReceiver(receiver, intentFilter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, intentFilter)
        }
        receiverRegistered = true

        binding.statusButton.setOnClickListener {
            // Ignore taps while a start/stop transition is in flight: a second
            // START would tear down the proxy that is still connecting.
            if (isStartingVisual) return@setOnClickListener
            maybeHaptic()
            val (status, _) = appStatus
            when (status) {
                AppStatus.Halted -> {
                    beginStartingAnimation()
                    start()
                    binding.statusButton.removeCallbacks(startWatchdog)
                    binding.statusButton.postDelayed(startWatchdog, 12_000L)
                }
                AppStatus.Running -> stop()
            }
        }
        binding.statusButtonCard.setOnClickListener { binding.statusButton.performClick() }
        binding.strategyButton.setOnClickListener { openProfiles() }
        binding.strategyButton.setOnLongClickListener {
            copyCurrentStrategy()
            true
        }
        binding.flowModeAction.setOnClickListener { showModePicker() }
        binding.flowAppsAction.setOnClickListener { showAppModePicker() }
        binding.flowTelegramAction.setOnClickListener { toggleTelegramFiltering() }
        binding.limeflowTab.setOnClickListener { binding.statusButton.requestFocus() }
        binding.tgWsProxyTab.setOnClickListener { openTgWsProxy() }
        binding.themeIconButton.setOnClickListener {
            it.animate().rotationBy(90f).setDuration(260).start()
            showPalettePicker()
        }
        binding.themeIconButton.setOnLongClickListener {
            showThemePicker()
            true
        }
        binding.settingsIconButton.setOnClickListener { openSettings() }
        binding.limeflowHomeNav.setOnClickListener { binding.statusButton.requestFocus() }
        binding.appearanceNav.setOnClickListener { showPalettePicker() }
        binding.settingsNav.setOnClickListener { openSettings() }
        handleProxyUiIntent(intent)
        handleLimeFlowDeeplink(intent)
        VpnWidgets.updateAll(this)

        binding.trafficCard.setOnClickListener { startActivity(Intent(this, TrafficStatsActivity::class.java)) }
        binding.checkConnectionCard.setOnClickListener { runConnectionCheck() }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        // The intro covers the battery prompt on first run, so the standalone
        // dialog only fires for users who skipped onboarding.
        if (getPreferences().getBoolean(OnboardingActivity.ONBOARDING_DONE_KEY, false)) {
            maybePromptBatteryExemption()
        } else {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }
        checkForUpdate()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleProxyUiIntent(intent)
        handleLimeFlowDeeplink(intent)
    }

    private fun handleProxyUiIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(ProxyUiNavigation.EXTRA_OPEN_PROXY_UI, false) == true) {
            intent.removeExtra(ProxyUiNavigation.EXTRA_OPEN_PROXY_UI)
            openTgWsProxy()
        }
    }

    /*
     * limeflow://connect | disconnect | toggle | status — automation entry
     * point for Tasker/MacroDroid/HTTP shortcuts; no permission required.
     */
    private fun handleLimeFlowDeeplink(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != DEEPLINK_SCHEME) return
        // Consume the link: recreate() (theme/palette change, rotation) and
        // relaunching from Recents redeliver the same intent, which used to
        // fire "toggle" again and switch the connection off by itself.
        intent.data = null
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        when (data.host) {
            "connect" -> {
                val (status, _) = appStatus
                if (status == AppStatus.Halted) {
                    beginStartingAnimation()
                    start()
                }
            }

            "disconnect" -> {
                val (status, _) = appStatus
                if (status == AppStatus.Running) stop()
            }

            "toggle" -> {
                val (status, _) = appStatus
                if (status == AppStatus.Running) stop() else {
                    beginStartingAnimation()
                    start()
                }
            }

            "status" -> {
                val (status, _) = appStatus
                Toast.makeText(
                    this,
                    if (status == AppStatus.Running) R.string.tile_active else R.string.tile_inactive,
                    Toast.LENGTH_SHORT,
                ).show()
            }

            StrategyShare.HOST -> offerStrategyImport(data.getQueryParameter(StrategyShare.DATA_PARAM))
        }
    }

    private fun offerStrategyImport(data: String?) {
        val imported = StrategyShare.parse(data) ?: run {
            Toast.makeText(this, R.string.strategy_import_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.strategy_import_title, imported.name))
            .setMessage(R.string.strategy_import_message)
            .setNegativeButton(R.string.custom_strategy_cancel, null)
            .setPositiveButton(R.string.strategy_import_apply) { _, _ ->
                val preferences = getPreferences()
                val saved = FlowsealProfiles.saveCustom(
                    preferences,
                    null,
                    imported.name,
                    imported.arguments,
                )
                FlowsealProfiles.select(preferences, saved)
                binding.strategyButtonText.text = getString(
                    R.string.profile_summary,
                    saved.name,
                    saved.method,
                )
                Toast.makeText(this, R.string.strategy_import_done, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    /*
     * Re-applies the stored profile so an upgrade picks up rewritten strategy arguments:
     * byedpi_cmd_args is a flattened copy of them, not a reference. Bump the version
     * whenever the catalog's argument strings change.
     */
    private fun installEngineDefaults() {
        val preferences = getPreferences()
        if (preferences.getInt("limeflow_engine_version", 0) >= 13) return

        // FlowsealProfiles.select() force-enables command-line mode; on an
        // upgrade the user's explicit UI-vs-CMD choice must survive the
        // re-apply. On a fresh install there is no choice yet, keep CMD.
        val isUpgrade = preferences.contains("byedpi_enable_cmd_settings")
        val userCmdSetting = preferences.getBoolean("byedpi_enable_cmd_settings", true)

        preferences.edit()
            .putString("byedpi_mode", "vpn")
            .putBoolean("byedpi_enable_cmd_settings", true)
            .putBoolean("ipv6_enable", true)
            .putInt("limeflow_engine_version", 13)
            .apply()
        FlowsealProfiles.select(preferences, FlowsealProfiles.selected(preferences))
        if (isUpgrade) {
            preferences.edit()
                .putBoolean("byedpi_enable_cmd_settings", userCmdSetting)
                .apply()
        }
        BypassHosts.writeHostFile(this, preferences)
    }

    private fun ensureUnifiedAppearanceDefaults() {
        val preferences = getPreferences()
        if (preferences.getBoolean("unified_appearance_migrated_v3", false)) return
        preferences.edit()
            .putString("app_theme", "system")
            .putBoolean("app_dynamic_colors", true)
            .putBoolean("unified_appearance_migrated_v3", true)
            .commit()
    }

    override fun onResume() {
        super.onResume()
        if (appliedAppearanceSignature != appearanceSignature()) {
            ActivityCompat.recreate(this)
            return
        }
        val profile = FlowsealProfiles.selected(getPreferences())
        val score = StrategyMemory.scoreFor(getPreferences(), profile.id)
        binding.strategyButtonText.text = if (score != null) {
            getString(R.string.profile_summary_score, profile.name, score.label)
        } else {
            getString(R.string.profile_summary, profile.name, profile.method)
        }
        updateStatus()
        maybeOfferNetworkStrategy()
        startDashboardTicker()
    }

    override fun onPause() {
        tickerJob?.cancel()
        tickerJob = null
        super.onPause()
    }

    /* Session stats and the engine memory line refresh once a second while
     * the dashboard is on screen. */
    private fun maybeHaptic() {
        if (getPreferences().getBoolean("haptic_feedback", false)) {
            binding.statusButton.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }

    private fun startDashboardTicker() {
        if (tickerJob?.isActive == true) return
        tickerJob = lifecycleScope.launch {
            while (isActive) {
                updateTrafficLine()
                updateMemoryLine()
                delay(1_000)
            }
        }
    }

    private fun updateTrafficLine() {
        val snapshot = TrafficStatsStore.snapshot(this)
        binding.trafficValue.text = getString(
            R.string.traffic_line_value,
            formatTraffic(snapshot.todayTx + snapshot.todayRx),
            formatTraffic(snapshot.totalTx + snapshot.totalRx),
        )
    }

    private fun updateMemoryLine() {
        val enabled = getPreferences().getBoolean("show_engine_memory", false)
        val running = appStatus.first == AppStatus.Running
        if (!enabled || !running) {
            binding.memoryValue.visibility = View.GONE
            return
        }
        val manager = getSystemService(ACTIVITY_SERVICE) as? ActivityManager
        val pssMb = manager
            ?.getProcessMemoryInfo(intArrayOf(Process.myPid()))
            ?.firstOrNull()
            ?.totalPss
            ?.takeIf { it > 0 } ?: run {
            binding.memoryValue.visibility = View.GONE
            return
        }
        val (label, color) = when {
            pssMb < 150 -> R.string.memory_level_ok to R.color.lime_connected
            pssMb < 300 -> R.string.memory_level_elevated to android.R.color.holo_orange_light
            else -> R.string.memory_level_high to R.color.app_error
        }
        binding.memoryValue.visibility = View.VISIBLE
        binding.memoryValue.text = getString(R.string.memory_line, pssMb, getString(label))
        binding.memoryValue.setTextColor(ContextCompat.getColor(this, color))
    }

    private fun runConnectionCheck() {
        binding.checkResult.setText(R.string.check_running)
        lifecycleScope.launch {
            val result = ConnectionTester.test(this@MainActivity)
            binding.checkResult.text = when (result) {
                is ConnectionTester.Result.Reachable ->
                    getString(R.string.check_ok, result.latencyMs)
                is ConnectionTester.Result.CaptivePortal ->
                    getString(R.string.check_captive, result.latencyMs)
                ConnectionTester.Result.Unreachable -> getString(R.string.check_unreachable)
                ConnectionTester.Result.NoNetwork -> getString(R.string.check_no_network)
            }
        }
    }

    private fun checkForUpdate() {
        lifecycleScope.launch {
            val release = runCatching { AppUpdateChecker.checkIfDue(this@MainActivity) }.getOrNull()
                ?: return@launch
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(getString(R.string.update_available_title, release.version))
                .setMessage(R.string.update_available_message)
                .setNegativeButton(R.string.update_later, null)
                .setPositiveButton(R.string.update_download) { _, _ ->
                    runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.apkUrl)))
                    }
                }
                .show()
        }
    }

    private fun appearanceSignature(): String = getPreferences().run {
        listOf(
            getString("app_theme", "system"),
            getBoolean("app_dynamic_colors", true),
            getString("app_palette", "limeflow"),
        ).joinToString("|")
    }

    private fun openProfiles() {
        val (status, _) = appStatus
        if (status == AppStatus.Running) {
            Toast.makeText(this, R.string.stop_before_strategy, Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(Intent(this, ProfilePickerActivity::class.java))
    }

    private fun copyCurrentStrategy() {
        val profile = FlowsealProfiles.selected(getPreferences())
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(profile.name, profile.arguments))
        Toast.makeText(this, R.string.strategy_copied, Toast.LENGTH_SHORT).show()
    }

    private fun maybeOfferNetworkStrategy() {
        val preferences = getPreferences()
        // With automatic switching on, the service applies the remembered
        // strategy itself; the manual dialog would only fight with it.
        if (preferences.getBoolean("auto_switch_network", false)) return
        // Per-network strategies are a byedpi feature.
        if (ServiceManager.isZapretEngine(this)) return
        val type = if (StrategyMemory.onWifi(this)) "wifi" else "mobile"
        val last = preferences.getString(StrategyMemory.NETWORK_HINT_KEY, null)
        if (last == null) {
            preferences.edit().putString(StrategyMemory.NETWORK_HINT_KEY, type).apply()
            return
        }
        if (last == type) return
        preferences.edit().putString(StrategyMemory.NETWORK_HINT_KEY, type).apply()
        val rememberedId = StrategyMemory.rememberedForCurrentNetwork(this, preferences) ?: return
        val current = FlowsealProfiles.selected(preferences)
        if (rememberedId == current.id) return
        val remembered = FlowsealProfiles.catalog(preferences)
            .firstOrNull { it.id == rememberedId } ?: return
        AlertDialog.Builder(this)
            .setTitle(R.string.network_strategy_title)
            .setMessage(
                getString(
                    R.string.network_strategy_message,
                    StrategyMemory.networkLabel(this),
                    remembered.name,
                )
            )
            .setNegativeButton(R.string.custom_strategy_cancel, null)
            .setPositiveButton(R.string.network_strategy_switch) { _, _ ->
                FlowsealProfiles.select(preferences, remembered)
                binding.strategyButtonText.text = getString(
                    R.string.profile_summary,
                    remembered.name,
                    remembered.method,
                )
            }
            .show()
    }

    private fun maybePromptBatteryExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val preferences = getPreferences()
        if (preferences.getBoolean(StrategyMemory.BATTERY_PROMPTED_KEY, false)) return
        val power = getSystemService(PowerManager::class.java) ?: return
        if (power.isIgnoringBatteryOptimizations(packageName)) {
            preferences.edit().putBoolean(StrategyMemory.BATTERY_PROMPTED_KEY, true).apply()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.battery_prompt_title)
            .setMessage(R.string.battery_prompt_message)
            .setNegativeButton(R.string.battery_prompt_later) { _, _ ->
                preferences.edit().putBoolean(StrategyMemory.BATTERY_PROMPTED_KEY, true).apply()
            }
            .setPositiveButton(R.string.battery_prompt_allow) { _, _ ->
                preferences.edit().putBoolean(StrategyMemory.BATTERY_PROMPTED_KEY, true).apply()
                runCatching {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            .setData(Uri.parse("package:$packageName"))
                    )
                }
            }
            .show()
    }

    private fun showModePicker() {
        val (status, _) = appStatus
        if (status == AppStatus.Running) {
            Toast.makeText(this, R.string.settings_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        val preferences = getPreferences()
        val values = arrayOf("vpn", "proxy")
        val labels = arrayOf(
            getString(R.string.settings_mode_vpn),
            getString(R.string.settings_mode_proxy),
        )
        val selected = if (preferences.mode() == Mode.Proxy) 1 else 0
        AlertDialog.Builder(this)
            .setTitle(R.string.flow_mode_dialog)
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                preferences.edit().putString("byedpi_mode", values[which]).apply()
                updateDashboardInfo()
                dialog.dismiss()
            }
            .show()
    }

    private fun showAppModePicker() {
        val preferences = getPreferences()
        val values = arrayOf(
            AppFilterActivity.MODE_ALL,
            AppFilterActivity.MODE_EXCLUDE,
            AppFilterActivity.MODE_INCLUDE,
        )
        val labels = arrayOf(
            getString(R.string.app_filter_all),
            getString(R.string.app_filter_exclude),
            getString(R.string.app_filter_include),
        )
        val selected = values.indexOf(
            preferences.getString(AppFilterActivity.FILTER_MODE, AppFilterActivity.MODE_ALL)
        ).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.flow_apps_dialog)
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                preferences.edit().putString(AppFilterActivity.FILTER_MODE, values[which]).apply()
                updateDashboardInfo()
                dialog.dismiss()
            }
            .setNeutralButton(R.string.app_filter_title) { _, _ ->
                startActivity(Intent(this, AppFilterActivity::class.java))
            }
            .show()
    }

    private fun toggleTelegramFiltering() {
        val preferences = getPreferences()
        val packages = preferences.getStringSet(
            AppFilterActivity.FILTER_PACKAGES,
            emptySet(),
        ).orEmpty().toMutableSet()
        val filterMode = preferences.getString(
            AppFilterActivity.FILTER_MODE,
            AppFilterActivity.MODE_ALL,
        ) ?: AppFilterActivity.MODE_ALL
        val telegramIncluded = when (filterMode) {
            AppFilterActivity.MODE_EXCLUDE ->
                AppFilterActivity.TELEGRAM_PACKAGES.none { it in packages }
            AppFilterActivity.MODE_INCLUDE ->
                AppFilterActivity.TELEGRAM_PACKAGES.any { it in packages }
            else -> true
        }

        // Toggle Telegram without discarding the semantics of the other
        // filtered packages (an INCLUDE list must not become an EXCLUDE list).
        if (telegramIncluded) {
            if (filterMode == AppFilterActivity.MODE_INCLUDE) {
                packages.removeAll(AppFilterActivity.TELEGRAM_PACKAGES)
                preferences.edit()
                    .putString(
                        AppFilterActivity.FILTER_MODE,
                        if (packages.isEmpty()) {
                            AppFilterActivity.MODE_ALL
                        } else {
                            AppFilterActivity.MODE_INCLUDE
                        },
                    )
                    .putStringSet(AppFilterActivity.FILTER_PACKAGES, packages)
                    .apply()
            } else {
                packages.addAll(AppFilterActivity.TELEGRAM_PACKAGES)
                preferences.edit()
                    .putString(AppFilterActivity.FILTER_MODE, AppFilterActivity.MODE_EXCLUDE)
                    .putStringSet(AppFilterActivity.FILTER_PACKAGES, packages)
                    .apply()
            }
        } else {
            if (filterMode == AppFilterActivity.MODE_INCLUDE) {
                packages.addAll(AppFilterActivity.TELEGRAM_PACKAGES)
                preferences.edit()
                    .putString(AppFilterActivity.FILTER_MODE, AppFilterActivity.MODE_INCLUDE)
                    .putStringSet(AppFilterActivity.FILTER_PACKAGES, packages)
                    .apply()
            } else {
                packages.removeAll(AppFilterActivity.TELEGRAM_PACKAGES)
                preferences.edit()
                    .putString(
                        AppFilterActivity.FILTER_MODE,
                        if (packages.isEmpty()) {
                            AppFilterActivity.MODE_ALL
                        } else {
                            AppFilterActivity.MODE_EXCLUDE
                        },
                    )
                    .putStringSet(AppFilterActivity.FILTER_PACKAGES, packages)
                    .apply()
            }
        }
        updateDashboardInfo()
        Toast.makeText(
            this,
            if (telegramIncluded) {
                R.string.flow_telegram_now_excluded
            } else {
                R.string.flow_telegram_now_included
            },
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun openTgWsProxy() {
        startActivity(
            Intent(this, IntegratedProxyActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        )
        overridePendingTransition(R.anim.proxy_enter, R.anim.limeflow_exit)
    }

    private fun showThemePicker() {
        val values = arrayOf("system", "light", "dark")
        val labels = arrayOf(
            getString(R.string.theme_system),
            getString(R.string.theme_light),
            getString(R.string.theme_dark),
        )
        val current = getPreferences().getString("app_theme", "system") ?: "system"
        AlertDialog.Builder(this)
            .setTitle(R.string.theme)
            .setSingleChoiceItems(labels, values.indexOf(current).coerceAtLeast(0)) { dialog, which ->
                getPreferences().edit().putString("app_theme", values[which]).apply()
                dialog.dismiss()
                applyStoredTheme()
            }
            .show()
    }

    private fun showPalettePicker() {
        val preferences = getPreferences()
        val appearance = DialogAppearanceBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(appearance.root)
            .create()

        val selectedTheme = preferences.getString("app_theme", "system") ?: "system"
        val primary = MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorPrimary,
            ContextCompat.getColor(this, R.color.lime_primary),
        )
        val onPrimary = MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorOnPrimary,
            ContextCompat.getColor(this, R.color.white),
        )
        val surface = MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorSurface,
            ContextCompat.getColor(this, R.color.app_surface),
        )
        val text = MaterialColors.getColor(
            this,
            android.R.attr.textColorPrimary,
            ContextCompat.getColor(this, R.color.app_text),
        )
        listOf(
            appearance.appearanceSystem to "system",
            appearance.appearanceLight to "light",
            appearance.appearanceDark to "dark",
        ).forEach { (button, value) ->
            val selected = selectedTheme == value
            button.backgroundTintList = ColorStateList.valueOf(if (selected) primary else surface)
            button.setTextColor(if (selected) onPrimary else text)
            button.iconTint = ColorStateList.valueOf(if (selected) onPrimary else primary)
            button.strokeColor = ColorStateList.valueOf(primary)
            button.strokeWidth = if (selected) 0 else resources.displayMetrics.density.toInt()
            button.setOnClickListener {
                preferences.edit().putString("app_theme", value).apply()
                lifecycleScope.launch {
                    appearanceStore.saveThemeMode(value)
                    dialog.dismiss()
                    AppCompatDelegate.setDefaultNightMode(
                        when (value) {
                            "light" -> AppCompatDelegate.MODE_NIGHT_NO
                            "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                        }
                    )
                }
            }
        }

        val supportsDynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        val dynamic = preferences.getBoolean("app_dynamic_colors", true) && supportsDynamic
        appearance.appearanceDynamic.isEnabled = supportsDynamic
        appearance.appearanceDynamic.isChecked = dynamic
        appearance.appearancePalettes.visibility = if (dynamic) View.GONE else View.VISIBLE
        appearance.appearanceDynamic.setOnCheckedChangeListener { _, checked ->
            preferences.edit()
                .putBoolean("app_dynamic_colors", checked)
                .apply()
            restartAfterAppearanceChange(dialog) {
                appearanceStore.saveDynamicColor(checked)
            }
        }

        val selectedPalette = preferences.getString("app_palette", "limeflow") ?: "limeflow"
        val paletteCards = listOf(
            appearance.paletteLimeflow to "limeflow",
            appearance.paletteIndigo to "indigo",
            appearance.paletteForest to "forest",
            appearance.paletteEspresso to "espresso",
            appearance.paletteAmoled to "amoled",
        )
        paletteCards.forEach { (card, value) ->
            card.strokeWidth = if (selectedPalette == value) {
                (4 * resources.displayMetrics.density).toInt()
            } else {
                0
            }
            card.setOnClickListener {
                // AMOLED only makes sense with the dark base theme: switching
                // to it forces dark mode as well.
                if (value == "amoled") {
                    preferences.edit().putString("app_theme", "dark").apply()
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                }
                preferences.edit()
                    .putBoolean("app_dynamic_colors", false)
                    .putString("app_palette", value)
                    .apply()
                restartAfterAppearanceChange(dialog) {
                    appearanceStore.saveDynamicColor(false)
                    appearanceStore.saveThemePalette(value)
                    if (value == "amoled") appearanceStore.saveThemeMode("dark")
                }
            }
        }

        // Vibration feedback and text size live in the same appearance hub.
        appearance.appearanceHaptic.isChecked = preferences.getBoolean("haptic_feedback", false)
        appearance.appearanceHaptic.setOnCheckedChangeListener { _, checked ->
            preferences.edit().putBoolean("haptic_feedback", checked).apply()
        }
        val selectedScale = preferences.getString("text_scale", "normal") ?: "normal"
        appearance.textScaleGroup.check(
            when (selectedScale) {
                "large" -> R.id.text_large
                "small" -> R.id.text_small
                else -> R.id.text_normal
            }
        )
        appearance.textScaleGroup.addOnButtonCheckedListener { _, _, checked ->
            if (!checked) return@addOnButtonCheckedListener
            val scale = when (appearance.textScaleGroup.checkedButtonId) {
                R.id.text_large -> "large"
                R.id.text_small -> "small"
                else -> "normal"
            }
            if (scale != selectedScale) {
                preferences.edit().putString("text_scale", scale).apply()
                restartAfterAppearanceChange(dialog) { }
            }
        }
        dialog.show()
    }

    private fun restartAfterAppearanceChange(
        dialog: AlertDialog,
        persist: suspend () -> Unit,
    ) {
        lifecycleScope.launch {
            persist()
            dialog.dismiss()
            delay(120)
            if (!isFinishing && !isDestroyed) {
                ActivityCompat.recreate(this@MainActivity)
            }
        }
    }

    private fun openSettings() {
        // Settings are readable while connected; edits apply on reconnect and
        // the risky toggles are disabled inside Settings while running.
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun applyStoredTheme() {
        val mode = when (getPreferences().getString("app_theme", "system")) {
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            "dark" -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    override fun onDestroy() {
        powerAnimator?.cancel()
        logoAnimator?.cancel()
        orbitAnimator?.cancel()
        startingPulseAnimator?.cancel()
        startingLogoAnimator?.cancel()
        if (receiverRegistered) {
            runCatching { unregisterReceiver(receiver) }
            receiverRegistered = false
        }
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        // Экран журнала — инструмент режима разработчика, в обычной работе не нужен.
        menu?.findItem(R.id.action_logs)?.isVisible =
            getPreferences().getBoolean("developer_mode", false)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val (status, _) = appStatus

        return when (item.itemId) {
            R.id.action_settings -> {
                val intent = Intent(this, SettingsActivity::class.java)
                startActivity(intent)
                true
            }

            R.id.action_save_logs -> {
                val intent =
                    Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TITLE, "limeflow.log")
                    }

                logsRegister.launch(intent)
                true
            }

            R.id.action_logs -> {
                startActivity(Intent(this, LogsActivity::class.java))
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun start() {
        val preferences = getPreferences()
        when (ServiceManager.engineMode(this)) {
            Mode.Zapret -> lifecycleScope.launch {
                // su may be missing entirely; ask once and explain instead of
                // failing inside the service.
                val hasRoot = withContext(Dispatchers.IO) {
                    io.github.dovecoteescapee.byedpi.zapret.ZapretEngineService.hasRoot()
                }
                if (hasRoot) {
                    ServiceManager.start(this@MainActivity, Mode.Zapret)
                    updateStatus()
                } else {
                    // A stuck zapret selection must not leave the user with a
                    // permanently failing button: fall back to our engine.
                    preferences.edit()
                        .putString(ServiceManager.ENGINE_MODE_KEY, ServiceManager.ENGINE_BYEDPI)
                        .apply()
                    Toast.makeText(
                        this@MainActivity,
                        R.string.zapret_root_missing,
                        Toast.LENGTH_LONG,
                    ).show()
                    updateDashboardInfo()
                    start()
                }
            }

            Mode.VPN -> {
                StrategyMemory.rememberForCurrentNetwork(
                    this,
                    preferences,
                    FlowsealProfiles.selected(preferences).id,
                )
                val intentPrepare = VpnService.prepare(this)
                if (intentPrepare != null) {
                    vpnRegister.launch(intentPrepare)
                } else {
                    ServiceManager.start(this, Mode.VPN)
                }
            }

            Mode.Proxy -> {
                StrategyMemory.rememberForCurrentNetwork(
                    this,
                    preferences,
                    FlowsealProfiles.selected(preferences).id,
                )
                ServiceManager.start(this, Mode.Proxy)
            }
        }
    }

    private fun stop() {
        ServiceManager.stop(this)
    }

    private fun updateStatus() {
        val (status, mode) = appStatus

        AppLog.i(TAG, "Updating status: $status, $mode")

        val preferences = getPreferences()
        val proxyIp = preferences.getStringNotNull("byedpi_proxy_ip", "127.0.0.1")
        val proxyPort = preferences.getStringNotNull("byedpi_proxy_port", "1080")
        binding.proxyAddress.text = getString(R.string.proxy_address, proxyIp, proxyPort)
        updateDashboardInfo()
        finishStartingAnimation(status == AppStatus.Running)

        when (status) {
            AppStatus.Halted -> {
                when (ServiceManager.engineMode(this)) {
                    Mode.Zapret -> {
                        binding.statusText.setText(R.string.zapret_state_halted)
                        binding.statusButton.setText(R.string.zapret_start)
                    }

                    else -> when (preferences.mode()) {
                        Mode.VPN -> {
                            binding.statusText.setText(R.string.vpn_disconnected)
                            binding.statusButton.setText(R.string.vpn_connect)
                        }

                        Mode.Proxy -> {
                            binding.statusText.setText(R.string.proxy_down)
                            binding.statusButton.setText(R.string.proxy_start)
                        }

                        Mode.Zapret -> Unit
                    }
                }
                binding.statusButton.isEnabled = true
            }

            AppStatus.Running -> {
                when (mode) {
                    Mode.VPN -> {
                        binding.statusText.setText(R.string.vpn_connected)
                        binding.statusButton.setText(R.string.vpn_disconnect)
                    }

                    Mode.Proxy -> {
                        binding.statusText.setText(R.string.proxy_up)
                        binding.statusButton.setText(R.string.proxy_stop)
                    }

                    Mode.Zapret -> {
                        binding.statusText.setText(R.string.zapret_state_running)
                        binding.statusButton.setText(R.string.zapret_stop)
                    }
                }
                binding.statusButton.isEnabled = true
            }
        }
        updatePowerColor(status == AppStatus.Running)
    }

    private fun updateDashboardInfo() {
        val preferences = getPreferences()
        val engineZapret = ServiceManager.isZapretEngine(this)
        val mode = preferences.mode()
        binding.flowModeValue.setText(
            when {
                engineZapret -> R.string.flow_mode_zapret
                mode == Mode.VPN -> R.string.flow_mode_vpn
                else -> R.string.flow_mode_local
            }
        )

        val filterMode = preferences.getString(
            AppFilterActivity.FILTER_MODE,
            AppFilterActivity.MODE_ALL,
        )
        val filteredPackages = preferences.getStringSet(
            AppFilterActivity.FILTER_PACKAGES,
            emptySet(),
        ).orEmpty()
        val telegramSelected =
            AppFilterActivity.TELEGRAM_PACKAGES.any { it in filteredPackages }
        binding.flowAppsValue.text = when (filterMode) {
            AppFilterActivity.MODE_EXCLUDE ->
                getString(R.string.flow_apps_excluded, filteredPackages.size)
            AppFilterActivity.MODE_INCLUDE ->
                getString(R.string.flow_apps_included, filteredPackages.size)
            else -> getString(R.string.flow_apps_all)
        }
        val telegramValue = when {
            mode == Mode.Proxy -> R.string.flow_telegram_separate
            filterMode == AppFilterActivity.MODE_EXCLUDE && telegramSelected ->
                R.string.flow_telegram_excluded
            filterMode == AppFilterActivity.MODE_INCLUDE && !telegramSelected ->
                R.string.flow_telegram_excluded
            else -> R.string.flow_telegram_included
        }
        binding.flowTelegramValue.setText(telegramValue)
    }

    private fun beginStartingAnimation() {
        if (isStartingVisual) return
        isStartingVisual = true
        binding.statusText.setText(R.string.flow_connecting)
        binding.powerProgress.visibility = View.VISIBLE
        binding.powerProgress.animate().alpha(1f).setDuration(240).start()

        startingPulseAnimator?.cancel()
        startingPulseAnimator = ValueAnimator.ofFloat(0.97f, 1.07f).apply {
            duration = 720
            interpolator = AccelerateDecelerateInterpolator()
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                val scale = it.animatedValue as Float
                binding.statusButtonCard.scaleX = scale
                binding.statusButtonCard.scaleY = scale
            }
            start()
        }
        startingLogoAnimator?.cancel()
        startingLogoAnimator = ObjectAnimator.ofFloat(
            binding.statusLogo,
            View.ROTATION,
            0f,
            360f,
        ).apply {
            duration = 1_300
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            start()
        }
        startOrbitAnimation(2_300)

        // Если ни один сервис не ответил статусом (движок умер до запуска
        // переднего сервиса), кнопка осталась бы в «Подключении» навсегда
        // и перестала бы реагировать — по таймауту синхронизируем с фактом.
        startingWatchdog?.cancel()
        startingWatchdog = lifecycleScope.launch {
            delay(15_000)
            if (isStartingVisual) {
                AppLog.w(TAG, "No status broadcast within 15s, resyncing UI")
                updateStatus()
            }
        }
    }

    private fun finishStartingAnimation(success: Boolean) {
        if (!isStartingVisual) return
        isStartingVisual = false
        startingWatchdog?.cancel()
        startingWatchdog = null
        startingPulseAnimator?.cancel()
        startingPulseAnimator = null
        startingLogoAnimator?.cancel()
        startingLogoAnimator = null
        binding.powerProgress.animate()
            .alpha(0f)
            .setDuration(260)
            .withEndAction { binding.powerProgress.visibility = View.INVISIBLE }
            .start()
        binding.statusButtonCard.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(300)
            .start()
        binding.statusLogo.animate()
            .rotation(0f)
            .scaleX(if (success) 1.18f else 1f)
            .scaleY(if (success) 1.18f else 1f)
            .setDuration(260)
            .withEndAction {
                binding.statusLogo.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(320)
                    .start()
            }
            .start()
    }

    private fun startOrbitAnimation(durationMs: Long) {
        if (orbitAnimator?.isRunning == true) return
        orbitAnimator = ObjectAnimator.ofFloat(
            binding.powerOrbit,
            View.ROTATION,
            binding.powerOrbit.rotation,
            binding.powerOrbit.rotation + 360f,
        ).apply {
            duration = durationMs
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun updatePowerColor(active: Boolean) {
        val stateChanged = lastPowerState != active

        val current = binding.statusButtonCard.cardBackgroundColor.defaultColor
        val target = if (active) {
            ContextCompat.getColor(this, R.color.lime_connected)
        } else {
            MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorPrimary)
        }
        powerAnimator?.cancel()

        if (stateChanged) {
            if (lastPowerState == null) {
                binding.statusButtonCard.setCardBackgroundColor(target)
            } else {
                val middle = ColorUtils.blendARGB(current, target, 0.5f)
                powerAnimator = ValueAnimator.ofArgb(current, middle, target).apply {
                    duration = 850
                    interpolator = AccelerateDecelerateInterpolator()
                    addUpdateListener {
                        binding.statusButtonCard.setCardBackgroundColor(it.animatedValue as Int)
                    }
                    start()
                }
                binding.statusButtonCard.animate()
                    .scaleX(1.06f)
                    .scaleY(1.06f)
                    .setDuration(260)
                    .withEndAction {
                        binding.statusButtonCard.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(320)
                            .start()
                    }
                    .start()
            }
        }

        if (active) {
            binding.powerOrbit.animate().alpha(0.9f).setDuration(350).start()
            startOrbitAnimation(5_000)
        } else if (!isStartingVisual) {
            orbitAnimator?.cancel()
            orbitAnimator = null
            binding.powerOrbit.animate()
                .alpha(0.45f)
                .rotation(0f)
                .setDuration(450)
                .start()
        }
        lastPowerState = active
    }

    private fun animateScreenEntry() {
        binding.root.alpha = 0f
        binding.root.animate().alpha(1f).setDuration(360).start()
        listOf(binding.heroBackground, binding.bottomActions).forEachIndexed { index, view ->
            view.alpha = 0f
            view.translationY = 34f
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(90L + index * 80L)
                .setDuration(420)
                .start()
        }
        logoAnimator = ObjectAnimator.ofFloat(binding.brandLogo, View.TRANSLATION_Y, 0f, -5f).apply {
            duration = 1_600
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }
}
