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
import io.github.dovecoteescapee.byedpi.utility.getPreferences
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
        wasRunning = ZapretEngineService.isRunning

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

        val prefs = getPreferences()
        binding.gameTcpEnabled.isChecked = prefs.getBoolean(ZapretStrategies.GAME_TCP_ENABLED, false)
        binding.gameUdpEnabled.isChecked = prefs.getBoolean(ZapretStrategies.GAME_UDP_ENABLED, false)
        binding.gameTcpPorts.setText(prefs.getString(ZapretStrategies.GAME_TCP_PORTS, ZapretStrategies.DEFAULT_GAME_PORTS))
        binding.gameUdpPorts.setText(prefs.getString(ZapretStrategies.GAME_UDP_PORTS, ZapretStrategies.DEFAULT_GAME_PORTS))
        binding.gameTcpEnabled.setOnCheckedChangeListener { _, checked -> binding.gameTcpLayout.isEnabled = checked }
        binding.gameUdpEnabled.setOnCheckedChangeListener { _, checked -> binding.gameUdpLayout.isEnabled = checked }
        binding.gameTcpLayout.isEnabled = binding.gameTcpEnabled.isChecked
        binding.gameUdpLayout.isEnabled = binding.gameUdpEnabled.isChecked

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
        val busy = state == ZapretEngineService.ZapretState.Starting ||
            state == ZapretEngineService.ZapretState.Stopping
        binding.zapretStart.isEnabled = hasRoot && !busy && state != ZapretEngineService.ZapretState.Running &&
            appStatus.first == AppStatus.Halted
        binding.zapretStop.isEnabled = state == ZapretEngineService.ZapretState.Running
        binding.gameTcpEnabled.isEnabled = !busy && state != ZapretEngineService.ZapretState.Running
        binding.gameUdpEnabled.isEnabled = !busy && state != ZapretEngineService.ZapretState.Running
        binding.gameTcpLayout.isEnabled = binding.gameTcpEnabled.isEnabled && binding.gameTcpEnabled.isChecked
        binding.gameUdpLayout.isEnabled = binding.gameUdpEnabled.isEnabled && binding.gameUdpEnabled.isChecked
        binding.zapretStatus.setText(
            when (state) {
                ZapretEngineService.ZapretState.Running -> R.string.zapret_state_running
                ZapretEngineService.ZapretState.Starting -> R.string.zapret_state_starting
                ZapretEngineService.ZapretState.Stopping -> R.string.zapret_state_stopping
                ZapretEngineService.ZapretState.Failed -> R.string.zapret_state_failed
                ZapretEngineService.ZapretState.Halted -> R.string.zapret_state_halted
            }
        )
        binding.zapretProgress.visibility =
            if (busy) View.VISIBLE else View.GONE
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
        val tcp = ZapretStrategies.validatePorts(binding.gameTcpPorts.text?.toString().orEmpty())
        val udp = ZapretStrategies.validatePorts(binding.gameUdpPorts.text?.toString().orEmpty())
        binding.gameTcpLayout.error = if (binding.gameTcpEnabled.isChecked && tcp == null)
            getString(R.string.zapret_ports_error) else null
        binding.gameUdpLayout.error = if (binding.gameUdpEnabled.isChecked && udp == null)
            getString(R.string.zapret_ports_error) else null
        if ((binding.gameTcpEnabled.isChecked && tcp == null) ||
            (binding.gameUdpEnabled.isChecked && udp == null)) return
        getPreferences().edit()
            .putBoolean(ZapretStrategies.GAME_TCP_ENABLED, binding.gameTcpEnabled.isChecked)
            .putBoolean(ZapretStrategies.GAME_UDP_ENABLED, binding.gameUdpEnabled.isChecked)
            .putString(ZapretStrategies.GAME_TCP_PORTS, tcp ?: ZapretStrategies.DEFAULT_GAME_PORTS)
            .putString(ZapretStrategies.GAME_UDP_PORTS, udp ?: ZapretStrategies.DEFAULT_GAME_PORTS)
            .apply()
        val selectedName = binding.zapretStrategy.text?.toString().orEmpty()
        val strategy = ZapretStrategies.list()
            .firstOrNull { it.name == selectedName } ?: ZapretStrategies.list().first()
        binding.zapretStatus.setText(R.string.zapret_state_starting)
        binding.zapretStatusDetail.setText(R.string.zapret_preparing)
        binding.zapretStart.isEnabled = false
        ZapretEngineService.start(applicationContext, strategy.id)
    }
}
