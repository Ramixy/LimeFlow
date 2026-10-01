package io.github.dovecoteescapee.byedpi.utility

import android.util.Log
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/*
 * Журнал приложения: дублирует записи android.util.Log в кольцевой буфер,
 * который показывает экран «Журнал» в режиме разработчика. Записи уровня
 * INFO и выше собираются всегда — по ним работает просмотр и экспорт
 * журнала; DEBUG и VERBOSE попадают в буфер только при включённом режиме
 * разработчика ([verbose]), чтобы не расходовать память в обычной работе.
 */
object AppLog {
    data class Entry(val time: Long, val level: Char, val tag: String, val message: String)

    private const val MAX_ENTRIES = 2_000
    private const val MAX_MESSAGE_LENGTH = 4_000
    private const val VERBOSE = Log.VERBOSE
    private const val DEBUG = Log.DEBUG

    /** Включается переключателем режима разработчика. */
    @Volatile
    var verbose: Boolean = false

    private val lock = Any()
    private val buffer = ArrayDeque<Entry>(MAX_ENTRIES)
    private var serial = 0L

    private val fileFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun v(tag: String, message: String, error: Throwable? = null) = log(VERBOSE, tag, message, error)
    fun d(tag: String, message: String, error: Throwable? = null) = log(DEBUG, tag, message, error)
    fun i(tag: String, message: String, error: Throwable? = null) = log(Log.INFO, tag, message, error)
    fun w(tag: String, message: String, error: Throwable? = null) = log(Log.WARN, tag, message, error)
    fun e(tag: String, message: String, error: Throwable? = null) = log(Log.ERROR, tag, message, error)

    fun log(priority: Int, tag: String, message: String, error: Throwable? = null) {
        val text = message + (error?.let { "\n${Log.getStackTraceString(it)}" } ?: "")
        Log.println(priority, tag, text)

        val level = when (priority) {
            VERBOSE -> 'V'
            DEBUG -> 'D'
            Log.INFO -> 'I'
            Log.WARN -> 'W'
            else -> 'E'
        }
        if (!verbose && (level == 'V' || level == 'D')) return

        synchronized(lock) {
            buffer.addLast(Entry(System.currentTimeMillis(), level, tag, text.take(MAX_MESSAGE_LENGTH)))
            while (buffer.size > MAX_ENTRIES) buffer.removeFirst()
            serial++
        }
    }

    /** Меняется при каждой новой записи: экран журнала по нему понимает, что пора перерисоваться. */
    fun serial(): Long = synchronized(lock) { serial }

    fun snapshot(): List<Entry> = synchronized(lock) { buffer.toList() }

    fun dump(): String = synchronized(lock) {
        buffer.joinToString("\n") { entry ->
            "${fileFormat.format(Date(entry.time))} ${entry.level}/${entry.tag}: ${entry.message}"
        }
    }

    fun clear() = synchronized(lock) {
        buffer.clear()
        serial++
    }
}
