package io.github.dovecoteescapee.byedpi.activities

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.dovecoteescapee.byedpi.BuildConfig
import io.github.dovecoteescapee.byedpi.R
import io.github.dovecoteescapee.byedpi.data.FlowsealProfiles
import io.github.dovecoteescapee.byedpi.databinding.ActivitySettingsBinding
import io.github.dovecoteescapee.byedpi.fragments.ByeDpiCommandLineSettingsFragment
import io.github.dovecoteescapee.byedpi.fragments.ByeDpiUISettingsFragment
import io.github.dovecoteescapee.byedpi.utility.getPreferences
import io.github.dovecoteescapee.byedpi.utility.applyLimeFlowPalette
import io.github.dovecoteescapee.byedpi.utility.SettingsTransfer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding

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
    }

    private fun configureConnection() {
        val preferences = getPreferences()
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
    }

    private fun configureNavigation() {
        binding.appFilterCard.setOnClickListener {
            startActivity(Intent(this, AppFilterActivity::class.java))
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
    }

    private fun configureReset() {
        binding.resetButton.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.settings_reset_title)
                .setMessage(R.string.settings_reset_message)
                .setNegativeButton(R.string.custom_strategy_cancel, null)
                .setPositiveButton(R.string.reset_settings) { _, _ ->
                    getPreferences().edit().clear()
                        .putString("byedpi_mode", "vpn")
                        .putBoolean("byedpi_enable_cmd_settings", true)
                        .putBoolean("ipv6_enable", true)
                        .apply()
                    FlowsealProfiles.select(
                        getPreferences(),
                        FlowsealProfiles.all.first { it.id == "alt11" },
                    )
                    AppFilterActivity.ensureTelegramExcludedByDefault(getPreferences())
                    recreate()
                }
                .show()
        }
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
