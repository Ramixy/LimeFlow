package io.github.dovecoteescapee.byedpi.activities

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.dovecoteescapee.byedpi.R
import io.github.dovecoteescapee.byedpi.data.TrafficStatsStore
import io.github.dovecoteescapee.byedpi.databinding.ActivityTrafficStatsBinding
import io.github.dovecoteescapee.byedpi.utility.applyLimeFlowPalette
import io.github.dovecoteescapee.byedpi.utility.formatDuration
import io.github.dovecoteescapee.byedpi.utility.formatTraffic

class TrafficStatsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityTrafficStatsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        applyLimeFlowPalette()
        super.onCreate(savedInstanceState)
        binding = ActivityTrafficStatsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.statsBack.setOnClickListener { finish() }
        binding.statsReset.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.traffic_stats_reset)
                .setMessage(R.string.traffic_stats_reset_message)
                .setNegativeButton(R.string.custom_strategy_cancel, null)
                .setPositiveButton(R.string.reset_settings) { _, _ ->
                    TrafficStatsStore.reset(this)
                    refresh()
                    Toast.makeText(this, R.string.traffic_stats_reset_done, Toast.LENGTH_SHORT).show()
                }
                .show()
        }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val snapshot = TrafficStatsStore.snapshot(this)
        binding.statsSession.text = describe(snapshot.sessionTx, snapshot.sessionRx, snapshot.sessionSeconds)
        binding.statsToday.text = describe(snapshot.todayTx, snapshot.todayRx, snapshot.todaySeconds)
        binding.statsWeek.text = describe(snapshot.weekTx, snapshot.weekRx, snapshot.weekSeconds)
        binding.statsTotal.text = describe(snapshot.totalTx, snapshot.totalRx, snapshot.totalSeconds)
    }

    private fun describe(tx: Long, rx: Long, seconds: Long): String =
        getString(
            R.string.traffic_period_value,
            formatTraffic(tx),
            formatTraffic(rx),
            formatDuration(seconds),
        )
}
