package io.github.dovecoteescapee.byedpi.utility

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
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
        "amoled" -> R.style.ThemeOverlay_LimeFlow_Amoled
        else -> R.style.ThemeOverlay_LimeFlow_Blue
    }
    theme.applyStyle(overlay, true)
}

/**
 * User-facing text size (normal/large/small) applied as a fontScale wrapper
 * around the activity base context.
 */
fun Context.withTextScale(): Context {
    val scale = when (getPreferences().getString("text_scale", "normal")) {
        "large" -> 1.15f
        "small" -> 0.9f
        else -> 1f
    }
    if (scale == 1f) return this
    val configuration = Configuration(resources.configuration)
    configuration.fontScale = scale
    return createConfigurationContext(configuration)
}
