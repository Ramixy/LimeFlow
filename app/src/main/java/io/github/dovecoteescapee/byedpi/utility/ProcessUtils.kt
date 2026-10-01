package io.github.dovecoteescapee.byedpi.utility

import android.os.Build
import java.util.concurrent.TimeUnit

/*
 * Timed-ожидание, принудительный останов и isAlive у процесса появились
 * только в API 26 (Process#waitFor(timeout), destroyForcibly, isAlive),
 * а minSdk приложения — 23. На старых версиях делаем то же самое опросом
 * exitValue(): NoSuchMethodError на Android 6–7 lint-выпуск NewApi и ловит.
 */
fun Process.waitForTimed(timeout: Long, unit: TimeUnit): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        return waitFor(timeout, unit)
    }
    val deadline = System.nanoTime() + unit.toNanos(timeout)
    while (System.nanoTime() < deadline) {
        if (hasExited()) return true
        Thread.sleep(50)
    }
    return hasExited()
}

fun Process.destroyForciblyCompat() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        destroyForcibly()
    } else {
        destroy()
    }
}

fun Process.isAliveCompat(): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) return isAlive
    return !hasExited()
}

private fun Process.hasExited(): Boolean =
    runCatching { exitValue() }.isSuccess
