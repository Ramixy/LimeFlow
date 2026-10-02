package com.amurcanov.tgwsproxy

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong

/**
 * Keeps the native engine's cfproxy domain cache file up to date.
 *
 * The Rust engine reads `cfproxy-domains-cache.txt` from its cache dir at
 * startup and merges it with the built-in list, then tries GitHub every 12h.
 * On networks where raw.githubusercontent.com is blocked (common without a
 * working bypass), the engine would otherwise stay on its stale built-in
 * list forever: several workers there no longer accept WebSocket upgrades,
 * which showed up as connection timeouts.
 *
 * This object seeds the cache with a known-good snapshot before the engine
 * starts and refreshes it from GitHub in the background between starts.
 */
object CfDomainCache {

    private const val FILE_NAME = "cfproxy-domains-cache.txt"
    private const val DOMAINS_URL =
        "https://raw.githubusercontent.com/Flowseal/tg-ws-proxy/main/.github/cfproxy-domains.txt"

    /**
     * Verbatim copy of .github/cfproxy-domains.txt on Flowseal/tg-ws-proxy
     * (checked 2026-10-02; every domain verified to accept a WSS upgrade
     * on /apiws). Encoded form, exactly as the engine's decoder expects.
     */
    private val FALLBACK_DOMAINS = listOf(
        "virkgj.com",
        "vmmzovy.com",
        "mkuosckvso.com",
        "zaewayzmplad.com",
        "twdmbzcm.com",
        "awzwsldi.com",
        "clngqrflngqin.com",
        "tjacxbqtj.com",
        "bxaxtxmrw.com",
        "dmohrsgmohcrwb.com",
        "vwbmtmoi.com",
        "khgrre.com",
        "ulihssf.com",
        "tmhqsdqmfpmk.com",
        "xwuwoqbm.com",
        "orgcnunpj.com",
        "zhkuldz.com",
        "zypoljnslxa.com",
        "efabnxaowuzs.com",
        "zaftuzsftqdq.com",
    )

    private const val MIN_REFRESH_INTERVAL_MS = 6 * 60 * 60 * 1000L
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 10_000

    private val lastFetchAttemptMs = AtomicLong(0L)

    /**
     * Called right before the native engine starts. Guarantees the cache file
     * exists and is non-empty, and kicks off a background GitHub refresh when
     * the cached copy looks stale.
     */
    fun ensureFresh(cacheDir: File) {
        val file = File(cacheDir, FILE_NAME)
        if (!file.exists() || file.length() == 0L) {
            writeDomains(file, FALLBACK_DOMAINS)
        }
        val age = System.currentTimeMillis() - file.lastModified()
        if (age > MIN_REFRESH_INTERVAL_MS) {
            refreshInBackground(cacheDir)
        }
    }

    private fun refreshInBackground(cacheDir: File) {
        val now = System.currentTimeMillis()
        val last = lastFetchAttemptMs.get()
        if (now - last < 10 * 60 * 1000L) return
        if (!lastFetchAttemptMs.compareAndSet(last, now)) return

        Thread {
            runCatching { fetchDomainList()?.let { writeDomains(File(cacheDir, FILE_NAME), it) } }
        }.apply {
            isDaemon = true
            name = "cf-domain-cache-refresh"
            start()
        }
    }

    private fun fetchDomainList(): List<String>? = runCatching {
        val conn = URL(DOMAINS_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 tg-ws-proxy-android")
        try {
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val parsed = body.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
            if (parsed.isEmpty()) null else parsed
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    private fun writeDomains(file: File, domains: List<String>) {
        runCatching {
            file.parentFile?.mkdirs()
            // Engine splits on '\n' and normalizes every entry; plain LF, no BOM.
            file.writeText(domains.joinToString("\n"))
        }
    }
}