package io.github.dovecoteescapee.byedpi

import io.github.dovecoteescapee.byedpi.data.FlowsealProfiles
import io.github.dovecoteescapee.byedpi.utility.shellSplit
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Mirrors the getopt table and the value parsers of app/src/main/cpp/byedpi/main.c.
 * A strategy with an unknown flag or a malformed value makes the native proxy
 * exit at once, which the user only sees as "Proxy rejected the selected strategy".
 */
class ByeDpiArgumentsTest {
    private val noArg = setOf('D', 'N', 'U', 'h', 'v', 'E', 'F', 'Z', 'Y')
    private val withArg = "wipIbcxALuTByKHVRsdoqfntzlOQeMrmaJGgWPjC".toSet()

    private fun letterList(value: String, allowed: Set<Char>): Boolean =
        value.split(',').all { part -> part.isNotEmpty() && part[0] in allowed }

    private fun checkValue(flag: Char, value: String): String? {
        if (value.isEmpty()) return "empty value"
        return when (flag) {
            'Q' -> if (value.split(',').all { it in setOf("r", "o", "d") || Regex("""m=\d+""").matches(it) }) null else "bad fake-tls-mod"
            'M' -> if (letterList(value, setOf('r', 'h', 'd'))) null else "bad mod-http"
            'L' -> if (letterList(value, setOf('o', 's', 'n'))) null else "bad auto-mode"
            'z' -> if (Regex("""-?\d+(:-?\d+(:-?\d+)?)?""").matches(value)) null else "bad auto-ttl"
            't', 'a', 'J', 'G', 'g', 'T', 'b', 'c', 'u', 'R', 'O', 'W' ->
                if (Regex("""[\d.:-]+""").matches(value)) null else "not a number"
            's', 'd', 'o', 'q', 'f', 'r' ->
                if (Regex("""-?\d+(:-?\d+(:-?\d+)?)?(\+[a-z]+)?""").matches(value)) null else "bad position"
            'n' -> if (Regex("""[A-Za-z0-9.*?-]+""").matches(value)) null else "bad fake-sni"
            else -> null
        }
    }

    @Test
    fun everyProfileUsesOnlyOptionsTheNativeParserAccepts() {
        val problems = mutableListOf<String>()
        FlowsealProfiles.all.forEach { profile ->
            shellSplit(profile.arguments).forEach { token ->
                if (token.startsWith("--")) return@forEach
                val flag = token.getOrNull(1) ?: run { problems.add("${profile.name}: '$token'"); return@forEach }
                val value = token.substring(2)
                when (flag) {
                    in noArg -> if (value.isNotEmpty()) problems.add("${profile.name}: -$flag takes no value ('$token')")
                    in withArg -> checkValue(flag, value)?.let { problems.add("${profile.name}: $it in '$token'") }
                    else -> problems.add("${profile.name}: unknown option '$token'")
                }
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }
}
