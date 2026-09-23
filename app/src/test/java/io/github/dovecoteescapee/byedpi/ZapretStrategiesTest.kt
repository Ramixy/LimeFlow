package io.github.dovecoteescapee.byedpi

import io.github.dovecoteescapee.byedpi.zapret.ZapretStrategies
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZapretStrategiesTest {
    @Test fun validatesGamePortsAndRejectsShellSyntax() {
        assertEquals("1024-1934,1936-65535", ZapretStrategies.validatePorts("1024-1934, 1936-65535"))
        assertEquals("443", ZapretStrategies.validatePorts("0443"))
        for (bad in listOf("", "0", "65536", "500-499", "1024;id", "1,,2", "1-2-3")) {
            assertEquals(bad, null, ZapretStrategies.validatePorts(bad))
        }
    }

    @Test fun skipsBatchPreambleAndOptionalGameGroups() {
        val config = """
            @echo off
            call service.bat check_updates
            start "zapret: general" /min "%BIN%winws.exe" --wf-tcp=80,443,%GameFilterTCP% ^
            --filter-tcp=443 --hostlist="%LISTS%list-google.txt" --new ^
            --filter-tcp=%GameFilterTCP% --dpi-desync=fake --new ^
            --filter-udp=%GameFilterUDP% --dpi-desync=fake
        """.trimIndent()
        val disabled = ZapretStrategies.parseConfig(config, "/bin", "/lists", null, null)
        assertFalse(disabled.any { it.contains("call") || it.contains("echo") || it.contains("GameFilter") })
        assertTrue(disabled.contains("--hostlist=/lists/list-google.txt"))
        assertFalse(disabled.contains("--filter-tcp=1024-65535"))
        val enabled = ZapretStrategies.parseConfig(config, "/bin", "/lists", "1024-1934,1936-65535", "1024-65535")
        assertTrue(enabled.contains("--filter-tcp=1024-1934,1936-65535"))
        assertTrue(enabled.contains("--filter-udp=1024-65535"))
    }
}
