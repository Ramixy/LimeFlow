package io.github.dovecoteescapee.byedpi.zapret

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import io.github.dovecoteescapee.byedpi.R
import io.github.dovecoteescapee.byedpi.activities.MainActivity
import io.github.dovecoteescapee.byedpi.data.*
import io.github.dovecoteescapee.byedpi.receiver.ScreenEventsController
import io.github.dovecoteescapee.byedpi.services.setStatus
import io.github.dovecoteescapee.byedpi.utility.createConnectionNotification
import io.github.dovecoteescapee.byedpi.utility.registerNotificationChannel
import io.github.dovecoteescapee.byedpi.widget.VpnWidgets
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
        private const val NFQWS_TAG = "nfqws"
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
            androidx.core.content.ContextCompat.startForegroundService(
                context,
                Intent(context, ZapretEngineService::class.java).apply { action = ACTION_STOP }
            )
        }

        // Cached for the session: every silent start/tile/widget path may ask,
        // and a su prompt must not pop up more than once per run.
        @Volatile
        private var rootAvailable: Boolean? = null

        fun hasRoot(): Boolean = rootAvailable ?: runCatching {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            // A pending su prompt or a wedged su binary must not hang the caller.
            if (!process.waitForTimed(5, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForciblyCompat()
                return false
            }
            val output = process.inputStream.bufferedReader().use { it.readText() }
            (output.contains("uid=0")).also { rootAvailable = it }
        }.getOrDefault(false).also { rootAvailable = it }
    }

    enum class ZapretState { Halted, Running, Failed }

    override fun onCreate() {
        super.onCreate()
        AppLog.verbose = getPreferences().getBoolean("developer_mode", false)
        registerNotificationChannel(
            this,
            NOTIFICATION_CHANNEL_ID,
            R.string.zapret_channel_name,
        )
        ScreenEventsController.register(this)
    }

    override fun onDestroy() {
        ScreenEventsController.unregister(this)
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startForeground()
        return when (intent?.action) {
            ACTION_START -> {
                val strategy = intent.getStringExtra("strategy") ?: "general"
                lifecycleScope.launch { startEngine(strategy) }
                // A sticky restart delivers a null intent; re-running the engine
                // without a start command would leave an uncontrolled foreground
                // service doing nothing.
                START_NOT_STICKY
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
            AppLog.w(TAG, "Zapret engine already running")
            return
        }
        _state.value = ZapretState.Halted
        _statusText.value = getString(R.string.zapret_preparing)
        AppLog.i(TAG, "Starting zapret strategy: $strategyId")

        // Without su the whole mode is impossible; fail fast with a clear
        // status instead of a stack trace from Runtime.exec.
        if (!withContext(Dispatchers.IO) { hasRoot() }) {
            Log.i(TAG, "Start refused: no root on device")
            _statusText.value = getString(R.string.zapret_root_missing)
            publish(ZapretState.Failed)
            stopSelf()
            return
        }

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
                // Вывод нужно постоянно дренировать, иначе пайп (~64 КБ)
                // переполнится и nfqws зависнет на записи; заодно каждая строка
                // попадает в журнал режима разработчика.
                val proc = process!!
                Thread {
                    proc.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { AppLog.i(NFQWS_TAG, it) }
                    }
                }.apply {
                    isDaemon = true
                    start()
                }
                Thread {
                    proc.errorStream.bufferedReader().useLines { lines ->
                        lines.forEach { AppLog.w(NFQWS_TAG, it) }
                    }
                }.apply {
                    isDaemon = true
                    start()
                }
                Thread.sleep(1_500)
                if (!proc.isAliveCompat()) {
                    throw IllegalStateException("nfqws exited: " + proc.exitValue())
                }
                _state.value = ZapretState.Running
                _statusText.value = getString(R.string.zapret_running_status)
                publish(ZapretState.Running)
            }
        } catch (error: Throwable) {
            AppLog.e(TAG, "Failed to start zapret engine", error)
            // A failed start must roll back: iptables rules from the script may
            // already be installed while the queue is dead.
            cleanupEngine()
            _state.value = ZapretState.Failed
            _statusText.value = error.message ?: getString(R.string.zapret_failed)
            publish(ZapretState.Failed)
            stopSelf()
        }
    }

    /*
     * Shares the zapret state with the rest of the shell (status button, tile,
     * widgets, ControlReceiver) through the same appStatus + broadcasts the
     * byedpi services use.
     */
    private fun publish(state: ZapretState) {
        setStatus(
            if (state == ZapretState.Running) AppStatus.Running else AppStatus.Halted,
            Mode.Zapret,
        )
        val action = when (state) {
            ZapretState.Running -> STARTED_BROADCAST
            ZapretState.Halted -> STOPPED_BROADCAST
            ZapretState.Failed -> FAILED_BROADCAST
        }
        val intent = Intent(action)
            .putExtra(SENDER, Sender.Zapret.ordinal)
            .setPackage(applicationContext.packageName)
        sendBroadcast(intent)
        VpnWidgets.updateAll(this)
    }

    private suspend fun cleanupEngine() {
        withContext(Dispatchers.IO) {
            runCatching { process?.destroy() }
            process = null
            val cleanup = buildString {
                for (tool in listOf("iptables", "ip6tables")) {
                    // -D removes one occurrence per call; delete any duplicates.
                    append("for i in 1 2 3 4; do $tool -t mangle -D OUTPUT -j LIMEFLOW 2>/dev/null; done\n")
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
            AppLog.i(TAG, "Zapret engine cleaned up")
        }
    }

    private fun buildScript(args: List<String>): String {
        val binary = applicationInfo.nativeLibraryDir + "/libnfqws.so"
        val portsTcp = "80,443,2053,2083,2087,2096,8443"
        val portsUdp = "443,19294:19344,50000:50100"
        // Every argument is single-quoted for sh: config tokens may contain quotes
        // or shell metacharacters, and one unbalanced character used to swallow
        // the whole command line.
        val nfqArgs = args.joinToString(" ") { arg -> ZapretStrategies.quoteForShell(arg) }
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
        cleanupEngine()
        _state.value = ZapretState.Halted
        _statusText.value = getString(R.string.zapret_stopped_status)
        publish(ZapretState.Halted)
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
