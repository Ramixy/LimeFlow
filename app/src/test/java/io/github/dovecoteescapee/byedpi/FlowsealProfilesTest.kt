package io.github.dovecoteescapee.byedpi

import io.github.dovecoteescapee.byedpi.data.FlowsealProfiles
import io.github.dovecoteescapee.byedpi.utility.shellSplit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowsealProfilesTest {
    @Test
    fun catalogContainsEveryFlowsealGeneralStrategy() {
        assertEquals(26, FlowsealProfiles.all.size)
        assertEquals(26, FlowsealProfiles.all.map { it.id }.toSet().size)
    }

    @Test
    fun everyStrategyProducesValidNonEmptyArguments() {
        FlowsealProfiles.all.forEach { profile ->
            val args = shellSplit(profile.arguments)
            assertTrue("${profile.name} has no arguments", args.isNotEmpty())
            assertTrue("${profile.name} contains an unresolved placeholder", "{sni}" !in profile.arguments)
            val regularUdp = profile.arguments.startsWith(
                    "-Ku -V443 -a11 -An -Ku -V19294-19344 -a6 -An " +
                        "-Ku -V50000-50100 -a6 -An "
            )
            val universalUdp = profile.arguments.startsWith(
                "-Ku -V443 -a12 -An -Ku -V19294-19344 -a8 -An " +
                    "-Ku -V50000-50100 -a8 -An "
            )
            assertTrue(
                "${profile.name} has no Android QUIC/Discord UDP groups",
                regularUdp || universalUdp,
            )
            assertTrue("${profile.name} has a malformed option", args.all { it.startsWith("-") || it.toIntOrNull() != null || it.contains(".") || it.contains("+") })
        }
    }
}
