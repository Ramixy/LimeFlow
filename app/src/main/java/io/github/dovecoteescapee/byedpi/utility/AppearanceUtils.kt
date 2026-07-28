package io.github.dovecoteescapee.byedpi.utility

import android.app.Activity
import io.github.dovecoteescapee.byedpi.R

fun Activity.applyLimeFlowPalette() {
    val preferences = getPreferences()
    val overlay = if (preferences.getBoolean("app_dynamic_colors", true)) {
        R.style.ThemeOverlay_LimeFlow_Dynamic
    } else when (preferences.getString("app_palette", "limeflow")) {
        "limeflow" -> R.style.ThemeOverlay_LimeFlow_Blue
        "indigo" -> R.style.ThemeOverlay_LimeFlow_Indigo
        "forest" -> R.style.ThemeOverlay_LimeFlow_Forest
        "espresso" -> R.style.ThemeOverlay_LimeFlow_Espresso
        else -> R.style.ThemeOverlay_LimeFlow_Blue
    }
    theme.applyStyle(overlay, true)
}
