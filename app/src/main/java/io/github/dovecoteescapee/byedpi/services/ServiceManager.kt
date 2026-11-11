package io.github.dovecoteescapee.byedpi.services

import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import io.github.dovecoteescapee.byedpi.data.AppStatus
import io.github.dovecoteescapee.byedpi.data.Mode
import io.github.dovecoteescapee.byedpi.data.START_ACTION
import io.github.dovecoteescapee.byedpi.data.STOP_ACTION
import io.github.dovecoteescapee.byedpi.utility.AppLog
import io.github.dovecoteescapee.byedpi.utility.getPreferences
import io.github.dovecoteescapee.byedpi.utility.mode

object ServiceManager {
    private val TAG: String = ServiceManager::class.java.simpleName

    /** The byedpi mode the user picked in settings: VPN or local proxy. */
    fun engineMode(context: Context): Mode = context.getPreferences().mode()

    /** Starts the selected mode; the tile, widgets, shortcuts and automations all go through here. */
    fun start(context: Context, mode: Mode = engineMode(context)) {
        when (mode) {
            Mode.VPN -> {
                AppLog.i(TAG, "Starting VPN")
                val intent = Intent(context, ByeDpiVpnService::class.java)
                intent.action = START_ACTION
                ContextCompat.startForegroundService(context, intent)
            }

            Mode.Proxy -> {
                AppLog.i(TAG, "Starting proxy")
                val intent = Intent(context, ByeDpiProxyService::class.java)
                intent.action = START_ACTION
                ContextCompat.startForegroundService(context, intent)
            }
        }
    }

    fun stop(context: Context) {
        val (status, runningMode) = appStatus
        val mode = if (status == AppStatus.Running) runningMode else engineMode(context)
        when (mode) {
            Mode.VPN -> {
                AppLog.i(TAG, "Stopping VPN")
                val intent = Intent(context, ByeDpiVpnService::class.java)
                intent.action = STOP_ACTION
                ContextCompat.startForegroundService(context, intent)
            }

            Mode.Proxy -> {
                AppLog.i(TAG, "Stopping proxy")
                val intent = Intent(context, ByeDpiProxyService::class.java)
                intent.action = STOP_ACTION
                ContextCompat.startForegroundService(context, intent)
            }
        }
    }

    fun toggle(context: Context) {
        val (status, _) = appStatus
        if (status == AppStatus.Running) {
            stop(context)
        } else {
            start(context)
        }
    }

    /**
     * A silent start (widget, shortcut, boot, automation) must not block on the
     * VPN consent dialog.
     */
    fun canStartSilently(context: Context): Boolean = when (engineMode(context)) {
        Mode.VPN -> VpnService.prepare(context) == null
        Mode.Proxy -> true
    }
}
