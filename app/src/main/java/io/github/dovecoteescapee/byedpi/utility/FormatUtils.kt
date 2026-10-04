package io.github.dovecoteescapee.byedpi.utility

import java.util.Locale

fun formatTraffic(bytes: Long): String = when {
    bytes < 1024L -> String.format(Locale.US, "%d Б", bytes)
    bytes < 1024L * 1024L ->
        String.format(Locale.US, "%.1f КБ", bytes / 1024.0)
    bytes < 1024L * 1024L * 1024L ->
        String.format(Locale.US, "%.1f МБ", bytes / (1024.0 * 1024.0))
    else ->
        String.format(Locale.US, "%.2f ГБ", bytes / (1024.0 * 1024.0 * 1024.0))
}

fun formatDuration(seconds: Long): String {
    val totalMinutes = seconds / 60
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours > 0 -> String.format(Locale.US, "%d ч %02d мин", hours, minutes)
        minutes > 0 -> String.format(Locale.US, "%d мин", minutes)
        else -> String.format(Locale.US, "%d с", seconds)
    }
}
