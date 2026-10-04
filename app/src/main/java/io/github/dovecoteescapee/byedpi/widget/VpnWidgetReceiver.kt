package io.github.dovecoteescapee.byedpi.widget

import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import io.github.dovecoteescapee.byedpi.data.WIDGET_TOGGLE_ACTION

/**
 * 1x1 quick-toggle widget. The status broadcasts it mirrors are sent with an
 * explicit package, so this manifest receiver receives them despite being
 * non-exported.
 */
class VpnWidgetReceiver : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: android.appwidget.AppWidgetManager, appWidgetIds: IntArray) {
        VpnWidgets.updateAll(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == WIDGET_TOGGLE_ACTION) {
            VpnWidgets.handleToggle(context)
        }
        VpnWidgets.updateAll(context)
    }
}
