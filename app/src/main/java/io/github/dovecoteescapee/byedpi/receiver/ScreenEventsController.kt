package io.github.dovecoteescapee.byedpi.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.dovecoteescapee.byedpi.data.AppStatus
import io.github.dovecoteescapee.byedpi.services.ServiceManager
import io.github.dovecoteescapee.byedpi.services.appStatus
import io.github.dovecoteescapee.byedpi.utility.getPreferences
import io.github.dovecoteescapee.byedpi.utility.mode

/**
 * Dynamic receiver for the screen-lock lifecycle options. ACTION_SCREEN_OFF
 * and ACTION_USER_PRESENT cannot be received by manifest components, so the
 * services register it while running and release it on shutdown.
 */
object ScreenEventsController {
    private val TAG: String = ScreenEventsController::class.java.simpleName

    @Volatile
    private var receiver: BroadcastReceiver? = null

    @Synchronized
    fun register(context: Context) {
        if (receiver != null) return
        val appContext = context.applicationContext
        val registered = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val preferences = context.getPreferences()
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        if (preferences.getBoolean("disconnect_on_screen_off", false) &&
                            appStatus.first == AppStatus.Running
                        ) {
                            Log.i(TAG, "Screen locked, stopping")
                            ServiceManager.stop(context)
                        }
                    }

                    Intent.ACTION_USER_PRESENT -> {
                        if (preferences.getBoolean("connect_on_unlock", false) &&
                            appStatus.first != AppStatus.Running
                        ) {
                            Log.i(TAG, "Device unlocked, connecting")
                            ServiceManager.start(context)
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(
            appContext,
            registered,
            filter,
            // Protected system broadcasts are still delivered to
            // not-exported receivers.
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiver = registered
    }

    @Synchronized
    fun unregister(context: Context) {
        val registered = receiver ?: return
        receiver = null
        runCatching { context.applicationContext.unregisterReceiver(registered) }
            .onFailure { Log.w(TAG, "Receiver was not registered", it) }
    }
}
