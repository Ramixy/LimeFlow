package io.github.dovecoteescapee.byedpi

import io.github.dovecoteescapee.byedpi.data.ClassicEngine
import io.github.dovecoteescapee.byedpi.data.FlowsealProfiles
import io.github.dovecoteescapee.byedpi.utility.shellSplit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassicEngineTest {

    @Test
    fun `strips experimental flags from arg array`() {
        val args = arrayOf(
            "ciadpi",
            "-T4",
            "-Ku",
            "-V443",
            "-l:\\xC2\\x00\\x00",
            "-a11",
            "-An",
            "-Qr",
            "-Kt,h",
            "-H:\"youtube.com\"",
            "-Qo",
            "-f-1",
        )

        val kept = ClassicEngine.applyToArgs(args)

        assertEquals(
            listOf("ciadpi", "-T4", "-Ku", "-V443", "-a11", "-An", "-Kt,h", "-H:\"youtube.com\"", "-f-1"),
            kept.toList(),
        )
    }

    @Test
    fun `separate -l token also removes its value`() {
        val kept = ClassicEngine.applyToArgs(arrayOf("ciadpi", "-l", "fake.bin", "-T4"))

        assertEquals(listOf("ciadpi", "-T4"), kept.toList())
    }

    @Test
    fun `command string keeps quoted host list intact`() {
        val command = "-T4 -Qr -l':\\xC2\\x00' -a5 -Kt,h -H:\"a b c\" -s1+s"

        val result = ClassicEngine.apply(command)

        assertFalse("-Qr" in result)
        assertFalse("-l" in result)
        assertTrue("result=[$result]", "a b c" in result)
        assertTrue("-s1+s" in result)
        assertEquals("result=[$result]", listOf("-T4", "-a5", "-Kt,h", "-H:a b c", "-s1+s"), shellSplit(result))
    }

    @Test
    fun `plain command passes through unchanged`() {
        val command = "-T4 -Kt,h -H:\"youtube.com\" -s1+s -a1"

        assertEquals(shellSplit(command), shellSplit(ClassicEngine.apply(command)))
    }
}

class LimeFlowCatalogTest {

    private val catalog = FlowsealProfiles.builtins()

    @Test
    fun `profile ids are unique`() {
        val ids = catalog.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `every profile has non empty arguments and name`() {
        catalog.forEach { profile ->
            assertTrue("${profile.id} arguments", profile.arguments.isNotBlank())
            assertTrue("${profile.id} name", profile.name.isNotBlank())
        }
    }

    @Test
    fun `zapret 1_10 ports are present`() {
        val ids = catalog.map { it.id }
        assertTrue(
            ids.containsAll(
                listOf(
                    "limeflow_z_alt",
                    "limeflow_z_alt2",
                    "limeflow_z_alt3",
                    "limeflow_z_alt4",
                    "limeflow_z_alt7",
                    "limeflow_z_alt9",
                    "limeflow_z_auto",
                    "limeflow_z_short",
                    "limeflow_zf_google",
                    "limeflow_zf_maxru",
                    "limeflow_zf_4pda",
                    "limeflow_zf_voice",
                    "limeflow_zf_autottl",
                )
            )
        )
        val ya = catalog.first { it.id == "limeflow_z_alt3" }
        assertTrue(ya.arguments.contains("-Qo"))
        assertTrue(ya.arguments.contains("-nya.ru"))
        val short = catalog.first { it.id == "limeflow_z_short" }
        assertTrue(short.arguments.contains("-Qr,m=100"))
        val autottl = catalog.first { it.id == "limeflow_zf_autottl" }
        assertTrue(autottl.arguments.contains("-z1:4:64"))
    }

    @Test
    fun `catalog arguments survive classic engine transform`() {
        // Ни одна встроенная стратегия не должна использовать "-l <файл>"
        // отдельным токеном: классический режим удаляет следующий за ним токен.
        catalog.forEach { profile ->
            val args = shellSplit(profile.arguments)
            assertFalse(
                "${profile.id} uses bare -l",
                args.any { it == "-l" },
            )
        }
    }
}
