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
        Triple("discord-voice", "VOICE", "голос Discord: fake UDP x8 на voice+STUN/TURN портах, cutoff n4, QUIC и media-TCP без изменений"),
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
     * Converts a Flowseal .bat config into nfqws arguments: drops the whole batch
     * prelude (everything before the winws.exe launch line), --wf-* (WinDivert-only)
     * flags and empty game-filter groups, then rewrites %BIN%/%LISTS% to the real
     * on-device paths.
     *
     * The prelude must not be filtered token by token: at least the `>` redirect and
     * the window title's unmatched quote (the `%~n0` fragment followed by a quote)
     * used to leak into run.sh, where `>` became a live shell redirection and the
     * quote glued the whole rest of the nfqws command line into a single argument.
     * Dropping everything before the binary token is the only safe cut.
     */
    fun parseArgs(context: Context, assetFile: String): List<String> {
        val raw = context.assets.open(assetFile).bufferedReader().readText()
        return parseConfigText(
            raw,
            filesDir(context, "zapret/bin"),
            filesDir(context, "zapret/lists"),
        )
    }

    /*
     * Pure text transform, unit-testable without an Android Context.
     */
    fun parseConfigText(raw: String, binDir: String, listsDir: String): List<String> {
        val joined = raw.replace("^", " ")
        val tokens = joined.split(Regex("\\s+")).map { it.trim() }.filter { it.isNotEmpty() }

        // Everything before the winws.exe launch token is batch script, not nfqws args.
        val launchIndex = tokens.indexOfFirst { token ->
            token == "\"%BIN%winws.exe\"" || token == "winws.exe" ||
                token.startsWith("%BIN%winws.exe")
        }
        if (launchIndex < 0) return emptyList()

        val args = mutableListOf<String>()
        for (token in tokens.subList(launchIndex + 1, tokens.size)) {
            if (token.startsWith("--wf-tcp=") || token.startsWith("--wf-udp=")) {
                continue
            }
            if (token == "--filter-tcp=%GameFilterTCP%" || token == "--filter-udp=%GameFilterUDP%") {
                // Empty game filter: the group has no real filters, skip it up to --new.
                args.add("__DROP_GROUP__")
                continue
            }
            args.add(token)
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

        /*
         * Batch configs wrap values in double quotes (`--hostlist="%LISTS%file.txt"`).
         * The opening quote sits after `=`, so a trailing trim('"') never removed it
         * and nfqws received `--dpi-desync-fake-stun="/path/file.bin` - a path it
         * cannot open, silently disabling STUN/voice fakes. Strip every double
         * quote: in .bat they are value wrappers, never part of an nfqws argument.
         */
        return cleaned.map { token ->
            token
                .replace("%BIN%", "$binDir/")
                .replace("%LISTS%", "$listsDir/")
                .replace("\"", "")
                .trim()
        }
    }

    /*
     * Quotes one argument for run.sh (POSIX sh). Single quotes are safe for everything
     * except a single quote itself. Tokens parsed from .bat configs may still carry
     * double quotes around paths; trim('"') drops that wrapping, and any residual
     * quote can no longer glue neighbouring arguments the way the leaked window
     * title quote used to.
     */
    fun quoteForShell(arg: String): String =
        "'" + arg.trim('"').replace("'", "'\\''") + "'"

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
