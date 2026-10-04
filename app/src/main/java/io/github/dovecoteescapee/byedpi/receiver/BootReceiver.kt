package io.github.dovecoteescapee.byedpi.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.dovecoteescapee.byedpi.services.ServiceManager
import io.github.dovecoteescapee.byedpi.utility.getPreferences
import io.github.dovecoteescapee.byedpi.utility.mode

/**
 * Auto-connect after the device boots. A silent start is skipped when the
 * VPN consent dialog was never accepted: showing it from the background is
 * not allowed on modern Android anyway.
 */
class BootReceiver : BroadcastReceiver() {
    private val TAG: String = BootReceiver::class.java.simpleName

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }

        val preferences = context.getPreferences()
        if (!preferences.getBoolean("autoconnect_on_boot", false)) {
            Log.i(TAG, "Boot completed, auto-connect disabled")
            return
        }
        if (!ServiceManager.canStartSilently(context)) {
            Log.i(TAG, "Boot completed, VPN consent missing")
            return
        }

        ServiceManager.start(context)
        Log.i(TAG, "Boot completed, auto-connect requested")
    }
}
