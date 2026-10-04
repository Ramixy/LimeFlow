package io.github.dovecoteescapee.byedpi.data

import android.content.Context
import io.github.dovecoteescapee.byedpi.utility.getPreferences
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Traffic counters kept in SharedPreferences.
 *
 * Room would buy nothing here: the whole dataset is nine scalars updated once
 * a second, and the app already persists its settings in SharedPreferences.
 * The session counters stay in memory — a session ends when the service does.
 */
object TrafficStatsStore {
    private const val KEY_TX_TOTAL = "traffic_tx_total"
    private const val KEY_RX_TOTAL = "traffic_rx_total"
    private const val KEY_SECONDS_TOTAL = "traffic_seconds_total"
    private const val KEY_TX_TODAY = "traffic_tx_today"
    private const val KEY_RX_TODAY = "traffic_rx_today"
    private const val KEY_SECONDS_TODAY = "traffic_seconds_today"
    private const val KEY_DAY = "traffic_day"
    private const val KEY_TX_WEEK = "traffic_tx_week"
    private const val KEY_RX_WEEK = "traffic_rx_week"
    private const val KEY_SECONDS_WEEK = "traffic_seconds_week"
    private const val KEY_WEEK = "traffic_week"

    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    @Volatile private var sessionTx = 0L
    @Volatile private var sessionRx = 0L
    @Volatile private var sessionSeconds = 0L
    @Volatile private var lastTickAt = 0L

    data class Snapshot(
        val sessionTx: Long,
        val sessionRx: Long,
        val sessionSeconds: Long,
        val todayTx: Long,
        val todayRx: Long,
        val todaySeconds: Long,
        val weekTx: Long,
        val weekRx: Long,
        val weekSeconds: Long,
        val totalTx: Long,
        val totalRx: Long,
        val totalSeconds: Long,
    )

    @Synchronized
    fun add(context: Context, txDelta: Long, rxDelta: Long) {
        val now = System.currentTimeMillis()
        // Accumulate connected time by wall-clock deltas between engine ticks;
        // a cap keeps the first tick after a long idle gap honest.
        val secondsDelta = if (lastTickAt == 0L) 0L else ((now - lastTickAt) / 1000).coerceIn(0, 10)
        lastTickAt = now
        if (txDelta <= 0 && rxDelta <= 0 && secondsDelta <= 0) return

        sessionTx += txDelta.coerceAtLeast(0)
        sessionRx += rxDelta.coerceAtLeast(0)
        sessionSeconds += secondsDelta

        val preferences = context.getPreferences()
        val editor = preferences.edit()
        rollDay(preferences, editor, now)
        rollWeek(preferences, editor, now)
        editor.putLong(KEY_TX_TOTAL, preferences.getLong(KEY_TX_TOTAL, 0) + txDelta.coerceAtLeast(0))
            .putLong(KEY_RX_TOTAL, preferences.getLong(KEY_RX_TOTAL, 0) + rxDelta.coerceAtLeast(0))
            .putLong(KEY_SECONDS_TOTAL, preferences.getLong(KEY_SECONDS_TOTAL, 0) + secondsDelta)
            .putLong(KEY_TX_TODAY, preferences.getLong(KEY_TX_TODAY, 0) + txDelta.coerceAtLeast(0))
            .putLong(KEY_RX_TODAY, preferences.getLong(KEY_RX_TODAY, 0) + rxDelta.coerceAtLeast(0))
            .putLong(KEY_SECONDS_TODAY, preferences.getLong(KEY_SECONDS_TODAY, 0) + secondsDelta)
            .putLong(KEY_TX_WEEK, preferences.getLong(KEY_TX_WEEK, 0) + txDelta.coerceAtLeast(0))
            .putLong(KEY_RX_WEEK, preferences.getLong(KEY_RX_WEEK, 0) + rxDelta.coerceAtLeast(0))
            .putLong(KEY_SECONDS_WEEK, preferences.getLong(KEY_SECONDS_WEEK, 0) + secondsDelta)
            .apply()
    }

    /** Starts a new session counter set (called when the VPN connects). */
    @Synchronized
    fun startSession() {
        sessionTx = 0
        sessionRx = 0
        sessionSeconds = 0
        lastTickAt = System.currentTimeMillis()
    }

    @Synchronized
    fun reset(context: Context) {
        sessionTx = 0
        sessionRx = 0
        sessionSeconds = 0
        lastTickAt = 0
        context.getPreferences().edit()
            .remove(KEY_TX_TOTAL).remove(KEY_RX_TOTAL).remove(KEY_SECONDS_TOTAL)
            .remove(KEY_TX_TODAY).remove(KEY_RX_TODAY).remove(KEY_SECONDS_TODAY).remove(KEY_DAY)
            .remove(KEY_TX_WEEK).remove(KEY_RX_WEEK).remove(KEY_SECONDS_WEEK).remove(KEY_WEEK)
            .apply()
    }

    @Synchronized
    fun snapshot(context: Context): Snapshot {
        val preferences = context.getPreferences()
        val now = System.currentTimeMillis()
        val day = dayFormat.format(Date(now))
        val week = weekKey(now)
        val todayStored = preferences.getString(KEY_DAY, null) == day
        val weekStored = preferences.getString(KEY_WEEK, null) == week
        return Snapshot(
            sessionTx = sessionTx,
            sessionRx = sessionRx,
            sessionSeconds = sessionSeconds,
            todayTx = if (todayStored) preferences.getLong(KEY_TX_TODAY, 0) else 0,
            todayRx = if (todayStored) preferences.getLong(KEY_RX_TODAY, 0) else 0,
            todaySeconds = if (todayStored) preferences.getLong(KEY_SECONDS_TODAY, 0) else 0,
            weekTx = if (weekStored) preferences.getLong(KEY_TX_WEEK, 0) else 0,
            weekRx = if (weekStored) preferences.getLong(KEY_RX_WEEK, 0) else 0,
            weekSeconds = if (weekStored) preferences.getLong(KEY_SECONDS_WEEK, 0) else 0,
            totalTx = preferences.getLong(KEY_TX_TOTAL, 0),
            totalRx = preferences.getLong(KEY_RX_TOTAL, 0),
            totalSeconds = preferences.getLong(KEY_SECONDS_TOTAL, 0),
        )
    }

    private fun rollDay(preferences: android.content.SharedPreferences, editor: android.content.SharedPreferences.Editor, now: Long) {
        val day = dayFormat.format(Date(now))
        if (preferences.getString(KEY_DAY, null) != day) {
            editor.putString(KEY_DAY, day)
                .putLong(KEY_TX_TODAY, 0)
                .putLong(KEY_RX_TODAY, 0)
                .putLong(KEY_SECONDS_TODAY, 0)
        }
    }

    private fun rollWeek(preferences: android.content.SharedPreferences, editor: android.content.SharedPreferences.Editor, now: Long) {
        val week = weekKey(now)
        if (preferences.getString(KEY_WEEK, null) != week) {
            editor.putString(KEY_WEEK, week)
                .putLong(KEY_TX_WEEK, 0)
                .putLong(KEY_RX_WEEK, 0)
                .putLong(KEY_SECONDS_WEEK, 0)
        }
    }

    /* Monday-based week key; Calendar instead of java.time for minSdk 23. */
    private fun weekKey(timeMillis: Long): String {
        val calendar = Calendar.getInstance().apply {
            firstDayOfWeek = Calendar.MONDAY
            timeInMillis = timeMillis
        }
        return "${calendar.get(Calendar.YEAR)}-W${calendar.get(Calendar.WEEK_OF_YEAR)}"
    }
}
