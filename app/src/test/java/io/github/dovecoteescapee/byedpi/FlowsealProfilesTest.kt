package io.github.dovecoteescapee.byedpi

import io.github.dovecoteescapee.byedpi.data.FlowsealProfiles
import io.github.dovecoteescapee.byedpi.utility.shellSplit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowsealProfilesTest {
    private val scopedIds = setOf(
        "limeflow_universal",
        "limeflow_youtube",
        "limeflow_amnezia",
        "limeflow_discord",
        "limeflow_discord_alt",
        "limeflow_stream",
        "limeflow_mobile",
        "limeflow_rostelecom",
        "limeflow_mgts",
        "limeflow_mts",
    )

    @Test
    fun catalogHasUniqueIdsAndNames() {
        val profiles = FlowsealProfiles.all
        assertEquals(profiles.size, profiles.map { it.id }.toSet().size)
        assertEquals(profiles.size, profiles.map { it.name }.toSet().size)
        assertTrue(profiles.map { it.id }.containsAll(scopedIds))
    }

    @Test
    fun defaultProfileIsPresent() {
        assertTrue(FlowsealProfiles.all.any { it.id == "limeflow_universal" })
    }

    /*
     * Android kernels ship without CONFIG_TCP_MD5SIG and desync.c bails out of the whole
     * fake-packet stage when the setsockopt fails, so -S must never come back.
     */
    @Test
    fun noProfileUsesMd5sig() {
        FlowsealProfiles.all.forEach { profile ->
            val args = shellSplit(profile.arguments)
            assertFalse(
                "${profile.name} uses -S/--md5sig, unsupported on Android",
                args.any { it == "-S" || it == "--md5sig" },
            )
        }
    }

    @Test
    fun everyProfileProducesNonEmptyWellFormedArguments() {
        FlowsealProfiles.all.forEach { profile ->
            val args = shellSplit(profile.arguments)
            assertTrue("${profile.name} has no arguments", args.isNotEmpty())
            assertFalse(
                "${profile.name} contains an unresolved placeholder",
                "{" in profile.arguments,
            )
            assertTrue(
                "${profile.name} has a token that is not an option",
                args.all { it.startsWith("-") },
            )
        }
    }

    @Test
    fun everyProfileDeclaresQuicAndDiscordVoiceUdpGroups() {
        FlowsealProfiles.all.forEach { profile ->
            val args = shellSplit(profile.arguments)
            assertTrue("${profile.name} has no QUIC group", args.contains("-V443"))
            assertTrue(
                "${profile.name} has no Discord voice group",
                args.contains("-V19294-19344") && args.contains("-V50000-50100"),
            )
            assertFalse(
                "${profile.name} uses the wide ICE range that stalled tests",
                args.contains("-V50000-65535"),
            )
            assertFalse(
                "${profile.name} has an extra STUN group",
                args.contains("-V3478"),
            )
            assertTrue(
                "${profile.name} declares no UDP fake count",
                args.any { it.startsWith("-a") },
            )
        }
    }

    /*
     * The QUIC decoy is escaped hex. shellSplit() strips backslashes outside single quotes,
     * so a regression in the quoting would silently hand the engine the literal text "xC2".
     */
    @Test
    fun quicFakePayloadSurvivesArgumentSplitting() {
        FlowsealProfiles.all.forEach { profile ->
            val fake = shellSplit(profile.arguments).firstOrNull { it.startsWith("-l:") }
            assertTrue("${profile.name} has no QUIC fake payload: " + shellSplit(profile.arguments).take(10), fake != null)
            requireNotNull(fake)
            assertTrue(
                "${profile.name} lost the escape backslashes in its QUIC payload",
                fake.startsWith("-l:\\x"),
            )
            assertFalse(
                "${profile.name} still carries quote characters",
                '\'' in fake || '"' in fake,
            )
        }
    }

    @Test
    fun scopedProfilesRestrictYoutubeAndDiscordChainsToTheirDomains() {
        FlowsealProfiles.all.filter { it.id in scopedIds }.forEach { profile ->
            val hostGroups = shellSplit(profile.arguments).filter { it.startsWith("-H:") }
            assertEquals(
                "${profile.name} should scope YouTube, Discord, extra and retry hosts",
                4,
                hostGroups.size,
            )
            assertTrue(
                "${profile.name} has no YouTube scope",
                hostGroups.any { "googlevideo.com" in it && "youtube.com" in it && "video.google.com" in it },
            )
            assertTrue(
                "${profile.name} has no Discord scope",
                hostGroups.any { "discord.com" in it && "discordapp.net" in it },
            )
            hostGroups.forEach { group ->
                assertFalse("${profile.name} has an empty host scope", group == "-H:")
            }
        }
    }

    @Test
    fun everyTcpDesyncGroupCarriesAHostList() {
        FlowsealProfiles.all.forEach { profile ->
            val args = shellSplit(profile.arguments)
            args.forEachIndexed { index, token ->
                if (token.startsWith("-Kt")) {
                    val next = args.getOrNull(index + 1)
                    assertTrue(
                        "${profile.name} TCP group at $index has no -H, so it would desync every site",
                        next != null && next.startsWith("-H:"),
                    )
                }
            }
        }
    }

    /*
     * main.c parses -L as the letters s/o/n, not as the level 0-3 that byedpi/README.md
     * documents, so a numeric value makes parse_args reject the whole command line. The only
     * relevant mode, 's', sorts groups and would also break the positional `-An` cascade.
     */
    @Test
    fun noProfileUsesAutoMode() {
        FlowsealProfiles.all.forEach { profile ->
            val args = shellSplit(profile.arguments)
            assertFalse(
                "${profile.name} passes -L, which the engine rejects or which reorders groups",
                args.any { it == "-L" || it.startsWith("-L") || it == "--auto-mode" },
            )
        }
    }

    @Test
    fun scopedProfilesUseTheSkippedGroupCascade() {
        FlowsealProfiles.all.filter { it.id in scopedIds }.forEach { profile ->
            assertTrue(
                "${profile.name} has no skipped-group cascade",
                shellSplit(profile.arguments).contains("-An"),
            )
        }
    }

    /*
     * A group opened inside one of the chain building blocks would inherit none of the
     * -K/-H filters, quietly applying a service specific chain to every domain. Pinning the
     * exact group count is what catches that.
     */
    @Test
    fun scopedProfilesOpenExactlyTheGroupsTheCascadeDeclares() {
        FlowsealProfiles.all.filter { it.id in scopedIds }.forEach { profile ->
            val groups = shellSplit(profile.arguments).filter { it.startsWith("-A") }
            assertEquals(
                "${profile.name} opens an unexpected option group: $groups",
                listOf("-An", "-An", "-An", "-An", "-An", "-An", "-At,r,s"),
                groups,
            )
        }
    }

    @Test
    fun everyProfileEmitsUdpJunkOnQuicWithoutCps() {
        FlowsealProfiles.all.forEach { profile ->
            val args = shellSplit(profile.arguments)
            assertTrue("${profile.name} has no junk count", args.any { it.startsWith("-J") })
            assertFalse(
                "${profile.name} still passes -k / UDP CPS that stalled the 1.1.8 engine",
                args.any { it == "-k" || it.startsWith("-k") },
            )
            assertTrue(
                "${profile.name} has no junk size range",
                args.any { it.startsWith("-G") && '-' in it.drop(2) },
            )
        }
    }

    @Test
    fun youtubeWifiProfileUsesFakeTlsJunkAndLaterRounds() {
        val youtube = FlowsealProfiles.all.first { it.id == "limeflow_youtube" }
        val args = shellSplit(youtube.arguments)
        assertTrue(args.contains("-nwww.google.com"))
        assertTrue(args.contains("-Qr"))
        assertTrue(args.contains("-J10"))
        assertTrue(args.contains("-R1-8"))
        assertTrue(args.any { it.startsWith("-G64-") })
        assertEquals(io.github.dovecoteescapee.byedpi.data.ProfileKind.YOUTUBE, youtube.kind)
    }

    @Test
    fun discordProfileUsesWorkingGatewayChainAndNarrowVoicePorts() {
        val discord = FlowsealProfiles.all.first { it.id == "limeflow_discord" }
        val args = shellSplit(discord.arguments)
        assertTrue(args.contains("-nvk.com"))
        assertTrue(args.contains("-nwww.google.com"))
        assertFalse(args.contains("-nozon.ru"))
        assertTrue(args.contains("-V50000-50100"))
        assertFalse(args.contains("-V3478"))
        assertEquals(io.github.dovecoteescapee.byedpi.data.ProfileKind.DISCORD, discord.kind)
        assertTrue(FlowsealProfiles.all.any { it.id == "limeflow_discord_alt" })
    }

    @Test
    fun amneziaProfileUsesALargerJunkTrainWithoutCps() {
        val amnezia = FlowsealProfiles.all.first { it.id == "limeflow_amnezia" }
        val args = shellSplit(amnezia.arguments)
        assertTrue(args.contains("-J10"))
        assertTrue(args.contains("-G80-400"))
        assertFalse(args.any { it.startsWith("-k") })
        assertTrue(args.contains("-nwww.google.com"))
    }

    @Test
    fun profilesSetATimeoutSoTimeoutTriggersCanFire() {
        FlowsealProfiles.all.forEach { profile ->
            val args = shellSplit(profile.arguments)
            if (args.any { it.startsWith("-At") }) {
                assertTrue(
                    "${profile.name} uses a torst trigger without a timeout",
                    args.any { it.startsWith("-T") },
                )
            }
        }
    }
}
