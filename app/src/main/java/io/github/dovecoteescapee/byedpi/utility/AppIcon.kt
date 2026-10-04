package io.github.dovecoteescapee.byedpi.utility

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * Launcher icon variants declared as activity-aliases. Switching enables the
 * chosen alias and disables the rest; DONT_KILL_APP keeps the running VPN
 * service alive through the swap.
 */
object AppIcon {
    const val PREF_KEY = "app_icon"

    private const val PACKAGE = "io.github.dovecoteescapee.byedpi"

    private val ALIASES = linkedMapOf(
        "default" to "$PACKAGE.activities.MainActivityDefault",
        "dark" to "$PACKAGE.activities.MainActivityDark",
        "mono" to "$PACKAGE.activities.MainActivityMono",
        "lime" to "$PACKAGE.activities.MainActivityLime",
    )

    val KEYS = ALIASES.keys.toList()

    fun current(preferences: android.content.SharedPreferences): String =
        preferences.getString(PREF_KEY, "default") ?: "default"

    fun apply(context: Context, key: String) {
        if (key !in ALIASES) return
        val manager = context.packageManager
        ALIASES.forEach { (aliasKey, className) ->
            val component = ComponentName(PACKAGE, className)
            val targetState = if (aliasKey == key) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            if (manager.getComponentEnabledSetting(component) != targetState) {
                manager.setComponentEnabledSetting(
                    component,
                    targetState,
                    PackageManager.DONT_KILL_APP,
                )
            }
        }
        context.getPreferences().edit().putString(PREF_KEY, key).apply()
    }
}
