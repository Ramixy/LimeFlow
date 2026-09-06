package io.github.dovecoteescapee.byedpi.zapret

import android.content.Context

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
        val joined = raw.replace("^", " ")
        val args = mutableListOf<String>()
        for (token in joined.split(Regex("\\s+"))) {
            val t = token.trim()
            if (t.isEmpty() || t == "start" || t.startsWith("\"zapret") ||
                t == "/min" || t == "\"%BIN%winws.exe\"" || t == "winws.exe" ||
                t.startsWith("%BIN%winws.exe")
            ) {
                continue
            }
            if (t.startsWith("--wf-tcp=") || t.startsWith("--wf-udp=")) {
                continue
            }
            if (t == "--filter-tcp=%GameFilterTCP%" || t == "--filter-udp=%GameFilterUDP%") {
                // Пустой game-фильтр: группа без реального фильтра — пропускаем до --new.
                args.add("__DROP_GROUP__")
                continue
            }
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

        val binDir = filesDir(context, "zapret/bin")
        val listsDir = filesDir(context, "zapret/lists")
        return cleaned.map { token ->
            token
                .replace("%BIN%", "$binDir/")
                .replace("%LISTS%", "$listsDir/")
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
            outFile.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                outFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
    }
}
