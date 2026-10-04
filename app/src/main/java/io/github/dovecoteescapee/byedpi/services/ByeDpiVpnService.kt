package io.github.dovecoteescapee.byedpi.services

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.lifecycle.lifecycleScope
import io.github.dovecoteescapee.byedpi.R
import io.github.dovecoteescapee.byedpi.activities.MainActivity
import io.github.dovecoteescapee.byedpi.activities.AppFilterActivity
import io.github.dovecoteescapee.byedpi.core.ByeDpiProxy
import io.github.dovecoteescapee.byedpi.core.ByeDpiProxyPreferences
import io.github.dovecoteescapee.byedpi.core.TProxyService
import io.github.dovecoteescapee.byedpi.data.*
import io.github.dovecoteescapee.byedpi.receiver.ScreenEventsController
import io.github.dovecoteescapee.byedpi.utility.*
import io.github.dovecoteescapee.byedpi.widget.VpnWidgets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale

class ByeDpiVpnService : LifecycleVpnService() {
    private val byeDpiProxy = ByeDpiProxy()
    private var proxyJob: Job? = null
    private var trafficJob: Job? = null
    private var tunFd: ParcelFileDescriptor? = null
    private val mutex = Mutex()
    private var stopping: Boolean = false

    companion object {
        private val TAG: String = ByeDpiVpnService::class.java.simpleName
        private const val FOREGROUND_SERVICE_ID: Int = 1
        private const val NOTIFICATION_CHANNEL_ID: String = "LimeFlowVpnStats"

        private var status: ServiceStatus = ServiceStatus.Disconnected
    }

    override fun onCreate() {
        super.onCreate()
        // Сервис может стартовать без активности — verbose берём из настроек здесь.
        AppLog.verbose = getPreferences().getBoolean("developer_mode", false)
        registerNotificationChannel(
            this,
            NOTIFICATION_CHANNEL_ID,
            R.string.vpn_channel_name,
        )
        ScreenEventsController.register(this)
    }

    override fun onDestroy() {
        ScreenEventsController.unregister(this)
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // Android requires a service started with startForegroundService() to
        // become foreground immediately, even while the native engine starts.
        startForeground()
        return when (val action = intent?.action) {
            START_ACTION -> {
                lifecycleScope.launch { start() }
                START_STICKY
            }

            STOP_ACTION -> {
                lifecycleScope.launch { stop() }
                START_NOT_STICKY
            }

            else -> {
                AppLog.w(TAG, "Unknown action: $action")
                START_NOT_STICKY
            }
        }
    }

    override fun onRevoke() {
        AppLog.i(TAG, "VPN revoked")
        lifecycleScope.launch { stop() }
    }

    private suspend fun start() {
        AppLog.i(TAG, "Starting")

        if (status == ServiceStatus.Connected) {
            Log.w(TAG, "VPN already connected")
            return
        }

        try {
            mutex.withLock {
                startProxy()
                startTun2Socks()
            }
            TrafficStatsStore.startSession()
            updateStatus(ServiceStatus.Connected)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            Log.e(TAG, "Failed to start VPN", error)
            stop()
            updateStatus(ServiceStatus.Failed)
        }
    }

    private fun startForeground() {
        val notification: Notification = createNotification()
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

    private suspend fun stop() {
        AppLog.i(TAG, "Stopping")

        mutex.withLock {
            stopping = true
            try {
                stopTun2Socks()
                stopProxy()
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                AppLog.e(TAG, "Failed to stop VPN", error)
            } finally {
                stopping = false
            }
        }

        updateStatus(ServiceStatus.Disconnected)
        stopSelf()
    }

    private suspend fun startProxy() {
        AppLog.i(TAG, "Starting proxy")

        if (proxyJob != null) {
            AppLog.w(TAG, "Proxy fields not null")
            throw IllegalStateException("Proxy fields not null")
        }

        val preferences = getByeDpiPreferences()

        proxyJob = lifecycleScope.launch(Dispatchers.IO) {
            val code = try {
                byeDpiProxy.startProxy(preferences)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                AppLog.e(TAG, "Native proxy failed during startup", error)
                withContext(Dispatchers.Main) {
                    lifecycleScope.launch {
                        stop()
                        updateStatus(ServiceStatus.Failed)
                    }
                }
                return@launch
            }

            withContext(Dispatchers.Main) {
                if (code != 0) {
                    AppLog.e(TAG, "Proxy stopped with code $code")
                    lifecycleScope.launch {
                        stop()
                        updateStatus(ServiceStatus.Failed)
                    }
                } else {
                    if (!stopping) {
                        lifecycleScope.launch { stop() }
                    }
                }
            }
        }

        delay(200)
        if (proxyJob?.isCompleted != false) {
            proxyJob = null
            throw IllegalStateException("Proxy rejected the selected strategy")
        }

        AppLog.i(TAG, "Proxy started")
    }

    private suspend fun stopProxy() {
        AppLog.i(TAG, "Stopping proxy")

        if (proxyJob == null) {
            AppLog.w(TAG, "Proxy already disconnected")
            return
        }

        runCatching { byeDpiProxy.stopProxy() }
            .onFailure { AppLog.e(TAG, "Graceful proxy stop failed", it) }
        proxyJob?.cancel()
        val completed = withTimeoutOrNull(2_000) {
            proxyJob?.join()
            true
        }
        if (completed == null) {
            AppLog.w(TAG, "Proxy did not stop in time; forcing socket close")
            runCatching { byeDpiProxy.jniForceClose() }
                .onFailure { AppLog.e(TAG, "Forced proxy close failed", it) }
        }
        proxyJob = null

        AppLog.i(TAG, "Proxy stopped")
    }

    private fun startTun2Socks() {
        AppLog.i(TAG, "Starting tun2socks")

        if (tunFd != null) {
            throw IllegalStateException("VPN field not null")
        }

        val sharedPreferences = getPreferences()
        val port = sharedPreferences.getString("byedpi_proxy_port", null)?.toInt() ?: 1080
        val dns = sharedPreferences.getStringNotNull("dns_ip", "1.1.1.1")
        val ipv6 = sharedPreferences.getBoolean("ipv6_enable", true)

        val tun2socksConfig = """
        | tunnel:
        |   mtu: 8500
        | misc:
        |   task-stack-size: 81920
        | socks5:
        |   address: 127.0.0.1
        |   port: $port
        |   udp: udp
        """.trimMargin("| ")

        val configPath = try {
            File.createTempFile("config", "tmp", cacheDir).apply {
                writeText(tun2socksConfig)
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "Failed to create config file", e)
            throw e
        }

        val fd = createBuilder(dns, ipv6).establish()
            ?: throw IllegalStateException("VPN connection failed")

        this.tunFd = fd

        TProxyService.TProxyStartService(configPath.absolutePath, fd.fd)
        startTrafficUpdates()

        AppLog.i(TAG, "Tun2Socks started")
    }

    private fun stopTun2Socks() {
        AppLog.i(TAG, "Stopping tun2socks")

        if (tunFd == null) {
            AppLog.w(TAG, "Tun2Socks already stopped")
            return
        }

        trafficJob?.cancel()
        trafficJob = null
        runCatching { TProxyService.TProxyStopService() }
            .onFailure { AppLog.e(TAG, "Failed to stop Tun2Socks", it) }

        try {
            File(cacheDir, "config.tmp").delete()
        } catch (e: SecurityException) {
            AppLog.e(TAG, "Failed to delete config file", e)
        }

        tunFd?.close() ?: AppLog.w(TAG, "VPN not running")
        tunFd = null

        AppLog.i(TAG, "Tun2socks stopped")
    }

    private fun getByeDpiPreferences(): ByeDpiProxyPreferences =
        ByeDpiProxyPreferences.fromSharedPreferences(getPreferences())

    private fun updateStatus(newStatus: ServiceStatus) {
        AppLog.d(TAG, "VPN status changed from $status to $newStatus")

        status = newStatus

        setStatus(
            when (newStatus) {
                ServiceStatus.Connected -> AppStatus.Running

                ServiceStatus.Disconnected,
                ServiceStatus.Failed -> {
                    proxyJob = null
                    AppStatus.Halted
                }
            },
            Mode.VPN
        )

        val intent = Intent(
            when (newStatus) {
                ServiceStatus.Connected -> STARTED_BROADCAST
                ServiceStatus.Disconnected -> STOPPED_BROADCAST
                ServiceStatus.Failed -> FAILED_BROADCAST
            }
        )
        intent.putExtra(SENDER, Sender.VPN.ordinal)
        sendBroadcast(intent)
        VpnWidgets.updateAll(this)
    }

    private fun createNotification(
        content: CharSequence = getString(R.string.vpn_notification_content),
        bigText: CharSequence? = null,
    ): Notification =
        createConnectionNotification(
            this,
            NOTIFICATION_CHANNEL_ID,
            R.string.notification_title,
            content,
            ByeDpiVpnService::class.java,
            bigText = bigText,
        )

    private fun startTrafficUpdates() {
        trafficJob?.cancel()
        trafficJob = lifecycleScope.launch(Dispatchers.IO) {
            delay(1_000)
            var prevSent = -1L
            var prevReceived = -1L
            while (isActive) {
                val stats = runCatching { TProxyService.TProxyGetStats() }.getOrNull()
                    ?.takeIf { it.size >= 4 }
                    ?: longArrayOf(0, 0, 0, 0)
                val sent = stats[1].coerceAtLeast(0)
                val received = stats[3].coerceAtLeast(0)
                if (prevSent >= 0) {
                    TrafficStatsStore.add(this@ByeDpiVpnService, sent - prevSent, received - prevReceived)
                }
                val content = getString(
                    R.string.vpn_notification_traffic,
                    formatTraffic(sent + received),
                )
                // Optional per-second speed line; off by default, the loop then
                // keeps its original cadence.
                val showSpeed = getPreferences().getBoolean("show_speed_in_notification", false)
                val bigText = if (showSpeed && prevSent >= 0) {
                    getString(
                        R.string.notification_speed,
                        formatTraffic((sent - prevSent).coerceAtLeast(0)),
                        formatTraffic((received - prevReceived).coerceAtLeast(0)),
                    )
                } else {
                    null
                }
                getSystemService(NotificationManager::class.java)?.notify(
                    FOREGROUND_SERVICE_ID,
                    createNotification(content, bigText),
                )
                prevSent = sent
                prevReceived = received
                delay(if (showSpeed) 1_000 else 3_000)
            }
        }
    }

    private fun formatTraffic(bytes: Long): String = when {
        bytes < 1024L * 1024L ->
            String.format(Locale.US, "%.1f КБ", bytes / 1024.0)
        bytes < 1024L * 1024L * 1024L ->
            String.format(Locale.US, "%.1f МБ", bytes / (1024.0 * 1024.0))
        else ->
            String.format(Locale.US, "%.2f ГБ", bytes / (1024.0 * 1024.0 * 1024.0))
    }

    private fun createBuilder(dns: String, ipv6: Boolean): Builder {
        AppLog.d(TAG, "DNS: $dns")
        val builder = Builder()
        builder.setSession("LimeFlow")
        builder.setConfigureIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
        )

        builder.addAddress("10.10.10.10", 32)
            .addRoute("0.0.0.0", 0)

        if (ipv6) {
            builder.addAddress("fd00::1", 128)
                .addRoute("::", 0)
        }

        if (dns.isNotBlank()) {
            builder.addDnsServer(dns)
            // Keep name resolution alive on mobile networks where one public
            // resolver is intermittently filtered. Respect custom DNS values.
            if (dns == "1.1.1.1") {
                builder.addDnsServer("8.8.8.8")
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        applyApplicationFilter(builder)

        return builder
    }

    private fun applyApplicationFilter(builder: Builder) {
        val preferences = getPreferences()
        AppFilterActivity.ensureTelegramExcludedByDefault(preferences)
        val mode = preferences.getString(
            AppFilterActivity.FILTER_MODE,
            AppFilterActivity.MODE_ALL,
        )
        val packages = preferences.getStringSet(
            AppFilterActivity.FILTER_PACKAGES,
            emptySet(),
        ).orEmpty().filter { it != applicationContext.packageName }

        if (mode == AppFilterActivity.MODE_INCLUDE && packages.isNotEmpty()) {
            var added = 0
            packages.forEach { packageName ->
                runCatching { builder.addAllowedApplication(packageName) }
                    .onSuccess { added++ }
                    .onFailure { AppLog.w(TAG, "Unable to include $packageName", it) }
            }
            if (added > 0) return
        }

        runCatching { builder.addDisallowedApplication(applicationContext.packageName) }
        if (mode == AppFilterActivity.MODE_EXCLUDE) {
            packages.forEach { packageName ->
                runCatching { builder.addDisallowedApplication(packageName) }
                    .onFailure { AppLog.w(TAG, "Unable to exclude $packageName", it) }
            }
        }
    }
}
