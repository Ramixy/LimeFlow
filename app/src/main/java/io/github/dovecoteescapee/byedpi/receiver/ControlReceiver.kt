package io.github.dovecoteescapee.byedpi.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.dovecoteescapee.byedpi.data.*
import io.github.dovecoteescapee.byedpi.services.ServiceManager
import io.github.dovecoteescapee.byedpi.services.appStatus
import io.github.dovecoteescapee.byedpi.utility.getPreferences
import io.github.dovecoteescapee.byedpi.utility.mode

/**
 * Automation entry point for Tasker/MacroDroid and similar tools.
 *
 * Declared in the manifest as exported with the signature-level
 * app.alt11.mobile.permission.CONTROL permission, so only apps signed with
 * the same key are granted access; deeplinks (limeflow://) cover everyone
 * else. STATUS answers with an explicit STATUS_RESPONSE broadcast back to
 * the caller.
 */
class ControlReceiver : BroadcastReceiver() {
    private val TAG: String = ControlReceiver::class.java.simpleName

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            CONTROL_ACTION_CONNECT -> {
                if (appStatus.first != AppStatus.Running && ServiceManager.canStartSilently(context)) {
                    ServiceManager.start(context)
                } else {
                    Log.i(TAG, "Connect request ignored: running or VPN consent missing")
                }
            }

            CONTROL_ACTION_DISCONNECT -> {
                if (appStatus.first == AppStatus.Running) {
                    ServiceManager.stop(context)
                }
            }

            CONTROL_ACTION_TOGGLE -> {
                if (appStatus.first == AppStatus.Running) {
                    ServiceManager.stop(context)
                } else if (ServiceManager.canStartSilently(context)) {
                    ServiceManager.start(context)
                } else {
                    Log.i(TAG, "Toggle request ignored: VPN consent missing")
                }
            }

            CONTROL_ACTION_STATUS -> sendStatusResponse(context, intent)

            else -> Log.w(TAG, "Unknown action: ${intent.action}")
        }
    }

    private fun sendStatusResponse(context: Context, request: Intent) {
        val (status, mode) = appStatus
        val response = Intent(CONTROL_ACTION_STATUS_RESPONSE)
            .setPackage(request.`package` ?: context.packageName)
            .putExtra(CONTROL_EXTRA_STATUS, if (status == AppStatus.Running) STATUS_VALUE_CONNECTED else STATUS_VALUE_DISCONNECTED)
            .putExtra(CONTROL_EXTRA_MODE, when (mode) {
                Mode.VPN -> MODE_VALUE_VPN
                Mode.Proxy -> MODE_VALUE_PROXY
                Mode.Zapret -> MODE_VALUE_ZAPRET
            })
            .putExtra(CONTROL_EXTRA_PAUSED, false)
        context.sendBroadcast(response)
    }
}
