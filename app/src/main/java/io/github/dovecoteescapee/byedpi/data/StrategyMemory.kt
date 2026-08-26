package io.github.dovecoteescapee.byedpi.data

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.json.JSONObject

data class StrategyScore(
    val protocolSuccess: Int,
    val protocolTotal: Int,
    val pingSuccess: Int,
    val pingTotal: Int,
) {
    val label: String get() = "$protocolSuccess/$protocolTotal"
}

object StrategyMemory {
    const val PINNED_KEY = "limeflow_pinned_profiles"
    const val WIFI_PROFILE_KEY = "limeflow_profile_wifi"
    const val MOBILE_PROFILE_KEY = "limeflow_profile_mobile"
    const val RESULTS_KEY = "strategy_test_results_v3"
    const val BATTERY_PROMPTED_KEY = "limeflow_battery_prompted"
    const val NETWORK_HINT_KEY = "limeflow_network_hint_type"
    const val PROTOCOL_TEST_COUNT = 45
    const val PING_TEST_COUNT = 18

    fun pinned(preferences: SharedPreferences): Set<String> =
        preferences.getStringSet(PINNED_KEY, emptySet()).orEmpty()

    fun isPinned(preferences: SharedPreferences, id: String): Boolean = id in pinned(preferences)

    fun togglePin(preferences: SharedPreferences, id: String): Boolean {
        val next = pinned(preferences).toMutableSet()
        val pinnedNow = if (next.remove(id)) false else next.add(id).let { true }
        preferences.edit().putStringSet(PINNED_KEY, HashSet(next)).apply()
        return pinnedNow
    }

    fun rememberForCurrentNetwork(context: Context, preferences: SharedPreferences, profileId: String) {
        val key = if (onWifi(context)) WIFI_PROFILE_KEY else MOBILE_PROFILE_KEY
        preferences.edit().putString(key, profileId).apply()
    }

    fun rememberedForCurrentNetwork(context: Context, preferences: SharedPreferences): String? {
        val key = if (onWifi(context)) WIFI_PROFILE_KEY else MOBILE_PROFILE_KEY
        return preferences.getString(key, null)
    }

    fun networkLabel(context: Context): String = if (onWifi(context)) "Wi-Fi" else "LTE"

    fun onWifi(context: Context): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        val network = manager.activeNetwork ?: return true
        val caps = manager.getNetworkCapabilities(network) ?: return true
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    fun scoreFor(preferences: SharedPreferences, profileId: String): StrategyScore? {
        val raw = preferences.getString(RESULTS_KEY, null) ?: return null
        return runCatching {
            val payload = JSONObject(raw)
            val results = payload.optJSONArray("results") ?: return null
            for (index in 0 until results.length()) {
                val item = results.getJSONObject(index)
                if (item.optString("profileId") != profileId) continue
                val protocol = item.optInt("protocolSuccess")
                val ping = item.optInt("pingSuccess")
                if (protocol <= 0 && ping <= 0) return null
                return StrategyScore(
                    protocolSuccess = protocol,
                    protocolTotal = item.optInt("protocolTotal", PROTOCOL_TEST_COUNT),
                    pingSuccess = ping,
                    pingTotal = item.optInt("pingTotal", PING_TEST_COUNT),
                )
            }
            null
        }.getOrNull()
    }
}
