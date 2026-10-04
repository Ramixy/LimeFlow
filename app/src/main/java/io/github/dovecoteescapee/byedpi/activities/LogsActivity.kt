package io.github.dovecoteescapee.byedpi.activities

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.dovecoteescapee.byedpi.BuildConfig
import io.github.dovecoteescapee.byedpi.R
import io.github.dovecoteescapee.byedpi.databinding.ActivityLogsBinding
import io.github.dovecoteescapee.byedpi.utility.AppLog
import io.github.dovecoteescapee.byedpi.utility.applyLimeFlowPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLogsBinding
    private val handler = Handler(Looper.getMainLooper())
    private var lastSerial = -1L

    private val saveLogsLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            uri ?: return@registerForActivityResult
            lifecycleScope.launch {
                val saved = withContext(Dispatchers.IO) {
                    runCatching {
                        contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use {
                            it.write(exportText())
                        } ?: error("Could not open export file")
                    }.isSuccess
                }
                Toast.makeText(
                    this@LogsActivity,
                    if (saved) R.string.logs_saved else R.string.logs_save_failed,
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }

    // Журнал пишется сервисами в фоне: опрашиваем буфер, пока экран открыт.
    private val pollLogs = object : Runnable {
        override fun run() {
            if (AppLog.serial() != lastSerial) render()
            handler.postDelayed(this, 400)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyLimeFlowPalette()
        super.onCreate(savedInstanceState)
        binding = ActivityLogsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.logsBack.setOnClickListener { finish() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = finish()
        })

        binding.logsCopy.setOnClickListener { copyLogs() }
        binding.logsSave.setOnClickListener {
            saveLogsLauncher.launch("LimeFlow-log.txt")
        }
        binding.logsClear.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.logs_clear_confirm_title)
                .setMessage(R.string.logs_clear_confirm_message)
                .setNegativeButton(R.string.custom_strategy_cancel, null)
                .setPositiveButton(R.string.logs_clear) { _, _ ->
                    AppLog.clear()
                    render()
                    Toast.makeText(this, R.string.logs_cleared, Toast.LENGTH_SHORT).show()
                }
                .show()
        }

        render()
    }

    override fun onResume() {
        super.onResume()
        handler.post(pollLogs)
    }

    override fun onPause() {
        handler.removeCallbacks(pollLogs)
        super.onPause()
    }

    private fun exportText(): String =
        "LimeFlow ${BuildConfig.VERSION_NAME}\n\n" + AppLog.dump()

    private fun copyLogs() {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("LimeFlow log", exportText()))
        Toast.makeText(this, R.string.logs_copied, Toast.LENGTH_SHORT).show()
    }

    private fun render() {
        lastSerial = AppLog.serial()
        val entries = AppLog.snapshot()
        binding.logsEmpty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        binding.logsScroll.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
        if (entries.isEmpty()) return

        val format = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        // Автопрокрутка нужна только когда пользователь и так внизу журнала;
        // иначе чтение середины будет постоянно сбрасываться новыми записями.
        val atBottom = !binding.logsScroll.canScrollVertically(1)
        binding.logsText.text = entries.joinToString("\n") { entry ->
            val stack = entry.message.indexOf('\n')
            val head = if (stack >= 0) entry.message.substring(0, stack) else entry.message
            val tail = if (stack >= 0) "\n${entry.message.substring(stack + 1)}" else ""
            "${format.format(Date(entry.time))} ${entry.level}/${entry.tag}: $head$tail"
        }
        if (atBottom) {
            binding.logsScroll.post {
                binding.logsScroll.fullScroll(View.FOCUS_DOWN)
            }
        }
    }
}
