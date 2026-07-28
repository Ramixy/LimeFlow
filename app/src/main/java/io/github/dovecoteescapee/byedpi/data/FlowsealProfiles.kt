package io.github.dovecoteescapee.byedpi.data

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class FlowsealProfile(
    val id: String,
    val name: String,
    val method: String,
    val description: String,
    val arguments: String,
    val custom: Boolean = false,
)

object FlowsealProfiles {
    // Known-good command shapes from ByeByeDPI 1.7.7's strategy tester.
    // They exercise the newer 0.17 engine features that cannot be expressed by old 0.13 presets.
    private val testedBase = listOf(
        "-f-200 -Qr -s3:5+sm -a1 -As -d1 -s4+sm -s8+sh -f-300 -d6+sh -a1 -At,r,s -o2 -f-30 -As -r5 -Mh -r6+sh -f-250 -s2:7+s -s3:6+sm -a1 -At,r,s -s3:5+sm -s6+s -s7:9+s -q30+sm -a1",
        "-d1 -d3+s -s6+s -d9+s -s12+s -d15+s -s20+s -d25+s -s30+s -d35+s -r1+s -S -a1 -As -d1 -d3+s -s6+s -d9+s -s12+s -d15+s -s20+s -d25+s -s30+s -d35+s -S -a1",
        "-q2 -s2 -s3+s -r3 -s4 -r4 -s5+s -r5+s -s6 -s7+s -r8 -s9+s -Qr -Mh,d,r -a1 -At,r -s2+s -r2 -d2 -s3 -r3 -r4 -s4 -d5+s -r5 -d6 -s7+s -d7 -a1",
        "-o1 -d1 -a1 -At,r,s -s1 -d1 -s5+s -s10+s -s15+s -s20+s -r1+s -S -a1 -As -s1 -d1 -s5+s -s10+s -s15+s -s20+s -S -a1",
        "-d1+s -s50+s -a1 -As -f20 -r2+s -a1 -At -d2 -s1+s -s5+s -s10+s -s15+s -s25+s -s35+s -s50+s -s60+s -a1",
        "-d1 -s1 -q1 -a1 -Ar -s5 -o1+s -d3+s -s6+s -d9+s -s12+s -d15+s -s20+s -d25+s -s30+s -d35+s -a1",
        "-d1 -s1+s -d3+s -s6+s -d9+s -s12+s -d15+s -s20+s -d25+s -s30+s -d35+s -a1",
        "-d1 -s1+s -d1+s -s3+s -d6+s -s12+s -d14+s -s20+s -d24+s -s30+s -a1",
        "-o1 -a1 -At,r,s -f-1 -a1 -Ar,s -o1 -a1 -At -r1+s -f-1 -t6 -a1",
        "-d1 -s1+s -s3+s -s6+s -s9+s -s12+s -s15+s -s20+s -s30+s -a1",
        "-d1 -d3+s -s6+s -d6+s -s7+s -d8+s -s10+s -a1 -t12 -At,s -r3",
        "-f-1 -Qr -s1+sm -d3+s -s5+sm -o2 -a1 -As -r1+s -d8+s -a1",
        "-r-1+s -o20+sm -s3:7+sm -d5:3+sm -f300+s -Qr -f-1 -a1",
        "-o2 -O4 -s1 -q1 -a1 -Ar -s5 -o1+s -f1+s -r20+s -a1",
        "--fake -1 --ttl 8 --split 1+s --disorder 3+s -a1",
        "-r5+s -s25+s -a1 -At,r,s -s50 -r5+s -s50+s -a1",
        "-d1 -d3+s -s6+s -d9+s -s20+s -d25+s -s30+s -a1",
        "-d9+s -q20+s -s25+s -t5 -a1 -At,r,s -r1+h -a1",
        "-q1+s -s29+s -s30+s -s14+s -o5+s -f-1 -S -a1",
        "-d1 -s1+s -r1+s -e1 -m1 -o1+s -f-1 -t2 -a1",
        "-d1 -o1 -a1 -Ar -o1 -a1 -At -f-1 -r1+s -a1",
    )
    /*
     * YouTube prefers QUIC, while Discord voice uses separate UDP ranges.
     * Isolated groups avoid applying UDP desync to every app on the phone.
     */
    private const val appUdpGroups =
        "-Ku -V443 -a11 -An " +
            "-Ku -V19294-19344 -a6 -An " +
            "-Ku -V50000-50100 -a6 -An "
    private val tested = testedBase.map { "$appUdpGroups$it" }
    private const val universalUdpGroups =
        "-Ku -V443 -a12 -An " +
            "-Ku -V19294-19344 -a8 -An " +
            "-Ku -V50000-50100 -a8 -An "
    private val universal = "$universalUdpGroups${testedBase[0]}"

    val all = listOf(
        FlowsealProfile(
            "limeflow_universal",
            "LIMEFLOW UNIVERSAL",
            "adaptive QUIC + deep TLS",
            "Усиленный профиль для YouTube, Discord, CDN и голосовых подключений",
            universal,
        ),
        FlowsealProfile(
            "limeflow_youtube",
            "LIMEFLOW YOUTUBE",
            "swift TLS chain + QUIC",
            "Профиль с глубокой цепочкой разделения для YouTube и видеопотоков",
            "$appUdpGroups${testedBase[1]}",
        ),
        FlowsealProfile(
            "limeflow_discord",
            "LIMEFLOW DISCORD",
            "voice UDP + stable TLS",
            "Усиленная обработка Discord CDN, gateway и голосовых подключений",
            "$appUdpGroups${testedBase[0]}",
        ),
        FlowsealProfile(
            "limeflow_stream",
            "LIMEFLOW STREAM",
            "multisplit streaming",
            "Стабильный профиль для продолжительного видео и крупных CDN",
            "$appUdpGroups${testedBase[6]}",
        ),
        FlowsealProfile(
            "limeflow_mobile",
            "LIMEFLOW MOBILE",
            "compact cellular chain",
            "Компактная стратегия для мобильной сети с меняющейся задержкой",
            "$appUdpGroups${testedBase[9]}",
        ),
        FlowsealProfile("general", "GENERAL", "smart chain 1", "Многопрофильная стратегия нового ядра", tested[0]),
        FlowsealProfile("alt", "ALT", "smart chain 2", "Глубокое TLS-разбиение", tested[1]),
        FlowsealProfile("alt2", "ALT2", "smart chain 3", "Split, disorder и TLS-record", tested[2]),
        FlowsealProfile("alt3", "ALT3", "smart chain 4", "Адаптивная цепочка протоколов", tested[3]),
        FlowsealProfile("alt4", "ALT4", "smart chain 5", "Длинный SNI multisplit", tested[4]),
        FlowsealProfile("alt5", "ALT5", "smart chain 6", "OOB и multidisorder", tested[5]),
        FlowsealProfile("alt6", "ALT6", "multidisorder", "Глубокое разбиение SNI", tested[6]),
        FlowsealProfile("alt7", "ALT7", "multisplit", "Несколько позиций SNI", tested[7]),
        FlowsealProfile("alt8", "ALT8", "fake + TLS record", "Комбинированный fake", tested[8]),
        FlowsealProfile("alt9", "ALT9", "multisplit", "Частое разбиение SNI", tested[9]),
        FlowsealProfile("alt10", "ALT10", "disorder + TLS record", "Альтернативная TTL-цепочка", tested[10]),
        FlowsealProfile("alt11", "ALT11", "fake + multisplit", "Обновлённый профиль для вашей сети", tested[11]),
        FlowsealProfile("alt12", "ALT12", "record + fake", "Экспериментальная комбинация", tested[12]),
        FlowsealProfile("exp", "EXP", "OOB + fake", "Расширенный экспериментальный профиль", tested[13]),
        FlowsealProfile("fake_auto", "FAKE TLS AUTO", "classic upgraded", "Классический профиль на новом ядре", tested[14]),
        FlowsealProfile("fake_auto_alt", "FAKE TLS AUTO ALT", "record split", "Автоматическое TLS-разбиение", tested[15]),
        FlowsealProfile("fake_auto_alt2", "FAKE TLS AUTO ALT2", "multidisorder", "Автоматическая глубокая цепочка", tested[16]),
        FlowsealProfile("fake_auto_alt3", "FAKE TLS AUTO ALT3", "QUIC + record", "Профиль для HTTPS/QUIC", tested[17]),
        FlowsealProfile("simple_fake", "SIMPLE FAKE", "compact fake", "Компактный fake-профиль", tested[18]),
        FlowsealProfile("simple_fake_alt", "SIMPLE FAKE ALT", "compact mixed", "Fake, OOB и TLS-record", tested[19]),
        FlowsealProfile("simple_fake_alt2", "SIMPLE FAKE ALT2", "protocol groups", "Раздельная обработка протоколов", tested[20]),
    )

    fun selected(preferences: SharedPreferences): FlowsealProfile {
        val id = preferences.getString("flowseal_profile", "alt11")
        return catalog(preferences).firstOrNull { it.id == id } ?: all.first { it.id == "alt11" }
    }

    fun select(preferences: SharedPreferences, profile: FlowsealProfile) {
        preferences.edit()
            .putString("flowseal_profile", profile.id)
            .putString("byedpi_cmd_args", profile.arguments)
            .putBoolean("byedpi_enable_cmd_settings", true)
            .apply()
    }

    fun catalog(preferences: SharedPreferences): List<FlowsealProfile> =
        all + customProfiles(preferences)

    fun saveCustom(
        preferences: SharedPreferences,
        existingId: String?,
        name: String,
        arguments: String,
    ): FlowsealProfile {
        val profiles = customProfiles(preferences).toMutableList()
        val profile = FlowsealProfile(
            id = existingId ?: "custom_${System.currentTimeMillis()}",
            name = name.trim().uppercase(),
            method = "пользовательская",
            description = "Собственная стратегия LimeFlow",
            arguments = arguments.trim(),
            custom = true,
        )
        val index = profiles.indexOfFirst { it.id == profile.id }
        if (index >= 0) profiles[index] = profile else profiles.add(0, profile)
        persistCustom(preferences, profiles)
        return profile
    }

    fun deleteCustom(preferences: SharedPreferences, id: String) {
        persistCustom(preferences, customProfiles(preferences).filterNot { it.id == id })
        if (preferences.getString("flowseal_profile", null) == id) {
            select(preferences, all.first { it.id == "alt11" })
        }
    }

    private fun customProfiles(preferences: SharedPreferences): List<FlowsealProfile> = runCatching {
        val stored = JSONArray(preferences.getString(CUSTOM_KEY, "[]"))
        buildList {
            for (index in 0 until stored.length()) {
                val item = stored.getJSONObject(index)
                val name = item.optString("name").trim()
                val arguments = item.optString("arguments").trim()
                if (name.isEmpty() || arguments.isEmpty()) continue
                add(
                    FlowsealProfile(
                        id = item.getString("id"),
                        name = name,
                        method = "пользовательская",
                        description = "Собственная стратегия LimeFlow",
                        arguments = arguments,
                        custom = true,
                    )
                )
            }
        }
    }.getOrDefault(emptyList())

    private fun persistCustom(
        preferences: SharedPreferences,
        profiles: List<FlowsealProfile>,
    ) {
        val payload = JSONArray().apply {
            profiles.forEach { profile ->
                put(
                    JSONObject()
                        .put("id", profile.id)
                        .put("name", profile.name)
                        .put("arguments", profile.arguments)
                )
            }
        }
        preferences.edit().putString(CUSTOM_KEY, payload.toString()).apply()
    }

    private const val CUSTOM_KEY = "limeflow_custom_profiles"
}
