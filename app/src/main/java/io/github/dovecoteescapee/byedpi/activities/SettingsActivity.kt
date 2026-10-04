package io.github.dovecoteescapee.byedpi.activities

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.children
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.dovecoteescapee.byedpi.BuildConfig
import io.github.dovecoteescapee.byedpi.R
import io.github.dovecoteescapee.byedpi.data.AppStatus
import io.github.dovecoteescapee.byedpi.data.BypassHosts
import io.github.dovecoteescapee.byedpi.data.FlowsealProfiles
import io.github.dovecoteescapee.byedpi.data.TrafficStatsStore
import io.github.dovecoteescapee.byedpi.databinding.ActivitySettingsBinding
import io.github.dovecoteescapee.byedpi.fragments.ByeDpiCommandLineSettingsFragment
import io.github.dovecoteescapee.byedpi.fragments.ByeDpiUISettingsFragment
import io.github.dovecoteescapee.byedpi.services.ServiceManager
import io.github.dovecoteescapee.byedpi.services.appStatus
import io.github.dovecoteescapee.byedpi.zapret.ZapretEngineService
import io.github.dovecoteescapee.byedpi.utility.AppIcon
import io.github.dovecoteescapee.byedpi.utility.DevLogStore
import io.github.dovecoteescapee.byedpi.utility.getPreferences
import io.github.dovecoteescapee.byedpi.utility.withTextScale
import io.github.dovecoteescapee.byedpi.utility.applyLimeFlowPalette
import io.github.dovecoteescapee.byedpi.utility.SettingsTransfer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(newBase.withTextScale())
    }

    private val exportSettingsLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            uri ?: return@registerForActivityResult
            lifecycleScope.launch {
                try {
                    val payload = withContext(Dispatchers.IO) {
                        SettingsTransfer.export(this@SettingsActivity)
                    }
                    withContext(Dispatchers.IO) {
                        contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use {
                            it.write(payload)
                        } ?: error("Could not open export file")
                    }
                    Toast.makeText(
                        this@SettingsActivity,
                        R.string.settings_exported,
                        Toast.LENGTH_SHORT,
                    ).show()
                } catch (_: Throwable) {
                    Toast.makeText(
                        this@SettingsActivity,
                        R.string.settings_transfer_failed,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }

    private val importSettingsLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@registerForActivityResult
            lifecycleScope.launch {
                try {
                    val raw = withContext(Dispatchers.IO) {
                        contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                            ?: error("Could not open import file")
                    }
                    withContext(Dispatchers.IO) {
                        SettingsTransfer.import(this@SettingsActivity, raw)
                    }
                    AppFilterActivity.ensureTelegramExcludedByDefault(getPreferences())
                    Toast.makeText(
                        this@SettingsActivity,
                        R.string.settings_imported,
                        Toast.LENGTH_SHORT,
                    ).show()
                    recreate()
                } catch (_: Throwable) {
                    Toast.makeText(
                        this@SettingsActivity,
                        R.string.settings_transfer_failed,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyLimeFlowPalette()
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.settingsBack.setOnClickListener { finish() }
        binding.detailBack.setOnClickListener { closeDetail() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.settingsDetailShell.visibility == View.VISIBLE) closeDetail() else finish()
            }
        })

        configureTheme()
        configureConnection()
        configureNavigation()
        configureTransfer()
        configureReset()
        animateEntry()
    }

    override fun onResume() {
        super.onResume()
        refreshDashboard()
    }

    private fun configureTheme() {
        val preference = getPreferences().getString("app_theme", "system")
        binding.themeGroup.check(
            when (preference) {
                "light" -> R.id.theme_light_button
                "dark" -> R.id.theme_dark_button
                else -> R.id.theme_system_button
            }
        )
        binding.themeGroup.addOnButtonCheckedListener { _, checkedId, checked ->
            if (!checked) return@addOnButtonCheckedListener
            val (name, mode) = when (checkedId) {
                R.id.theme_light_button -> "light" to AppCompatDelegate.MODE_NIGHT_NO
                R.id.theme_dark_button -> "dark" to AppCompatDelegate.MODE_NIGHT_YES
                else -> "system" to AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            getPreferences().edit().putString("app_theme", name).apply()
            AppCompatDelegate.setDefaultNightMode(mode)
        }
        binding.appIconCard.setOnClickListener { pickAppIcon() }
    }

    private fun pickAppIcon() {
        val preferences = getPreferences()
        val current = AppIcon.current(preferences)
        val labels = arrayOf(
            getString(R.string.app_icon_default),
            getString(R.string.app_icon_dark),
            getString(R.string.app_icon_mono),
            getString(R.string.app_icon_lime),
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.app_icon_title)
            .setSingleChoiceItems(labels, AppIcon.KEYS.indexOf(current)) { dialog, which ->
                dialog.dismiss()
                AppIcon.apply(this, AppIcon.KEYS[which])
                Toast.makeText(this, R.string.app_icon_applied, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun configureConnection() {
        val preferences = getPreferences()

        // Engine switch: our byedpi (VPN/Proxy) or the root zapret engine.
        val zapretSelected =
            preferences.getString(ServiceManager.ENGINE_MODE_KEY, ServiceManager.ENGINE_BYEDPI) ==
                ServiceManager.ENGINE_ZAPRET
        binding.engineGroup.check(
            if (zapretSelected) R.id.engine_zapret_button else R.id.engine_byedpi_button
        )
        applyEngineVisibility(zapretSelected)
        binding.engineGroup.addOnButtonCheckedListener { _, checkedId, checked ->
            if (!checked) return@addOnButtonCheckedListener
            val isZapret = checkedId == R.id.engine_zapret_button
            if (isZapret) {
                // The zapret mode is impossible without root; check before
                // committing the switch and fall back with an explanation.
                lifecycleScope.launch {
                    val hasRoot = withContext(Dispatchers.IO) { ZapretEngineService.hasRoot() }
                    if (hasRoot) {
                        preferences.edit()
                            .putString(ServiceManager.ENGINE_MODE_KEY, ServiceManager.ENGINE_ZAPRET)
                            .apply()
                        applyEngineVisibility(true)
                    } else {
                        binding.engineGroup.check(R.id.engine_byedpi_button)
                        MaterialAlertDialogBuilder(this@SettingsActivity)
                            .setTitle(R.string.settings_engine_zapret)
                            .setMessage(R.string.zapret_root_missing)
                            .setPositiveButton(R.string.custom_strategy_cancel, null)
                            .show()
                    }
                }
            } else {
                preferences.edit()
                    .putString(ServiceManager.ENGINE_MODE_KEY, ServiceManager.ENGINE_BYEDPI)
                    .apply()
                applyEngineVisibility(false)
            }
        }
        binding.engineHint.setOnClickListener {
            startActivity(Intent(this, ZapretEngineActivity::class.java))
        }

        // Settings open while connected: engine/mode switches stay locked
        // until disconnect (they cannot be applied mid-run), everything else
        // just applies on the next connect.
        val running = appStatus.first == AppStatus.Running
        binding.runningNote.visibility = if (running) View.VISIBLE else View.GONE
        if (running) {
            binding.engineGroup.children.forEach { it.isEnabled = false }
            binding.modeGroup.children.forEach { it.isEnabled = false }
        }

        binding.modeGroup.check(
            if (preferences.getString("byedpi_mode", "vpn") == "proxy") {
                R.id.mode_proxy_button
            } else {
                R.id.mode_vpn_button
            }
        )
        binding.modeGroup.addOnButtonCheckedListener { _, checkedId, checked ->
            if (checked) {
                preferences.edit()
                    .putString(
                        "byedpi_mode",
                        if (checkedId == R.id.mode_proxy_button) "proxy" else "vpn",
                    )
                    .apply()
            }
        }
        binding.dnsInput.setText(preferences.getString("dns_ip", "1.1.1.1"))
        binding.dnsInput.doAfterTextChanged {
            preferences.edit().putString("dns_ip", it?.toString()?.trim().orEmpty()).apply()
        }
        binding.ipv6Switch.isChecked = preferences.getBoolean("ipv6_enable", true)
        binding.ipv6Switch.setOnCheckedChangeListener { _, checked ->
            preferences.edit().putBoolean("ipv6_enable", checked).apply()
        }
        binding.bootSwitch.isChecked = preferences.getBoolean("autoconnect_on_boot", false)
        binding.bootSwitch.setOnCheckedChangeListener { _, checked ->
            preferences.edit().putBoolean("autoconnect_on_boot", checked).apply()
        }
        binding.unlockSwitch.isChecked = preferences.getBoolean("connect_on_unlock", false)
        binding.unlockSwitch.setOnCheckedChangeListener { _, checked ->
            preferences.edit().putBoolean("connect_on_unlock", checked).apply()
        }
        binding.screenOffSwitch.isChecked = preferences.getBoolean("disconnect_on_screen_off", false)
        binding.screenOffSwitch.setOnCheckedChangeListener { _, checked ->
            preferences.edit().putBoolean("disconnect_on_screen_off", checked).apply()
        }
        binding.speedNotificationSwitch.isChecked =
            preferences.getBoolean("show_speed_in_notification", false)
        binding.speedNotificationSwitch.setOnCheckedChangeListener { _, checked ->
            preferences.edit().putBoolean("show_speed_in_notification", checked).apply()
        }
        binding.memorySwitch.isChecked = preferences.getBoolean("show_engine_memory", false)
        binding.memorySwitch.setOnCheckedChangeListener { _, checked ->
            preferences.edit().putBoolean("show_engine_memory", checked).apply()
        }
    }

    /* While zapret is selected the byedpi-only VPN/Proxy mode switch makes no
     * sense; the hint doubles as a shortcut to the Zapret Engine screen. */
    private fun applyEngineVisibility(zapretSelected: Boolean) {
        binding.modeGroup.visibility =
            if (zapretSelected) android.view.View.GONE else android.view.View.VISIBLE
        binding.engineHint.visibility =
            if (zapretSelected) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun configureNavigation() {
        binding.appFilterCard.setOnClickListener {
            startActivity(Intent(this, AppFilterActivity::class.java))
        }
        binding.hostListCard.setOnClickListener {
            startActivity(Intent(this, HostListActivity::class.java))
        }
        binding.urlSchemesCard.setOnClickListener {
            startActivity(Intent(this, UrlSchemesActivity::class.java))
        }
        binding.strategiesCard.setOnClickListener {
            startActivity(Intent(this, ProfilePickerActivity::class.java))
        }
        binding.strategyBuilderCard.setOnClickListener {
            startActivity(StrategyBuilderActivity.intent(this))
        }
        binding.engineSettingsCard.setOnClickListener {
            getPreferences().edit().putBoolean("byedpi_enable_cmd_settings", false).apply()
            openDetail(ByeDpiUISettingsFragment(), getString(R.string.settings_engine_visual))
        }
        binding.commandSettingsCard.setOnClickListener {
            getPreferences().edit().putBoolean("byedpi_enable_cmd_settings", true).apply()
            openDetail(
                ByeDpiCommandLineSettingsFragment(),
                getString(R.string.settings_engine_command),
            )
        }
        binding.strategyShareCard.setOnClickListener {
            startActivity(Intent(this, StrategyShareActivity::class.java))
        }
        binding.devLogsCard.setOnClickListener {
            startActivity(Intent(this, DevLogsActivity::class.java))
        }
    }

    private fun configureReset() {
        // INCY-style danger zone: every reset is its own action with its own
        // confirmation dialog spelling out the consequences.
        binding.resetButton.setOnClickListener {
            val options = arrayOf(
                getString(R.string.danger_reset_network),
                getString(R.string.danger_reset_theme),
                getString(R.string.danger_reset_stats),
                getString(R.string.danger_reset_logs),
                getString(R.string.danger_reset_all),
            )
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.danger_zone_title)
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> confirmReset(R.string.danger_reset_network, R.string.danger_network_message) { resetNetworkSettings() }
                        1 -> confirmReset(R.string.danger_reset_theme, R.string.danger_theme_message) { resetTheme() }
                        2 -> confirmReset(R.string.danger_reset_stats, R.string.danger_stats_message) {
                            TrafficStatsStore.reset(this)
                        }
                        3 -> confirmReset(R.string.danger_reset_logs, R.string.danger_logs_message) {
                            DevLogStore.clear()
                        }
                        4 -> confirmReset(R.string.danger_reset_all, R.string.settings_reset_message) { resetEverything() }
                    }
                }
                .show()
        }
    }

    private fun confirmReset(title: Int, message: Int, action: () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton(R.string.custom_strategy_cancel, null)
            .setPositiveButton(R.string.reset_settings) { _, _ ->
                action()
                recreate()
            }
            .show()
    }

    private fun resetNetworkSettings() {
        getPreferences().edit()
            .remove("dns_ip")
            .remove("ipv6_enable")
            .remove("byedpi_proxy_ip")
            .remove("byedpi_proxy_port")
            .remove("byedpi_max_connections")
            .remove("byedpi_buffer_size")
            .remove("byedpi_default_ttl")
            .remove("byedpi_enable_cmd_settings")
            .remove("byedpi_cmd_args")
            .putString("byedpi_mode", "vpn")
            .putBoolean("ipv6_enable", true)
            .putBoolean("byedpi_enable_cmd_settings", true)
            .apply()
        FlowsealProfiles.refreshSelected(getPreferences())
        AppFilterActivity.ensureTelegramExcludedByDefault(getPreferences())
    }

    private fun resetTheme() {
        getPreferences().edit()
            .putString("app_theme", "system")
            .putBoolean("app_dynamic_colors", true)
            .putString("app_palette", "limeflow")
            .apply()
    }

    private fun resetEverything() {
        getPreferences().edit().clear()
            .putString("byedpi_mode", "vpn")
            .putBoolean("byedpi_enable_cmd_settings", true)
            .putBoolean("ipv6_enable", true)
            .apply()
        FlowsealProfiles.select(getPreferences(), FlowsealProfiles.default)
        AppFilterActivity.ensureTelegramExcludedByDefault(getPreferences())
    }

    private fun configureTransfer() {
        binding.exportSettingsButton.setOnClickListener {
            exportSettingsLauncher.launch("LimeFlow-settings.json")
        }
        binding.importSettingsButton.setOnClickListener {
            importSettingsLauncher.launch(arrayOf("application/json", "text/plain"))
        }
    }

    private fun refreshDashboard() {
        val preferences = getPreferences()
        AppFilterActivity.ensureTelegramExcludedByDefault(preferences)
        binding.selectedStrategy.text = FlowsealProfiles.selected(preferences).name
        val packages = preferences.getStringSet(AppFilterActivity.FILTER_PACKAGES, emptySet()).orEmpty()
        binding.appFilterSummary.text = when (
            preferences.getString(AppFilterActivity.FILTER_MODE, AppFilterActivity.MODE_ALL)
        ) {
            AppFilterActivity.MODE_INCLUDE ->
                getString(R.string.filter_mode_include_summary, packages.size)
            AppFilterActivity.MODE_EXCLUDE ->
                getString(R.string.filter_mode_exclude_summary, packages.size)
            else -> getString(R.string.filter_mode_all_summary)
        }
        val hostsOn = BypassHosts.enabledCount(preferences)
        val hostsTotal = BypassHosts.catalog(preferences).size
        binding.hostListSummary.text = getString(R.string.host_list_selected, hostsOn, hostsTotal)
        binding.versionText.text = getString(
            R.string.version_summary,
            getString(R.string.version),
            BuildConfig.VERSION_NAME,
        )
    }

    private fun openDetail(fragment: Fragment, title: String) {
        binding.detailTitle.text = title.replace("\n", " ")
        supportFragmentManager.beginTransaction()
            .replace(R.id.settings, fragment)
            .commit()
        binding.settingsDetailShell.visibility = View.VISIBLE
        binding.settingsDetailShell.alpha = 0f
        binding.settingsDetailShell.translationX = 48f
        binding.settingsDetailShell.animate()
            .alpha(1f)
            .translationX(0f)
            .setDuration(280)
            .start()
        binding.settingsDashboard.visibility = View.GONE
    }

    private fun closeDetail() {
        supportFragmentManager.findFragmentById(R.id.settings)?.let {
            supportFragmentManager.beginTransaction().remove(it).commit()
        }
        binding.settingsDashboard.visibility = View.VISIBLE
        binding.settingsDashboard.alpha = 0f
        binding.settingsDashboard.animate().alpha(1f).setDuration(220).start()
        binding.settingsDetailShell.visibility = View.GONE
    }

    private fun animateEntry() {
        binding.settingsDashboard.alpha = 0f
        binding.settingsDashboard.translationY = 24f
        binding.settingsDashboard.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(420)
            .start()
    }
}
