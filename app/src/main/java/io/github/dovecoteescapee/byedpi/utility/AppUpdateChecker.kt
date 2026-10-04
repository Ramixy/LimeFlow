package io.github.dovecoteescapee.byedpi.utility

import android.content.Context
import android.os.Build
import io.github.dovecoteescapee.byedpi.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * GitHub Releases check with a daily cache. No background work: the check
 * runs on app start and is throttled by the stored timestamp of the last one.
 */
object AppUpdateChecker {
    private const val RELEASES_URL = "https://api.github.com/repos/ramixy/limeflow/releases/latest"
    private const val LAST_CHECK_KEY = "update_last_check"
    private const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000

    data class Release(val version: String, val apkUrl: String)

    /**
     * Returns a release only when one is due to be surfaced: at most once a
     * day, and only if it is newer than the running build.
     */
    suspend fun checkIfDue(context: Context): Release? = withContext(Dispatchers.IO) {
        val preferences = context.getPreferences()
        val now = System.currentTimeMillis()
        val last = preferences.getLong(LAST_CHECK_KEY, 0)
        if (now - last < CHECK_INTERVAL_MS) return@withContext null

        val release = runCatching { fetchLatest() }.getOrNull()
        preferences.edit().putLong(LAST_CHECK_KEY, now).apply()
        release?.takeIf { isNewer(it.version, BuildConfig.VERSION_NAME) }
    }

    private fun fetchLatest(): Release? {
        val connection = URL(RELEASES_URL).openConnection() as HttpURLConnection
        connection.connectTimeout = 8_000
        connection.readTimeout = 8_000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "LimeFlow")
        val code = connection.responseCode
        if (code != 200) return null
        val payload = connection.inputStream.bufferedReader().use { it.readText() }
        connection.disconnect()

        val json = JSONObject(payload)
        val version = json.optString("tag_name").removePrefix("v").trim()
        if (version.isEmpty()) return null

        val assets = json.optJSONArray("assets")
        val apkUrl = pickAsset(assets) ?: "https://github.com/ramixy/limeflow/releases/latest"
        return Release(version, apkUrl)
    }

    /* Match the release APK to the device ABI the same way the build splits. */
    private fun pickAsset(assets: org.json.JSONArray?): String? {
        if (assets == null) return null
        val names = buildList {
            for (index in 0 until assets.length()) {
                val asset = assets.optJSONObject(index) ?: continue
                val name = asset.optString("name")
                val url = asset.optString("browser_download_url")
                if (name.isNotEmpty() && url.isNotEmpty()) add(name to url)
            }
        }
        val abis = Build.SUPPORTED_ABIS
        for (abi in abis) {
            names.firstOrNull { (name, _) -> name.contains(abi, ignoreCase = true) }
                ?.let { return it.second }
        }
        return names.firstOrNull { (name, _) -> name.contains("universal", ignoreCase = true) }?.second
            ?: names.firstOrNull { (name, _) -> name.endsWith(".apk", ignoreCase = true) }?.second
    }

    fun isNewer(remote: String, current: String): Boolean {
        val remoteParts = numericParts(remote)
        val currentParts = numericParts(current)
        for (index in 0 until maxOf(remoteParts.size, currentParts.size)) {
            val r = remoteParts.getOrElse(index) { 0 }
            val c = currentParts.getOrElse(index) { 0 }
            if (r != c) return r > c
        }
        return false
    }

    private fun numericParts(version: String): List<Int> =
        version.substringBefore('-').removePrefix("v")
            .split('.')
            .map { it.filter { ch -> ch.isDigit() }.ifEmpty { "0" }.toInt() }
}
