package io.github.dovecoteescapee.byedpi.utility

/** Timed process wait compatible with Android API 23. Call from a worker thread. */
fun waitForProcessExit(process: Process, timeoutMs: Long): Int? {
    val deadline = System.nanoTime() + timeoutMs * 1_000_000L
    while (System.nanoTime() < deadline) {
        val result = runCatching { process.exitValue() }.getOrNull()
        if (result != null) return result
        Thread.sleep(50)
    }
    return runCatching { process.exitValue() }.getOrNull()
}
