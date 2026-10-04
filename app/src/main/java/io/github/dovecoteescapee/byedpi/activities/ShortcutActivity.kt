package io.github.dovecoteescapee.byedpi.activities

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import io.github.dovecoteescapee.byedpi.R
import io.github.dovecoteescapee.byedpi.data.AppStatus
import io.github.dovecoteescapee.byedpi.data.CONTROL_ACTION_CONNECT
import io.github.dovecoteescapee.byedpi.data.CONTROL_ACTION_DISCONNECT
import io.github.dovecoteescapee.byedpi.data.CONTROL_ACTION_TOGGLE
import io.github.dovecoteescapee.byedpi.data.VOICE_ACTION_CONNECT
import io.github.dovecoteescapee.byedpi.data.VOICE_ACTION_DISCONNECT
import io.github.dovecoteescapee.byedpi.data.VOICE_ACTION_TOGGLE
import io.github.dovecoteescapee.byedpi.services.ServiceManager
import io.github.dovecoteescapee.byedpi.services.appStatus
import io.github.dovecoteescapee.byedpi.utility.getPreferences
import io.github.dovecoteescapee.byedpi.utility.mode

/**
 * Invisible trampoline for launcher shortcuts and assistant voice commands.
 * Static shortcuts may target a non-exported activity because the system
 * launches them on the app's behalf; external apps cannot start it directly
 * (they should use ControlReceiver or limeflow:// deeplinks instead).
 */
class ShortcutActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        finishAndRemoveTask()
    }

    private fun handleIntent(intent: Intent?) {
        val (status, _) = appStatus
        when (intent?.action) {
            CONTROL_ACTION_CONNECT, VOICE_ACTION_CONNECT -> connect()
            CONTROL_ACTION_DISCONNECT, VOICE_ACTION_DISCONNECT -> disconnect(status)
            CONTROL_ACTION_TOGGLE, VOICE_ACTION_TOGGLE -> when (status) {
                AppStatus.Running -> disconnect(status)
                else -> connect()
            }
            else -> Unit
        }
    }

    private fun connect() {
        if (!ServiceManager.canStartSilently(this)) {
            // One-time VPN consent is still missing: show the real UI.
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            Toast.makeText(this, R.string.vpn_permission_denied, Toast.LENGTH_SHORT).show()
            return
        }
        ServiceManager.start(this, this.getPreferences().mode())
        Toast.makeText(this, R.string.shortcut_connecting, Toast.LENGTH_SHORT).show()
    }

    private fun disconnect(status: AppStatus) {
        if (status == AppStatus.Running) {
            ServiceManager.stop(this)
            Toast.makeText(this, R.string.shortcut_disconnecting, Toast.LENGTH_SHORT).show()
        }
    }
}
