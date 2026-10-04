package io.github.dovecoteescapee.byedpi.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import io.github.dovecoteescapee.byedpi.R
import io.github.dovecoteescapee.byedpi.activities.MainActivity
import io.github.dovecoteescapee.byedpi.data.AppStatus
import io.github.dovecoteescapee.byedpi.data.WIDGET_TOGGLE_ACTION
import io.github.dovecoteescapee.byedpi.data.FlowsealProfiles
import io.github.dovecoteescapee.byedpi.services.ServiceManager
import io.github.dovecoteescapee.byedpi.services.appStatus
import io.github.dovecoteescapee.byedpi.utility.getPreferences
import io.github.dovecoteescapee.byedpi.utility.mode

/**
 * Shared rendering for both home-screen widgets. They are refreshed from the
 * service status broadcasts (STARTED/STOPPED/FAILED), on app start and from
 * the system's periodic APPWIDGET_UPDATE.
 */
object VpnWidgets {
    private val TAG: String = VpnWidgets::class.java.simpleName

    fun updateAll(context: Context) {
        try {
            val manager = AppWidgetManager.getInstance(context) ?: return
            update1x1(context, manager)
            update2x2(context, manager)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update widgets", e)
        }
    }

    fun handleToggle(context: Context) {
        val (status, _) = appStatus
        if (status == AppStatus.Running) {
            ServiceManager.stop(context)
            return
        }
        if (ServiceManager.canStartSilently(context)) {
            ServiceManager.start(context)
        } else {
            // VPN consent was never granted; a silent start would hang.
            openApp(context)
        }
    }

    /* The strategy line follows the selected engine: byedpi catalog or zapret. */
    private fun currentStrategyName(context: Context): String =
        if (ServiceManager.isZapretEngine(context)) {
            val id = ServiceManager.zapretStrategy(context)
            io.github.dovecoteescapee.byedpi.zapret.ZapretStrategies.list()
                .firstOrNull { it.id == id }?.name ?: "Zapret"
        } else {
            FlowsealProfiles.selected(context.getPreferences()).name
        }

    private fun update1x1(context: Context, manager: AppWidgetManager) {
        val ids = manager.getAppWidgetIds(ComponentName(context, VpnWidgetReceiver::class.java))
        if (ids.isEmpty()) return

        val running = appStatus.first == AppStatus.Running
        val views = RemoteViews(context.packageName, R.layout.widget_vpn_1x1)
        views.setInt(
            R.id.widget_root,
            "setBackgroundResource",
            if (running) R.drawable.widget_bg_circle_on else R.drawable.widget_bg_circle,
        )
        views.setOnClickPendingIntent(R.id.widget_root, togglePendingIntent(
            context, VpnWidgetReceiver::class.java
        ))
        manager.updateAppWidget(ids, views)
    }

    private fun update2x2(context: Context, manager: AppWidgetManager) {
        val ids = manager.getAppWidgetIds(ComponentName(context, VpnWidget2x2Receiver::class.java))
        if (ids.isEmpty()) return

        val running = appStatus.first == AppStatus.Running
        val views = RemoteViews(context.packageName, R.layout.widget_vpn_2x2)
        views.setTextViewText(
            R.id.widget_status,
            context.getString(if (running) R.string.widget_status_on else R.string.widget_status_off),
        )
        views.setTextColor(
            R.id.widget_status,
            context.getColor(if (running) R.color.widget_connected else R.color.widget_idle),
        )
        views.setTextViewText(R.id.widget_strategy, currentStrategyName(context))
        views.setTextViewText(
            R.id.widget_toggle,
            context.getString(if (running) R.string.widget_action_off else R.string.widget_action_on),
        )
        views.setTextColor(
            R.id.widget_toggle,
            context.getColor(if (running) R.color.widget_connected else R.color.widget_text),
        )
        // The card itself opens the app; only the pill toggles the connection.
        views.setOnClickPendingIntent(R.id.widget_root, openAppPendingIntent(context))
        views.setOnClickPendingIntent(
            R.id.widget_toggle,
            togglePendingIntent(context, VpnWidget2x2Receiver::class.java),
        )
        manager.updateAppWidget(ids, views)
    }

    private fun togglePendingIntent(context: Context, receiver: Class<*>): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, receiver).setAction(WIDGET_TOGGLE_ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun openAppPendingIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun openApp(context: Context) {
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
