package io.github.dovecoteescapee.byedpi.zapret

import android.content.Context
import io.github.dovecoteescapee.byedpi.utility.getPreferences

/*
 * Порты стратегий flowseal/zapret-discord-youtube: исходные .bat-конфиги лежат в
 * assets и разбираются при запуске. Из winws-команды убираются только
 * Windows-специфичные --wf-* и пустые game-фильтры; всё остальное (desync-группы,
 * seqovl, fooling, повторы) передаётся движку nfqws один в один.
 */
data class ZapretStrategy(
    val id: String,
    val name: String,
    val assetFile: String,
    val description: String,
)

object ZapretStrategies {
    const val GAME_TCP_ENABLED = "game_filter_tcp_enabled"
    const val GAME_UDP_ENABLED = "game_filter_udp_enabled"
    const val GAME_TCP_PORTS = "game_filter_tcp_ports"
    const val GAME_UDP_PORTS = "game_filter_udp_ports"
    const val DEFAULT_GAME_PORTS = "1024-65535"

    fun validatePorts(value: String): String? {
        val parts = value.trim().split(',')
        if (parts.isEmpty() || parts.size > 32) return null
        val normalized = parts.map { part ->
            val match = Regex("^([0-9]{1,5})(?:-([0-9]{1,5}))?$").matchEntire(part.trim())
                ?: return null
            val start = match.groupValues[1].toInt()
            val end = match.groupValues[2].takeIf { it.isNotEmpty() }?.toInt() ?: start
            if (start !in 1..65535 || end !in start..65535) return null
            if (start == end) "$start" else "$start-$end"
        }
        return normalized.joinToString(",")
    }

    fun gamePorts(context: Context, tcp: Boolean): String? {
        val prefs = context.getPreferences()
        val enabled = prefs.getBoolean(if (tcp) GAME_TCP_ENABLED else GAME_UDP_ENABLED, false)
        if (!enabled) return null
        return validatePorts(prefs.getString(if (tcp) GAME_TCP_PORTS else GAME_UDP_PORTS, DEFAULT_GAME_PORTS).orEmpty())
            ?: throw IllegalArgumentException("Неверные порты GameFilter")
    }
    private val CONFIGS = listOf(
        Triple("general", "General", "Основной конфиг Flowseal: QUIC-fake, multisplit с google/4pda-перекрытиями"),
        Triple("general (ALT)", "ALT", "fake,fakedsplit с ts-fooling и fake SNI google/max.ru"),
        Triple("general (ALT2)", "ALT2", "multisplit seqovl=652, split-pos=2, google-паттерн"),
        Triple("general (ALT3)", "ALT3", "fake,hostfakesplit с подменой хоста на ya.ru"),
        Triple("general (ALT4)", "ALT4", "fake,multisplit с badseq-fooling"),
        Triple("general (ALT5)", "ALT5", "syndata + multidisorder без fake TLS"),
        Triple("general (ALT6)", "ALT6", "multisplit seqovl=681 с google-паттерном"),
        Triple("general (ALT7)", "ALT7", "multisplit split-pos=2,sniext+1"),
        Triple("general (ALT8)", "ALT8", "fake с fake-tls-mod=none и badseq"),
        Triple("general (ALT9)", "ALT9", "hostfakesplit с хостом ozon.ru"),
        Triple("general (ALT10)", "ALT10", "fake с 4pda-подменой и ts-fooling"),
        Triple("general (ALT11)", "ALT11", "fake,multisplit seqovl=664 с max.ru-паттерном"),
        Triple("general (ALT12)", "ALT12", "fake,multisplit seqovl=664, ts-fooling"),
        Triple("general (EXP)", "EXP", "экспериментальный: fake,multisplit со stun2-паттерном"),
        Triple("general (FAKE TLS AUTO)", "FAKE TLS AUTO", "fake,multidisorder split-pos=1,midsld, повторы 11"),
        Triple("general (FAKE TLS AUTO ALT)", "FAKE TLS AUTO ALT", "fake,fakedsplit с rnd,dupsid и google SNI"),
        Triple("general (FAKE TLS AUTO ALT2)", "FAKE TLS AUTO ALT2", "fake,multisplit seqovl=681 с badseq=10000000"),
        Triple("general (FAKE TLS AUTO ALT3)", "FAKE TLS AUTO ALT3", "fake,multisplit google-паттерн, rnd,dupsid"),
        Triple("general (SIMPLE FAKE)", "SIMPLE FAKE", "простой fake с google/max.ru подменами"),
        Triple("general (SIMPLE FAKE ALT)", "SIMPLE FAKE ALT", "fake с badseq и google-подменой"),
        Triple("general (SIMPLE FAKE ALT2)", "SIMPLE FAKE ALT2", "fake с badseq, stun/max.ru подменами"),
    )

    fun list(): List<ZapretStrategy> = CONFIGS.map { (file, name, description) ->
        ZapretStrategy(
            id = file,
            name = name,
            assetFile = "zapret/configs/$file.bat",
            description = description,
        )
    }

    /*
     * Разбирает .bat в аргументы nfqws: убирает --wf-* (WinDivert-only), пустые
     * game-фильтры и подставляет пути к спискам и fake-бинарям.
     */
    fun parseArgs(context: Context, assetFile: String): List<String> {
        val raw = context.assets.open(assetFile).bufferedReader().readText()
        return parseConfig(raw, filesDir(context, "zapret/bin"), filesDir(context, "zapret/lists"),
            gamePorts(context, true), gamePorts(context, false))
    }

    fun parseConfig(raw: String, binDir: String, listsDir: String,
                    gameTcp: String?, gameUdp: String?): List<String> {
        // The preamble is Windows batch code, never nfqws arguments.
        val command = raw.substringAfter("winws.exe\"", missingDelimiterValue = "")
        require(command.isNotEmpty()) { "В конфиге нет команды winws.exe" }
        val joined = command.replace("^", " ")
        val args = mutableListOf<String>()
        var inGameGroup = false
        for (token in joined.split(Regex("\\s+"))) {
            val t = token.trim()
            if (t.isEmpty()) continue
            if (t == "--new") inGameGroup = false
            if (t.startsWith("--wf-tcp=") || t.startsWith("--wf-udp=")) {
                continue
            }
            if (t == "--filter-tcp=%GameFilterTCP%" || t == "--filter-udp=%GameFilterUDP%") {
                val ports = if (t.startsWith("--filter-tcp")) gameTcp else gameUdp
                if (ports == null) args.add("__DROP_GROUP__")
                else {
                    args.add(t.substringBefore('=') + "=" + ports)
                    inGameGroup = true
                }
                continue
            }
            // The upstream placeholder ipset contains only a documentation IP;
            // keeping it here would make the Android game filter match no games.
            if (inGameGroup && t.startsWith("--ipset=") && t.contains("ipset-all.txt")) continue
            args.add(t)
        }

        val cleaned = mutableListOf<String>()
        var dropping = false
        for (token in args) {
            if (token == "__DROP_GROUP__") {
                dropping = true
                continue
            }
            if (dropping) {
                if (token == "--new") dropping = false
                continue
            }
            cleaned.add(token)
        }
        while (cleaned.lastOrNull() == "--new") cleaned.removeAt(cleaned.lastIndex)

        return cleaned.map { token ->
            token
                .replace("%BIN%", "$binDir/")
                .replace("%LISTS%", "$listsDir/")
                .replace("\"", "")
        }
    }

    fun filesDir(context: Context, sub: String): String =
        "${context.filesDir.absolutePath}/$sub"

    /*
     * Распаковывает списки хостов и fake-бинари из assets в filesDir — nfqws
     * принимает только пути к файлам.
     */
    fun extractDataFiles(context: Context) {
        val targets = mutableListOf<Pair<String, String>>()
        context.assets.list("zapret/lists").orEmpty().forEach {
            targets.add("zapret/lists/$it" to "zapret/lists/$it")
        }
        context.assets.list("zapret/bin").orEmpty().forEach {
            targets.add("zapret/bin/$it" to "zapret/bin/$it")
        }
        for ((assetPath, targetPath) in targets) {
            val outFile = java.io.File(context.filesDir, targetPath)
            if (targetPath.endsWith("-user.txt") && outFile.exists()) continue
            outFile.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                outFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
    }
}
