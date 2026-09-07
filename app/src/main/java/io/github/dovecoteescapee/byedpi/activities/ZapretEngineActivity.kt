package io.github.dovecoteescapee.byedpi.activities

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.dovecoteescapee.byedpi.R
import io.github.dovecoteescapee.byedpi.databinding.ActivityZapretEngineBinding
import io.github.dovecoteescapee.byedpi.services.appStatus
import io.github.dovecoteescapee.byedpi.data.AppStatus
import io.github.dovecoteescapee.byedpi.utility.applyLimeFlowPalette
import io.github.dovecoteescapee.byedpi.zapret.ZapretEngineService
import io.github.dovecoteescapee.byedpi.zapret.ZapretStrategies
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ZapretEngineActivity : AppCompatActivity() {
    private lateinit var binding: ActivityZapretEngineBinding
    private var hasRoot = false
    private var wasRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        applyLimeFlowPalette()
        super.onCreate(savedInstanceState)
        binding = ActivityZapretEngineBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // `su` can hang for seconds while waiting for a user prompt; never
        // block the main thread on it.
        lifecycleScope.launch(Dispatchers.IO) {
            val root = ZapretEngineService.hasRoot()
            withContext(Dispatchers.Main) {
                hasRoot = root
                binding.zapretRootStatus.text = getString(
                    if (hasRoot) R.string.zapret_root_ok else R.string.zapret_root_missing
                )
                binding.zapretRootStatus.setTextColor(
                    androidx.core.content.ContextCompat.getColor(
                        this@ZapretEngineActivity,
                        if (hasRoot) R.color.lime_connected else R.color.youtube_red,
                    )
                )
                renderState(ZapretEngineService.state.value)
            }
        }

        val strategies = ZapretStrategies.list()
        binding.zapretStrategy.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, strategies.map { it.name })
        )
        binding.zapretStrategy.setText(strategies.first().name, false)
        binding.zapretStrategy.setOnItemClickListener { _, _, position, _ ->
            binding.zapretStrategyDescription.text = strategies[position].description
        }
        binding.zapretStrategyDescription.text = strategies.first().description

        binding.zapretStart.setOnClickListener { startEngine() }
        binding.zapretStop.setOnClickListener {
            ZapretEngineService.stop(applicationContext)
        }
        binding.zapretBack.setOnClickListener { finish() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    ZapretEngineService.state.collect { state ->
                        renderState(state)
                    }
                }
                launch {
                    ZapretEngineService.statusText.collect { text ->
                        if (text.isNotEmpty()) binding.zapretStatusDetail.text = text
                    }
                }
            }
        }
    }

    private fun renderState(state: ZapretEngineService.ZapretState) {
        binding.zapretStart.isEnabled = hasRoot && state != ZapretEngineService.ZapretState.Running &&
            appStatus.first == AppStatus.Halted
        binding.zapretStop.isEnabled = state == ZapretEngineService.ZapretState.Running
        binding.zapretStatus.setText(
            when (state) {
                ZapretEngineService.ZapretState.Running -> R.string.zapret_state_running
                ZapretEngineService.ZapretState.Failed -> R.string.zapret_state_failed
                ZapretEngineService.ZapretState.Halted -> R.string.zapret_state_halted
            }
        )
        binding.zapretProgress.visibility =
            if (state == ZapretEngineService.ZapretState.Running) View.VISIBLE else View.GONE
        if (state == ZapretEngineService.ZapretState.Running && !wasRunning) {
            Toast.makeText(this, R.string.zapret_started_toast, Toast.LENGTH_SHORT).show()
        }
        wasRunning = state == ZapretEngineService.ZapretState.Running
    }

    private fun startEngine() {
        if (!hasRoot) {
            Toast.makeText(this, R.string.zapret_root_missing, Toast.LENGTH_LONG).show()
            return
        }
        if (appStatus.first != AppStatus.Halted) {
            Toast.makeText(this, R.string.zapret_stop_vpn_first, Toast.LENGTH_LONG).show()
            return
        }
        val selectedName = binding.zapretStrategy.text?.toString().orEmpty()
        val strategy = ZapretStrategies.list()
            .firstOrNull { it.name == selectedName } ?: ZapretStrategies.list().first()
        ZapretEngineService.start(applicationContext, strategy.id)
    }
}
