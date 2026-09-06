package io.github.dovecoteescapee.byedpi.data

import android.content.SharedPreferences
import io.github.dovecoteescapee.byedpi.utility.shellSplit

/*
 * Классический режим движка: во время запуска из аргументов стратегии убираются
 * экспериментальные приёмы LimeFlow, и движок ведёт себя как стандартный byedpi —
 * встроенный fake_tls вместо копии настоящего ClientHello, повтор клиентской
 * датаграммы для UDP вместо QUIC-подменного пакета, без рандомизации fake.
 * Применяется на лету, сами стратегии и их команды не меняются.
 */
object ClassicEngine {
    const val PREF_KEY = "limeflow_classic_engine"

    fun isEnabled(preferences: SharedPreferences): Boolean =
        preferences.getBoolean(PREF_KEY, false)

    fun applyToArgs(args: Array<String>): Array<String> {
        val kept = ArrayList<String>(args.size)
        var skipNext = false
        for (arg in args) {
            if (skipNext) {
                skipNext = false
                continue
            }
            when {
                arg == "-l" -> skipNext = true          // -l <файл> — значение отдельным токеном
                arg.startsWith("-l:") -> Unit           // -l:'<payload>' — встроенный QUIC-fake
                arg.startsWith("-Q") -> Unit            // -Qr/-Qo — рандомизация и копия ClientHello
                else -> kept.add(arg)
            }
        }
        return kept.toTypedArray()
    }

    fun apply(command: String): String =
        applyToArgs(arrayOf("ciadpi") + shellSplit(command.trim()))
            .drop(1)
            .joinToString(" ") { token ->
                // shellSplit снимает кавычки: токен с пробелами (список хостов)
                // нужно закавычить обратно, иначе команда не перечитается.
                if (token.any { it.isWhitespace() }) "\"$token\"" else token
            }
}
