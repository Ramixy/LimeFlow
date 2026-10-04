package io.github.dovecoteescapee.byedpi.activities

import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import io.github.dovecoteescapee.byedpi.R
import io.github.dovecoteescapee.byedpi.databinding.ActivityDevLogsBinding
import io.github.dovecoteescapee.byedpi.utility.DevLogStore
import io.github.dovecoteescapee.byedpi.utility.applyLimeFlowPalette
import io.github.dovecoteescapee.byedpi.utility.getPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Debug log viewer over DevLogStore: level filter (all/errors), free-text
 * search (works for domains too), export to a file and a retention window.
 * The buffer itself keeps refreshing while the screen is open.
 */
class DevLogsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityDevLogsBinding
    private var errorOnly = false
    private var appOnly = false
    private var query = ""
    private var refreshJob: Job? = null

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            uri ?: return@registerForActivityResult
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val text = DevLogStore.snapshotFiltered(retentionMs(), errorOnly, query, appOnly)
                        .joinToString("\n")
                    contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use {
                        it.write(text)
                    }
                } catch (e: IOException) {
                    android.util.Log.w("DevLogs", "Export failed", e)
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyLimeFlowPalette()
        super.onCreate(savedInstanceState)
        binding = ActivityDevLogsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.logsBack.setOnClickListener { finish() }
        binding.filterApp.isChecked = true
        binding.logsFilterGroup.addOnButtonCheckedListener { _, checkedId, checked ->
            if (!checked) return@addOnButtonCheckedListener
            when (checkedId) {
                R.id.filter_errors -> { errorOnly = true; appOnly = false }
                R.id.filter_all -> { errorOnly = false; appOnly = false }
                else -> { errorOnly = false; appOnly = true }
            }
            refresh()
        }
        binding.logsSearch.doAfterTextChanged {
            query = it?.toString()?.trim().orEmpty()
            refresh()
        }
        binding.logsExport.setOnClickListener {
            exportLauncher.launch("limeflow-log.txt")
        }
        binding.logsRetention.setOnClickListener { pickRetention() }
        binding.logsClear.setOnClickListener {
            DevLogStore.clear()
            refresh()
        }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        // Live capture while the screen is visible, like the Proxy logs tab:
        // logcat also replays its recent buffer, so history shows up at once.
        DevLogStore.startStream(this)
        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch {
            while (isActive) {
                delay(1_000)
                refresh()
            }
        }
    }

    override fun onPause() {
        refreshJob?.cancel()
        refreshJob = null
        DevLogStore.stopStream()
        super.onPause()
    }

    private fun retentionMs(): Long = when (
        getPreferences().getString("logs_retention", "24h")
    ) {
        "1h" -> 60L * 60 * 1000
        "7d" -> 7L * 24 * 60 * 60 * 1000
        else -> 24L * 60 * 60 * 1000
    }

    private fun pickRetention() {
        val values = arrayOf("1h", "24h", "7d")
        val labels = arrayOf(
            getString(R.string.dev_logs_retention_1h),
            getString(R.string.dev_logs_retention_24h),
            getString(R.string.dev_logs_retention_7d),
        )
        val current = getPreferences().getString("logs_retention", "24h") ?: "24h"
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.dev_logs_retention)
            .setSingleChoiceItems(labels, values.indexOf(current).coerceAtLeast(0)) { dialog, which ->
                getPreferences().edit().putString("logs_retention", values[which]).apply()
                dialog.dismiss()
                refresh()
            }
            .show()
    }

    private fun refresh() {
        lifecycleScope.launch(Dispatchers.IO) {
            val lines = DevLogStore.snapshotFiltered(retentionMs(), errorOnly, query, appOnly)
            val text = if (lines.isEmpty()) "" else lines.joinToString("\n")
            withContext(Dispatchers.Main) {
                if (lines.isEmpty()) {
                    binding.logsText.setText(R.string.dev_logs_empty)
                    binding.logsCount.text = ""
                } else {
                    binding.logsText.text = text
                    binding.logsCount.text = getString(R.string.dev_logs_lines, lines.size)
                    binding.logsScroll.post {
                        binding.logsScroll.fullScroll(View.FOCUS_DOWN)
                    }
                }
            }
        }
    }
}
