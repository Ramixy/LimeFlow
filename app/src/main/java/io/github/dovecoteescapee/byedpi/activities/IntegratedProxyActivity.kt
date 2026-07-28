package io.github.dovecoteescapee.byedpi.activities

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.amurcanov.tgwsproxy.EmbeddedProxyFragment
import io.github.dovecoteescapee.byedpi.R
import io.github.dovecoteescapee.byedpi.databinding.ActivityIntegratedProxyBinding
import io.github.dovecoteescapee.byedpi.utility.applyLimeFlowPalette
import io.github.dovecoteescapee.byedpi.utility.getPreferences

class IntegratedProxyActivity : AppCompatActivity() {
    private lateinit var binding: ActivityIntegratedProxyBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        ensureUnifiedAppearanceDefaults()
        applyStoredTheme()
        applyLimeFlowPalette()
        super.onCreate(savedInstanceState)

        binding = ActivityIntegratedProxyBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.tg_ws_proxy_container, EmbeddedProxyFragment())
                .commit()
        }

        binding.limeflowTab.setOnClickListener {
            finish()
        }
        binding.tgWsProxyTab.setOnClickListener {
            binding.tgWsProxyContainer.requestFocus()
        }
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.limeflow_enter, R.anim.proxy_exit)
    }

    private fun applyStoredTheme() {
        val mode = when (getPreferences().getString("app_theme", "system")) {
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            "dark" -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(mode)
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
}
