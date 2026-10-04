package io.github.dovecoteescapee.byedpi.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import io.github.dovecoteescapee.byedpi.data.WIDGET_TOGGLE_ACTION

/**
 * 2x2 status widget: connection state, current strategy name and a toggle
 * pill. The card itself opens the app.
 */
class VpnWidget2x2Receiver : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
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
