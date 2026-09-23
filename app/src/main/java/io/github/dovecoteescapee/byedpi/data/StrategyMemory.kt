package io.github.dovecoteescapee.byedpi.data

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.telephony.TelephonyManager
import org.json.JSONObject

data class StrategyScore(
    val protocolSuccess: Int,
    val protocolTotal: Int,
    val pingSuccess: Int,
    val pingTotal: Int,
) {
    val label: String get() = "$protocolSuccess/$protocolTotal"
}

data class TestNetwork(val key: String, val label: String)

object StrategyMemory {
    const val PINNED_KEY = "limeflow_pinned_profiles"
    const val WIFI_PROFILE_KEY = "limeflow_profile_wifi"
    const val MOBILE_PROFILE_KEY = "limeflow_profile_mobile"
    const val RESULTS_KEY = "strategy_test_results_v3"
    private const val NETWORK_RESULTS_PREFIX = "strategy_test_results_v4_"
    private const val KNOWN_NETWORKS_KEY = "strategy_test_networks_v4"
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
        val key = "limeflow_profile_${testNetwork(context).key}"
        preferences.edit().putString(key, profileId).apply()
    }

    fun rememberedForCurrentNetwork(context: Context, preferences: SharedPreferences): String? {
        val key = "limeflow_profile_${testNetwork(context).key}"
        return preferences.getString(key, preferences.getString(
            if (onWifi(context)) WIFI_PROFILE_KEY else MOBILE_PROFILE_KEY, null))
    }

    fun networkLabel(context: Context): String = testNetwork(context).label

    fun testNetwork(context: Context): TestNetwork {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val caps = manager?.let { connection ->
            connection.activeNetwork?.let(connection::getNetworkCapabilities)
        }
        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
            return TestNetwork("wifi", "Wi-Fi")
        }
        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) {
            val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            val raw = runCatching { telephony?.networkOperatorName.orEmpty().ifBlank {
                telephony?.simOperatorName.orEmpty()
            } }.getOrDefault("")
            val name = normalizeOperator(raw)
            val key = name.lowercase(java.util.Locale.ROOT)
                .replace(Regex("[^a-zа-яё0-9]+"), "_").trim('_').take(32)
            return TestNetwork("mobile_${key.ifBlank { "unknown" }}", "Мобильная · $name")
        }
        return TestNetwork("other", "Другая сеть")
    }

    fun normalizeOperator(raw: String): String {
        val name = raw.trim().take(32)
        return when {
            name.contains("мегафон", true) || name.contains("megafon", true) -> "МегаФон"
            name.contains("билайн", true) || name.contains("beeline", true) -> "Билайн"
            name.contains("мтс", true) || name.equals("mts", true) -> "МТС"
            name.contains("tele2", true) || name.equals("t2", true) -> "T2"
            name.contains("yota", true) || name.contains("йота", true) -> "Yota"
            name.isBlank() -> "оператор не определён"
            else -> name
        }
    }

    fun resultsKey(networkKey: String): String = NETWORK_RESULTS_PREFIX + networkKey

    fun rememberTestNetwork(preferences: SharedPreferences, network: TestNetwork) {
        val current = preferences.getStringSet(KNOWN_NETWORKS_KEY, emptySet()).orEmpty().toMutableSet()
        current.add("${network.key}|${network.label}")
        preferences.edit().putStringSet(KNOWN_NETWORKS_KEY, current).apply()
    }

    fun testedNetworks(preferences: SharedPreferences): List<TestNetwork> =
        preferences.getStringSet(KNOWN_NETWORKS_KEY, emptySet()).orEmpty()
            .mapNotNull { value ->
                val key = value.substringBefore('|')
                val label = value.substringAfter('|', "")
                if (key.isBlank() || label.isBlank()) null else TestNetwork(key, label)
            }.sortedBy { it.label }

    fun onWifi(context: Context): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        val network = manager.activeNetwork ?: return true
        val caps = manager.getNetworkCapabilities(network) ?: return true
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    fun scoreFor(context: Context, preferences: SharedPreferences, profileId: String): StrategyScore? {
        val raw = preferences.getString(resultsKey(testNetwork(context).key), null) ?: return null
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
