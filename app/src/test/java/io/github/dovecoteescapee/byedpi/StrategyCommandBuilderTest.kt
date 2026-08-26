package io.github.dovecoteescapee.byedpi

import io.github.dovecoteescapee.byedpi.data.PositionAnchor
import io.github.dovecoteescapee.byedpi.data.StrategyBuildError
import io.github.dovecoteescapee.byedpi.data.StrategyCommandBuilder
import io.github.dovecoteescapee.byedpi.data.StrategyDraft
import io.github.dovecoteescapee.byedpi.data.StrategyMethod
import io.github.dovecoteescapee.byedpi.utility.shellSplit
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
            "-Kt,u -V443 -R1-3 -T2.5 -F -s1+s -d3+h -t6 " +
                "-O-1+s -nwww.iana.org -e\\x0a -m3",
            result.command,
        )
    }

    /*
     * The engine parses -K one letter at a time and then jumps to the next comma, so an
     * uncommaed list would quietly drop every protocol after the first.
     */
    @Test
    fun separatesEverySelectedProtocolWithACommaSoNoneAreDropped() {
        val result = StrategyCommandBuilder.build(
            StrategyDraft(
                method = StrategyMethod.SPLIT,
                positions = "1",
                protocolTls = true,
                protocolHttp = true,
                protocolUdp = true,
                protocolIpv4 = true,
            )
        )

        assertEquals("-Kt,h,u,i -s1", result.command)
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

    @Test
    fun quotesTheHostScopeSoItStaysOneArgument() {
        val result = StrategyCommandBuilder.build(
            StrategyDraft(
                method = StrategyMethod.SPLIT,
                positions = "1",
                anchor = PositionAnchor.SNI,
                hostScope = "youtube.com  googlevideo.com\nytimg.com",
            )
        )

        assertTrue(result.isValid)
        assertEquals("-H:\"youtube.com googlevideo.com ytimg.com\" -s1+s", result.command)
    }

    @Test
    fun quotedHostScopeSurvivesRoundTripThroughShellSplit() {
        val result = StrategyCommandBuilder.build(
            StrategyDraft(
                method = StrategyMethod.OOB,
                positions = "1",
                hostScope = "discord.com discordapp.net",
            )
        )

        val args = shellSplit(result.command)
        assertEquals(listOf("-H:discord.com discordapp.net", "-o1"), args)
    }

    @Test
    fun rejectsAMalformedHostScope() {
        val result = StrategyCommandBuilder.build(
            StrategyDraft(
                method = StrategyMethod.SPLIT,
                positions = "1",
                hostScope = "youtube.com not a domain!",
            )
        )

        assertEquals(StrategyBuildError.INVALID_HOST_SCOPE, result.error)
    }

    @Test
    fun emitsAmneziaStyleUdpJunk() {
        val result = StrategyCommandBuilder.build(
            StrategyDraft(
                method = StrategyMethod.FAKE,
                positions = "-1",
                randomizeFake = true,
                udpJunkCount = "8",
                udpJunkSize = "64-320",
            )
        )

        assertTrue(result.isValid)
        assertEquals("-f-1 -Qr -J8 -G64-320", result.command)
    }

    @Test
    fun rejectsAnInvalidJunkRange() {
        val result = StrategyCommandBuilder.build(
            StrategyDraft(
                udpJunkCount = "4",
                udpJunkSize = "400-80",
            )
        )

        assertEquals(StrategyBuildError.INVALID_UDP_JUNK, result.error)
    }

    @Test
    fun emitsTheRandomisedFakeFlag() {
        val result = StrategyCommandBuilder.build(
            StrategyDraft(
                method = StrategyMethod.FAKE,
                positions = "-1",
                randomizeFake = true,
                fakeTtl = "8",
            )
        )

        assertEquals("-f-1 -Qr -t8", result.command)
    }
}
