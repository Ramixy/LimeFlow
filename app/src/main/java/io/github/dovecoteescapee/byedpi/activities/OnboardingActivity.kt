package io.github.dovecoteescapee.byedpi.activities

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import io.github.dovecoteescapee.byedpi.R
import io.github.dovecoteescapee.byedpi.data.StrategyMemory
import io.github.dovecoteescapee.byedpi.databinding.ActivityOnboardingBinding
import io.github.dovecoteescapee.byedpi.utility.applyLimeFlowPalette
import io.github.dovecoteescapee.byedpi.utility.getPreferences

/**
 * First-run intro: what LimeFlow is, how strategies are picked and the
 * battery-optimization offer (the same prompt MainActivity shows later).
 */
class OnboardingActivity : AppCompatActivity() {
    private lateinit var binding: ActivityOnboardingBinding
    private var page = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        applyLimeFlowPalette()
        super.onCreate(savedInstanceState)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.onboardingNext.setOnClickListener { advance() }
        binding.onboardingBattery.setOnClickListener { requestBatteryExemption() }
        showPage(0)
    }

    private fun showPage(target: Int) {
        page = target
        listOf(
            binding.onboardingPage1,
            binding.onboardingPage2,
            binding.onboardingPage3,
        ).forEachIndexed { index, view ->
            view.visibility = if (index == target) View.VISIBLE else View.GONE
        }
        val active = ContextCompat.getColor(this, R.color.lime_connected)
        val idle = ContextCompat.getColor(this, R.color.app_stroke)
        // XML-inflated drawables share a constant state; without mutate() the
        // tint of one dot recolors all three.
        listOf(binding.dot1, binding.dot2, binding.dot3).forEachIndexed { index, dot ->
            dot.background.mutate().setTint(if (index == target) active else idle)
        }
        val last = target == 2
        binding.onboardingNext.setText(
            if (last) R.string.onboarding_done else R.string.onboarding_next
        )
        binding.onboardingBattery.visibility = if (last) View.VISIBLE else View.GONE
    }

    private fun advance() {
        if (page < 2) {
            showPage(page + 1)
        } else {
            finishOnboarding()
        }
    }

    private fun requestBatteryExemption() {
        getPreferences().edit().putBoolean(StrategyMemory.BATTERY_PROMPTED_KEY, true).apply()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(Uri.parse("package:$packageName"))
                )
            }
        }
    }

    private fun finishOnboarding() {
        getPreferences().edit().putBoolean(ONBOARDING_DONE_KEY, true).apply()
        finish()
    }

    companion object {
        const val ONBOARDING_DONE_KEY = "onboarding_done_v1"
    }
}
