package io.github.dovecoteescapee.byedpi.services

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.dovecoteescapee.byedpi.data.AppStatus
import io.github.dovecoteescapee.byedpi.data.Mode
import io.github.dovecoteescapee.byedpi.data.START_ACTION
import io.github.dovecoteescapee.byedpi.data.STOP_ACTION
import io.github.dovecoteescapee.byedpi.utility.AppLog
import io.github.dovecoteescapee.byedpi.utility.getPreferences
import io.github.dovecoteescapee.byedpi.utility.mode
import io.github.dovecoteescapee.byedpi.zapret.ZapretEngineService
import io.github.dovecoteescapee.byedpi.zapret.ZapretStrategies

object ServiceManager {
    private val TAG: String = ServiceManager::class.java.simpleName

    const val ENGINE_MODE_KEY = "engine_mode"
    const val ENGINE_BYEDPI = "byedpi"
    const val ENGINE_ZAPRET = "zapret"
    const val ZAPRET_STRATEGY_KEY = "zapret_strategy_id"
    const val ZAPRET_DEFAULT_STRATEGY = "general (ALT11)"

    /** The engine the user picked in settings: our byedpi or the root zapret. */
    fun engineMode(context: Context): Mode =
        if (context.getPreferences().getString(ENGINE_MODE_KEY, ENGINE_BYEDPI) == ENGINE_ZAPRET) {
            Mode.Zapret
        } else {
            context.getPreferences().mode()
        }

    fun isZapretEngine(context: Context): Boolean =
        engineMode(context) == Mode.Zapret

    fun zapretStrategy(context: Context): String {
        // A stored strategy may have been removed from the catalog (e.g. the
        // experimental discord-voice preset of 1.4.2, which broke Discord voice
        // and was reverted). Fall back to the default instead of feeding the
        // engine a config that no longer exists.
        val stored = context.getPreferences().getString(ZAPRET_STRATEGY_KEY, null)
            ?.takeIf { it.isNotBlank() }
        val known = ZapretStrategies.list().any { it.id == stored }
        return if (known) stored!! else ZAPRET_DEFAULT_STRATEGY
    }

    /**
     * Starts the selected engine. Callers may still pass a byedpi mode; while
     * the zapret engine is selected it takes precedence, so the tile, widgets,
     * shortcuts and automations all follow the same switch.
     */
    fun start(context: Context, mode: Mode = engineMode(context)) {
        when (mode) {
            Mode.Zapret -> {
                Log.i(TAG, "Starting zapret engine")
                val strategy = zapretStrategy(context)
                context.getPreferences().edit().putString(ZAPRET_STRATEGY_KEY, strategy).apply()
                ZapretEngineService.start(context, strategy)
            }

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
            Mode.Zapret -> {
                Log.i(TAG, "Stopping zapret engine")
                ZapretEngineService.stop(context)
            }

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
     * A silent start (widget, shortcut, boot, automation) must not block on a
     * consent dialog: VPN consent for the byedpi VPN, a su prompt for zapret.
     */
    fun canStartSilently(context: Context): Boolean = when (engineMode(context)) {
        Mode.VPN -> VpnService.prepare(context) == null
        else -> true
    }
}
