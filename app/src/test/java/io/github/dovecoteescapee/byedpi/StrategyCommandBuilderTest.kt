package io.github.dovecoteescapee.byedpi

import io.github.dovecoteescapee.byedpi.data.PositionAnchor
import io.github.dovecoteescapee.byedpi.data.StrategyBuildError
import io.github.dovecoteescapee.byedpi.data.StrategyCommandBuilder
import io.github.dovecoteescapee.byedpi.data.StrategyDraft
import io.github.dovecoteescapee.byedpi.data.StrategyMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StrategyCommandBuilderTest {
    @Test
    fun buildsACompleteStrategy() {
        val result = StrategyCommandBuilder.build(
            StrategyDraft(
                method = StrategyMethod.DISORDER,
                positions = "1, 3",
                anchor = PositionAnchor.SNI,
                tlsRecordEnabled = true,
                tlsRecordPosition = "5+s",
                hostMixedCase = true,
                removeHostSpaces = true,
                fakeTtl = "8",
                udpFakeCount = "2",
                dropSack = true,
                advancedArguments = "-a1",
            )
        )

        assertTrue(result.isValid)
        assertEquals("-d1+s -d3+s -r5+s -Mh,r -t8 -a2 -Y -a1", result.command)
    }

    @Test
    fun rejectsInvalidPosition() {
        val result = StrategyCommandBuilder.build(
            StrategyDraft(
                method = StrategyMethod.SPLIT,
                positions = "sni",
            )
        )

        assertEquals(StrategyBuildError.INVALID_POSITION, result.error)
    }

    @Test
    fun doesNotDuplicateAnExplicitAnchor() {
        val result = StrategyCommandBuilder.build(
            StrategyDraft(
                method = StrategyMethod.FAKE,
                positions = "-1+s",
                anchor = PositionAnchor.HTTP_HOST,
            )
        )

        assertEquals("-f-1+s", result.command)
    }

    @Test
    fun buildsAnExtendedTwoMethodStrategy() {
        val result = StrategyCommandBuilder.build(
            StrategyDraft(
                method = StrategyMethod.SPLIT,
                positions = "1+s",
                secondaryMethod = StrategyMethod.DISORDER,
                secondaryPositions = "3",
                secondaryAnchor = PositionAnchor.HTTP_HOST,
                protocolTls = true,
                protocolUdp = true,
                portFilter = "443",
                requestRounds = "1-3",
                timeoutSeconds = "2.5",
                tcpFastOpen = true,
                fakeTtl = "6",
                fakeOffset = "-1+s",
                fakeSni = "www.iana.org",
                oobData = "\\x0a",
                tlsMinor = "3",
            )
        )

        assertTrue(result.isValid)
        assertEquals(
            "-Ktu -V443 -R1-3 -T2.5 -F -s1+s -d3+h -t6 " +
                "-O-1+s -nwww.iana.org -e\\x0a -m3",
            result.command,
        )
    }

    @Test
    fun rejectsReversedPortRange() {
        val result = StrategyCommandBuilder.build(
            StrategyDraft(
                portFilter = "5000-443",
                advancedArguments = "-s1",
            )
        )

        assertEquals(StrategyBuildError.INVALID_PORT_FILTER, result.error)
    }

    @Test
    fun acceptsLongAdvancedArgumentsWithValues() {
        val result = StrategyCommandBuilder.build(
            StrategyDraft(advancedArguments = "--fake -1 --ttl 8")
        )

        assertEquals("--fake -1 --ttl 8", result.command)
    }
}
