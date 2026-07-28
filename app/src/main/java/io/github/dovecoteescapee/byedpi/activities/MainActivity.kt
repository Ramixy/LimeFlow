package io.github.dovecoteescapee.byedpi.activities

import android.Manifest
import android.animation.ValueAnimator
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.res.ColorStateList
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.util.Log
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
import com.amurcanov.tgwsproxy.ProxyUiNavigation
import com.amurcanov.tgwsproxy.SettingsStore
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    private var lastPowerState: Boolean? = null

    companion object {
        private val TAG: String = MainActivity::class.java.simpleName

        private fun collectLogs(): String? =
            try {
                Runtime.getRuntime()
                    .exec("logcat *:D -d")
                    .inputStream.bufferedReader()
                    .use { it.readText() }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to collect logs", e)
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
                    Toast.makeText(
                        this@MainActivity,
                        R.string.logs_failed,
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    val uri = it.data?.data ?: run {
                        Log.e(TAG, "No data in result")
                        return@launch
                    }
                    contentResolver.openOutputStream(uri)?.use {
                        try {
                            it.write(logs.toByteArray())
                        } catch (e: IOException) {
                            Log.e(TAG, "Failed to save logs", e)
                        }
                    } ?: run {
                        Log.e(TAG, "Failed to open output stream")
                    }
                }
            }
        }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "Received intent: ${intent?.action}")

            if (intent == null) {
                Log.w(TAG, "Received null intent")
                return
            }

            val senderOrd = intent.getIntExtra(SENDER, -1)
            val sender = Sender.entries.getOrNull(senderOrd)
            if (sender == null) {
                Log.w(TAG, "Received intent with unknown sender: $senderOrd")
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

                else -> Log.w(TAG, "Unknown action: $action")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ensureUnifiedAppearanceDefaults()
        appliedAppearanceSignature = appearanceSignature()
        applyStoredTheme()
        applyLimeFlowPalette()
        super.onCreate(savedInstanceState)

        installAlt11Defaults()
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
            registerReceiver(receiver, intentFilter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(receiver, intentFilter)
        }
        receiverRegistered = true

        binding.statusButton.setOnClickListener {
            val (status, _) = appStatus
            when (status) {
                AppStatus.Halted -> {
                    beginStartingAnimation()
                    start()
                }
                AppStatus.Running -> stop()
            }
        }
        binding.statusButtonCard.setOnClickListener { binding.statusButton.performClick() }
        binding.strategyButton.setOnClickListener { openProfiles() }
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleProxyUiIntent(intent)
    }

    private fun handleProxyUiIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(ProxyUiNavigation.EXTRA_OPEN_PROXY_UI, false) == true) {
            intent.removeExtra(ProxyUiNavigation.EXTRA_OPEN_PROXY_UI)
            openTgWsProxy()
        }
    }

    private fun installAlt11Defaults() {
        val preferences = getPreferences()
        if (preferences.getInt("limeflow_engine_version", 0) >= 7) return

        preferences.edit()
            .putString("byedpi_mode", "vpn")
            .putBoolean("byedpi_enable_cmd_settings", true)
            .putBoolean("ipv6_enable", true)
            .putBoolean("alt11_defaults_installed", true)
            .putInt("limeflow_engine_version", 7)
            .apply()
        FlowsealProfiles.select(preferences, FlowsealProfiles.selected(preferences))
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
        binding.strategyButtonText.text = getString(
            R.string.profile_summary,
            profile.name,
            profile.method,
        )
        updateStatus()
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
        val excludedNow =
            preferences.getString(
                AppFilterActivity.FILTER_MODE,
                AppFilterActivity.MODE_ALL,
            ) == AppFilterActivity.MODE_EXCLUDE &&
                AppFilterActivity.TELEGRAM_PACKAGES.any { it in packages }

        if (excludedNow) {
            packages.removeAll(AppFilterActivity.TELEGRAM_PACKAGES)
        } else {
            packages.addAll(AppFilterActivity.TELEGRAM_PACKAGES)
        }
        preferences.edit()
            .putString(
                AppFilterActivity.FILTER_MODE,
                if (excludedNow && packages.isEmpty()) {
                    AppFilterActivity.MODE_ALL
                } else {
                    AppFilterActivity.MODE_EXCLUDE
                },
            )
            .putStringSet(AppFilterActivity.FILTER_PACKAGES, packages)
            .apply()
        updateDashboardInfo()
        Toast.makeText(
            this,
            if (excludedNow) {
                R.string.flow_telegram_now_included
            } else {
                R.string.flow_telegram_now_excluded
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
        )
        paletteCards.forEach { (card, value) ->
            card.strokeWidth = if (selectedPalette == value) {
                (4 * resources.displayMetrics.density).toInt()
            } else {
                0
            }
            card.setOnClickListener {
                preferences.edit()
                    .putBoolean("app_dynamic_colors", false)
                    .putString("app_palette", value)
                    .apply()
                restartAfterAppearanceChange(dialog) {
                    appearanceStore.saveDynamicColor(false)
                    appearanceStore.saveThemePalette(value)
                }
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
        val (status, _) = appStatus
        if (status == AppStatus.Halted) {
            startActivity(Intent(this, SettingsActivity::class.java))
        } else {
            Toast.makeText(this, R.string.settings_unavailable, Toast.LENGTH_SHORT).show()
        }
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
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val (status, _) = appStatus

        return when (item.itemId) {
            R.id.action_settings -> {
                if (status == AppStatus.Halted) {
                    val intent = Intent(this, SettingsActivity::class.java)
                    startActivity(intent)
                } else {
                    Toast.makeText(this, R.string.settings_unavailable, Toast.LENGTH_SHORT)
                        .show()
                }
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

            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun start() {
        when (getPreferences().mode()) {
            Mode.VPN -> {
                val intentPrepare = VpnService.prepare(this)
                if (intentPrepare != null) {
                    vpnRegister.launch(intentPrepare)
                } else {
                    ServiceManager.start(this, Mode.VPN)
                }
            }

            Mode.Proxy -> ServiceManager.start(this, Mode.Proxy)
        }
    }

    private fun stop() {
        ServiceManager.stop(this)
    }

    private fun updateStatus() {
        val (status, mode) = appStatus

        Log.i(TAG, "Updating status: $status, $mode")

        val preferences = getPreferences()
        val proxyIp = preferences.getStringNotNull("byedpi_proxy_ip", "127.0.0.1")
        val proxyPort = preferences.getStringNotNull("byedpi_proxy_port", "1080")
        binding.proxyAddress.text = getString(R.string.proxy_address, proxyIp, proxyPort)
        updateDashboardInfo()
        finishStartingAnimation(status == AppStatus.Running)

        when (status) {
            AppStatus.Halted -> {
                when (preferences.mode()) {
                    Mode.VPN -> {
                        binding.statusText.setText(R.string.vpn_disconnected)
                        binding.statusButton.setText(R.string.vpn_connect)
                    }

                    Mode.Proxy -> {
                        binding.statusText.setText(R.string.proxy_down)
                        binding.statusButton.setText(R.string.proxy_start)
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
                }
                binding.statusButton.isEnabled = true
            }
        }
        updatePowerColor(status == AppStatus.Running)
    }

    private fun updateDashboardInfo() {
        val preferences = getPreferences()
        val mode = preferences.mode()
        binding.flowModeValue.setText(
            if (mode == Mode.VPN) R.string.flow_mode_vpn else R.string.flow_mode_local
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
    }

    private fun finishStartingAnimation(success: Boolean) {
        if (!isStartingVisual) return
        isStartingVisual = false
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
