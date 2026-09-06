package io.github.dovecoteescapee.byedpi.zapret

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import io.github.dovecoteescapee.byedpi.R
import io.github.dovecoteescapee.byedpi.activities.MainActivity
import io.github.dovecoteescapee.byedpi.data.STOP_ACTION
import io.github.dovecoteescapee.byedpi.utility.createConnectionNotification
import io.github.dovecoteescapee.byedpi.utility.registerNotificationChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/*
 * Zapret Engine: запускает настоящий nfqws (bol-van/zapret, MIT) через su.
 * Трафик перехватывается ядровой очередью NFQUEUE через iptables, поэтому
 * VPN-интерфейс не используется — режим требует root и несовместим с
 * запущенным VPN/Proxy LimeFlow.
 */
class ZapretEngineService : LifecycleService() {
    private var process: Process? = null

    companion object {
        private val TAG: String = ZapretEngineService::class.java.simpleName
        private const val FOREGROUND_SERVICE_ID: Int = 3
        private const val NOTIFICATION_CHANNEL_ID = "LimeFlowZapret"
        private const val QUEUE_NUM = 537
        private const val ACTION_START = "io.github.dovecoteescapee.byedpi.zapret.START"
        private const val ACTION_STOP = "io.github.dovecoteescapee.byedpi.zapret.STOP"

        private val _state = kotlinx.coroutines.flow.MutableStateFlow(ZapretState.Halted)
        val state: kotlinx.coroutines.flow.StateFlow<ZapretState> = _state
        private val _statusText = kotlinx.coroutines.flow.MutableStateFlow("")
        val statusText: kotlinx.coroutines.flow.StateFlow<String> = _statusText

        val isRunning: Boolean get() = _state.value == ZapretState.Running

        fun start(context: Context, strategyId: String) {
            val intent = Intent(context, ZapretEngineService::class.java).apply {
                action = ACTION_START
                putExtra("strategy", strategyId)
            }
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, ZapretEngineService::class.java).apply { action = ACTION_STOP }
            )
        }

        fun hasRoot(): Boolean = runCatching {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            output.contains("uid=0")
        }.getOrDefault(false)
    }

    enum class ZapretState { Halted, Running, Failed }

    override fun onCreate() {
        super.onCreate()
        registerNotificationChannel(
            this,
            NOTIFICATION_CHANNEL_ID,
            R.string.zapret_channel_name,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startForeground()
        return when (intent?.action) {
            ACTION_START -> {
                val strategy = intent.getStringExtra("strategy") ?: "general"
                lifecycleScope.launch { startEngine(strategy) }
                START_STICKY
            }

            ACTION_STOP, STOP_ACTION -> {
                lifecycleScope.launch { stopEngine() }
                START_NOT_STICKY
            }

            else -> START_NOT_STICKY
        }
    }

    private fun startForeground() {
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                FOREGROUND_SERVICE_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(FOREGROUND_SERVICE_ID, notification)
        }
    }

    private suspend fun startEngine(strategyId: String) {
        if (_state.value == ZapretState.Running) {
            Log.w(TAG, "Zapret engine already running")
            return
        }
        _state.value = ZapretState.Halted
        _statusText.value = getString(R.string.zapret_preparing)

        try {
            withContext(Dispatchers.IO) {
                ZapretStrategies.extractDataFiles(applicationContext)
                val args = ZapretStrategies.parseArgs(applicationContext, "zapret/configs/$strategyId.bat")
                val script = buildScript(args)
                val scriptFile = File(filesDir, "zapret/run.sh")
                scriptFile.parentFile?.mkdirs()
                scriptFile.writeText(script)
                Runtime.getRuntime().exec(arrayOf("chmod", "700", scriptFile.absolutePath)).waitFor()

                process = Runtime.getRuntime()
                    .exec(arrayOf("su", "-c", "sh ${scriptFile.absolutePath}"))
                // Движок должен жить: если он умер сразу — ошибка конфигурации.
                Thread.sleep(1_500)
                if (!process!!.isAlive) {
                    throw IllegalStateException("nfqws exited: " + process!!.inputStream.bufferedReader().readText().take(200))
                }
                _state.value = ZapretState.Running
                _statusText.value = getString(R.string.zapret_running_status)
            }
        } catch (error: Throwable) {
            Log.e(TAG, "Failed to start zapret engine", error)
            _state.value = ZapretState.Failed
            _statusText.value = error.message ?: getString(R.string.zapret_failed)
            stopSelf()
        }
    }

    private fun buildScript(args: List<String>): String {
        val binary = applicationInfo.nativeLibraryDir + "/libnfqws.so"
        val portsTcp = "80,443,2053,2083,2087,2096,8443"
        val portsUdp = "443,19294:19344,50000:50100"
        val nfqArgs = args.joinToString(" ") { arg ->
            if (' ' in arg) "'$arg'" else arg
        }
        return buildString {
            append("#!/system/bin/sh\n")
            for (tool in listOf("iptables", "ip6tables")) {
                append("$tool -t mangle -N LIMEFLOW 2>/dev/null\n")
                append("$tool -t mangle -F LIMEFLOW 2>/dev/null\n")
                append("$tool -t mangle -A LIMEFLOW -p tcp -m multiport --dports $portsTcp")
                append(" -j NFQUEUE --queue-num $QUEUE_NUM --queue-bypass 2>/dev/null\n")
                append("$tool -t mangle -A LIMEFLOW -p udp -m multiport --dports $portsUdp")
                append(" -j NFQUEUE --queue-num $QUEUE_NUM --queue-bypass 2>/dev/null\n")
                append("$tool -t mangle -C OUTPUT -j LIMEFLOW 2>/dev/null ||")
                append(" $tool -t mangle -I OUTPUT 1 -j LIMEFLOW 2>/dev/null\n")
            }
            append("exec '$binary' --qnum=$QUEUE_NUM $nfqArgs\n")
        }
    }

    private suspend fun stopEngine() {
        withContext(Dispatchers.IO) {
            runCatching { process?.destroy() }
            process = null
            val cleanup = buildString {
                for (tool in listOf("iptables", "ip6tables")) {
                    append("$tool -t mangle -D OUTPUT -j LIMEFLOW 2>/dev/null\n")
                    append("$tool -t mangle -X LIMEFLOW 2>/dev/null\n")
                }
                append("pkill -f libnfqws.so 2>/dev/null\n")
            }
            val script = File(filesDir, "zapret/cleanup.sh")
            script.writeText(cleanup)
            runCatching {
                val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "sh ${script.absolutePath}"))
                p.waitFor()
            }
            _state.value = ZapretState.Halted
            _statusText.value = getString(R.string.zapret_stopped_status)
        }
        stopSelf()
    }

    private fun createNotification(): Notification =
        createConnectionNotification(
            this,
            NOTIFICATION_CHANNEL_ID,
            R.string.zapret_notification_title,
            getString(R.string.zapret_notification_content),
            ZapretEngineService::class.java,
        )
}
