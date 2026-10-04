package io.github.dovecoteescapee.byedpi.utility

import android.content.Context
import android.util.Log
import java.io.File
import java.util.ArrayDeque

/**
 * In-memory ring buffer of recent logcat lines plus an optional on-disk mirror.
 *
 * The app renders its own logs (MainActivity, services, the native engine) via
 * android.util.Log, so a background logcat stream is the single source that
 * covers both Java and native output without touching every call site.
 */
object DevLogStore {
    private const val MAX_LINES = 4000
    private const val MAX_DISK_BYTES = 2L * 1024 * 1024

    // logcat -v tag lines look like "E/Tag: msg" (level prefix, no timestamp).
    private val ERROR_LINE = Regex("(?:^|\\s)[EW]/\\w+")

    // Tags of everything this app logs (Kotlin classes + the native engines).
    private val APP_TAGS = setOf(
        "byedpivpnservice", "byedpiproxyservice", "servicemanager", "quicktileservice",
        "zapretengineservice", "zapretstrategies", "vpnwidgets", "controlreceiver",
        "bootreceiver", "screeneventscontroller", "devlogstore", "mainactivity",
        "settingsactivity", "strategytestrunner", "trafficstatsstore", "connectiontester",
        "appupdatechecker", "appfilteractivity", "hostlistactivity", "profilepickeractivity",
        "shortcutactivity", "strategyshareactivity", "trafficstatsactivity",
        "urlschemesactivity", "devlogsactivity", "onboardingactivity", "proxy", "nfqws",
    )

    private val lines = ArrayDeque<String>(MAX_LINES)
    private val times = ArrayDeque<Long>(MAX_LINES)
    private var mirror: File? = null
    private var mirrorDirty = false

    @Synchronized
    fun append(line: String) {
        if (lines.size >= MAX_LINES) {
            lines.removeFirst()
            times.removeFirst()
        }
        lines.addLast(line)
        times.addLast(System.currentTimeMillis())
        mirrorDirty = true
    }

    @Synchronized
    fun snapshot(): List<String> = lines.toList()

    /**
     * Filtered view for the logs screen: retention window, level and free-text
     * (or domain) search. Everything is already buffered in memory, so this is
     * a pure pass over at most MAX_LINES entries.
     */
    @Synchronized
    fun snapshotFiltered(
        retentionMs: Long,
        errorOnly: Boolean,
        query: String,
        appOnly: Boolean = false,
    ): List<String> {
        val cutoff = System.currentTimeMillis() - retentionMs
        val result = ArrayList<String>(lines.size)
        val timeIterator = times.iterator()
        for (line in lines) {
            val time = timeIterator.next()
            if (time < cutoff) continue
            if (errorOnly && !ERROR_LINE.containsMatchIn(line)) continue
            if (appOnly && !isAppLine(line)) continue
            if (query.isNotEmpty() && !line.contains(query, ignoreCase = true)) continue
            result.add(line)
        }
        return result
    }

    /* Vendor and ART spam (libc, Adreno, ashmem...) drowns the useful lines. */
    private fun isAppLine(line: String): Boolean {
        val tag = line.substringAfter('/').substringBefore(':').trim().lowercase()
        return tag in APP_TAGS
    }

    @Synchronized
    fun size(): Int = lines.size

    @Synchronized
    fun clear() {
        lines.clear()
        times.clear()
        mirrorDirty = true
    }

    /**
     * One text blob for copy/share. Filtered views are applied by the caller.
     */
    @Synchronized
    fun dump(): String = lines.joinToString("\n")

    fun mirrorFile(context: Context): File {
        synchronized(this) {
            val existing = mirror
            if (existing != null && !mirrorDirty) return existing
            val file = existing ?: File(context.cacheDir, "limeflow-dev.log").also { mirror = it }
            runCatching { file.writeText(dump()) }
            if (file.length() > MAX_DISK_BYTES) {
                val trimmed = file.readLines().takeLast(MAX_LINES / 2)
                file.writeText(trimmed.joinToString("\n"))
            }
            mirrorDirty = false
            return file
        }
    }

    /**
     * Reads the app's own logcat stream into the buffer, mirroring the working
     * LogManager of the Proxy section: same ProcessBuilder form, same own-pid
     * restriction, started while the logs screen is visible.
     */
    fun startStream(context: Context) {
        if (streamJob != null) return
        val pid = android.os.Process.myPid()
        streamJob = Thread {
            try {
                val process = ProcessBuilder("logcat", "-v", "tag", "--pid", pid.toString())
                    .redirectErrorStream(true)
                    .start()
                streamProcess = process
                process.inputStream.bufferedReader().useLines { seq ->
                    seq.forEach { line ->
                        if (line.isNotBlank()) append(line)
                    }
                }
            } catch (e: Exception) {
                // stopStream() closes the pipe on purpose; that teardown is
                // expected and must not spam the buffer with a stack trace.
                if (streamProcess != null) {
                    Log.w("DevLogStore", "logcat stream ended", e)
                }
            }
        }.apply {
            name = "devlog-stream"
            isDaemon = true
            start()
        }
    }

    fun stopStream() {
        val process = streamProcess
        // Flag the intentional teardown before destroying the pipe, so the
        // reader thread exits quietly.
        streamProcess = null
        streamJob = null
        runCatching { process?.destroy() }
    }

    @Volatile
    private var streamJob: Thread? = null
    @Volatile
    private var streamProcess: Process? = null
}
