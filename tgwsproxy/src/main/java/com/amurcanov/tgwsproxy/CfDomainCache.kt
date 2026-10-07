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
 * starts and refreshes it from GitHub through two mirrors (raw + jsDelivr CDN)
 * — synchronously when the cached copy is stale, so the current start already
 * uses fresh domains, not only the next one.
 */
object CfDomainCache {

    private const val FILE_NAME = "cfproxy-domains-cache.txt"

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

    // raw.githubusercontent.com is routinely blocked on RU networks; the
    // jsDelivr CDN mirrors the same file and is usually reachable.
    private val DOMAIN_URLS = listOf(
        "https://raw.githubusercontent.com/Flowseal/tg-ws-proxy/main/.github/cfproxy-domains.txt",
        "https://cdn.jsdelivr.net/gh/Flowseal/tg-ws-proxy@main/.github/cfproxy-domains.txt",
    )

    private const val STALE_INTERVAL_MS = 60 * 60 * 1000L
    private const val SYNC_TIMEOUT_MS = 6_000
    private const val ASYNC_TIMEOUT_MS = 10_000

    private val lastFetchAttemptMs = AtomicLong(0L)

    /**
     * Called right before the native engine starts. Guarantees the cache file
     * exists and is non-empty. When the cached copy is older than an hour,
     * refreshes synchronously (bounded by short timeouts) so this start uses
     * fresh domains; otherwise kicks off a background refresh.
     */
    fun ensureFresh(cacheDir: File) {
        val file = File(cacheDir, FILE_NAME)
        if (!file.exists() || file.length() == 0L) {
            writeDomains(file, FALLBACK_DOMAINS)
        }
        val age = System.currentTimeMillis() - file.lastModified()
        if (age <= STALE_INTERVAL_MS) return

        val now = System.currentTimeMillis()
        val last = lastFetchAttemptMs.get()
        if (now - last < 5 * 60 * 1000L) return
        if (!lastFetchAttemptMs.compareAndSet(last, now)) return

        val fresh = fetchDomainList(sync = true)
        if (fresh != null) {
            writeDomains(file, fresh)
            return
        }
        // Both mirrors failed (offline / blocked): refresh in the background
        // with longer timeouts so a later start still picks the list up.
        Thread {
            runCatching { fetchDomainList(sync = false)?.let { writeDomains(file, it) } }
        }.apply {
            isDaemon = true
            name = "cf-domain-cache-refresh"
            start()
        }
    }

    private fun fetchDomainList(sync: Boolean): List<String>? {
        for (url in DOMAIN_URLS) {
            val result = fetchFrom(url, sync)
            if (result != null) return result
        }
        return null
    }

    private fun fetchFrom(url: String, sync: Boolean): List<String>? = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = if (sync) SYNC_TIMEOUT_MS else ASYNC_TIMEOUT_MS
        conn.readTimeout = if (sync) SYNC_TIMEOUT_MS else ASYNC_TIMEOUT_MS
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
