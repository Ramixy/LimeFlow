package io.github.dovecoteescapee.byedpi

import io.github.dovecoteescapee.byedpi.zapret.ZapretStrategies
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * The Flowseal .bat prelude (chcp/cd/call/set, window title, /min) must never
 * reach the nfqws command line: in run.sh (POSIX sh) the stray `>` of the batch
 * prelude becomes a redirection and the window title's unmatched quote glues
 * the whole command into one unusable argument.
 */
class ZapretStrategiesTest {

    private val batWithPrelude = """
        @echo off
        chcp 65001 > nul
        :: 65001 - UTF-8

        cd /d "%~dp0"
        call service.bat status_zapret
        echo:

        set "BIN=%~dp0bin\"
        set "LISTS=%~dp0lists\"
        cd /d %BIN%

        start "zapret: %~n0" /min "%BIN%winws.exe" --wf-tcp=80,443,%GameFilterTCP% --wf-udp=443,%GameFilterUDP% ^
        --filter-udp=443 --hostlist="%LISTS%list-general.txt" --dpi-desync=fake --dpi-desync-repeats=6 --new ^
        --filter-udp=19294-19344,50000-50100 --filter-l7=discord,stun --dpi-desync=fake --new ^
        --filter-tcp=%GameFilterTCP% --ipset="%LISTS%ipset-all.txt" --dpi-desync=multisplit --new ^
        --filter-tcp=443 --hostlist="%LISTS%list-google.txt" --dpi-desync=multisplit
    """.trimIndent().replace("\n", "\r\n")

    @Test
    fun `batch prelude is dropped and args survive`() {
        val args = ZapretStrategies.parseConfigText(
            batWithPrelude,
            binDir = "/data/app/bin",
            listsDir = "/data/app/lists",
        )

        val joined = args.joinToString(" ")

        // Nothing from the batch prelude may survive.
        for (junk in listOf("@echo", "off", "chcp", "65001", "nul", "call", "service.bat", "set", "cd", "start", "/min")) {
            assertFalse("prelude token leaked: $junk", args.any { it == junk })
        }
        assertFalse("window title leaked", joined.contains("zapret:"))
        assertFalse("unmatched title quote leaked", joined.contains("%~n0"))
        assertFalse("WinDivert filters leaked", joined.contains("--wf-tcp="))
        assertFalse("unexpanded placeholder leaked", joined.contains("%BIN%") || joined.contains("%LISTS%"))

        // Real arguments must be present and rewritten.
        assertTrue(joined.contains("--filter-udp=443"))
        assertTrue(joined.contains("--hostlist=/data/app/lists/list-general.txt"))
        assertTrue(joined.contains("--dpi-desync=fake"))
        // The only --ipset in this config belongs to the empty game-filter group,
        // which the parser drops up to the next --new (see the test below).
        assertFalse(joined.contains("--ipset="))
        assertTrue(joined.contains("--filter-tcp=443"))

        // The last group (no trailing --new) must survive; the dropped game group
        // sits between --new markers and must be gone.
        assertFalse(joined.contains("GameFilterTCP"))
    }

    @Test
    fun `game filter group is dropped up to next new`() {
        val args = ZapretStrategies.parseConfigText(
            batWithPrelude,
            binDir = "/b",
            listsDir = "/l",
        )

        val joined = args.joinToString(" ")
        // The game TCP group is between two --new markers; its only unique arg is the ipset line,
        // which is shared with other groups, so assert on ordering instead.
        val discordIndex = args.indexOfFirst { it.startsWith("--filter-l7=") }
        assertTrue(discordIndex >= 0)
        val nextNewAfterDiscord = args.drop(discordIndex + 1).indexOf("--new") + discordIndex + 1
        assertTrue(nextNewAfterDiscord > discordIndex)
        // The game group would start with --filter-tcp=%GameFilterTCP% which is a drop marker,
        // so the group after the second --new must be the final tcp group, not a game group.
        // The final group's last token is its desync option, so assert on the group header.
        val lastNew = args.lastIndexOf("--new")
        assertEquals("--filter-tcp=443", args[lastNew + 1])
    }

    @Test
    fun `catalog has no discord-voice strategy and no STUN capture ports`() {
        // 1.4.2 shipped an experimental discord-voice preset plus STUN/TURN
        // 3478-3481 capture ports; both broke Discord voice and were reverted
        // in 1.4.3. The parser itself must still pass a STUN fake through
        // when a config legitimately carries one: original Flowseal configs
        // do that on the voice port ranges only.
        val ids = ZapretStrategies.list().map { it.id }
        assertFalse("discord-voice preset must stay removed", ids.contains("discord-voice"))
        assertTrue("general preset must exist", ids.contains("general"))

        val bat = "start \"z\" /min \"%BIN%winws.exe\" --wf-udp=443 ^\n" +
            "--filter-udp=19294-19344,50000-50100 --filter-l7=discord,stun --dpi-desync=fake --dpi-desync-fake-stun=\"%BIN%ACTIVE_DISCORD_UDP.bin\" --dpi-desync-repeats=8 --dpi-desync-cutoff=n4\n"
        val args = ZapretStrategies.parseConfigText(bat, binDir = "/b", listsDir = "/l")
        val joined = args.joinToString(" ")
        assertTrue(joined.contains("--filter-udp=19294-19344,50000-50100"))
        assertTrue(joined.contains("--dpi-desync-fake-stun=/b/ACTIVE_DISCORD_UDP.bin"))
        assertFalse("STUN/TURN ports must stay out of engine configs", joined.contains("3478"))
        assertFalse(joined.contains("--wf-udp"))
    }

    @Test
    fun `config without launch token yields no args`() {
        val args = ZapretStrategies.parseConfigText("echo hello\r\npause\r\n", "/b", "/l")
        assertTrue(args.isEmpty())
    }

    @Test
    fun `quoteForShell neutralizes quotes and spaces`() {
        assertEquals("'/data/app/bin/x.bin'", ZapretStrategies.quoteForShell("/data/app/bin/x.bin"))
        assertEquals("'/data/app/bin/x bin'", ZapretStrategies.quoteForShell("/data/app/bin/x bin"))
        assertEquals("'/data/app/x\"y'", ZapretStrategies.quoteForShell("/data/app/x\"y"))
        assertEquals("'a'\\''b'", ZapretStrategies.quoteForShell("a'b"))
        assertEquals("'/data/app/y'", ZapretStrategies.quoteForShell("\"/data/app/y\""))
    }
}
