package io.github.dovecoteescapee.byedpi

import io.github.dovecoteescapee.byedpi.core.ProfileTestResult
import io.github.dovecoteescapee.byedpi.core.ServiceCategory
import io.github.dovecoteescapee.byedpi.core.TargetResult
import io.github.dovecoteescapee.byedpi.core.TestTarget
import io.github.dovecoteescapee.byedpi.core.profileResultComparator
import io.github.dovecoteescapee.byedpi.core.serviceResultComparator
import io.github.dovecoteescapee.byedpi.data.FlowsealProfile
import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileResultComparatorTest {

    private val yt = TestTarget("YouTubeWeb", "www.youtube.com", ServiceCategory.YOUTUBE)
    private val ytFreeze = TestTarget(
        "YouTubeFreeze64K", "i.ytimg.com", ServiceCategory.YOUTUBE, minBytes = 40_000,
    )
    private val dc = TestTarget("DiscordAPI", "discord.com", ServiceCategory.DISCORD)

    private fun profile(id: String) = FlowsealProfile(
        id = id,
        name = id.uppercase(),
        method = "test",
        description = "",
        arguments = "-T4",
    )

    private fun target(t: TestTarget, ok: Int, latency: Double? = null) = TargetResult(
        target = t,
        httpOk = ok >= 1,
        tls12Ok = ok >= 2,
        tls13Ok = ok >= 3,
        latencyMs = latency,
    )

    private fun result(id: String, vararg targets: TargetResult) = ProfileTestResult(
        profile = profile(id),
        protocolSuccess = targets.sumOf { it.protocolSuccess },
        pingSuccess = 0,
        averagePingMs = null,
        targetResults = targets.toList(),
    )

    @Test
    fun `best strategy sorts first`() {
        val worst = result("worst", target(yt, 0), target(dc, 0))
        val middle = result("middle", target(yt, 3), target(dc, 0))
        val best = result("best", target(yt, 3), target(dc, 3))

        val ranked = listOf(worst, best, middle).sortedWith(profileResultComparator)

        assertEquals(listOf("best", "middle", "worst"), ranked.map { it.profile.id })
    }

    @Test
    fun `passing the freeze check outweighs a handshake`() {
        // Same number of passed checks, but only one gets past 16-20 KB.
        val freezes = result("freezes", target(yt, 3), target(ytFreeze, 0), target(dc, 3))
        val flows = result("flows", target(yt, 0), target(ytFreeze, 3), target(dc, 3))

        val ranked = listOf(freezes, flows).sortedWith(profileResultComparator)

        assertEquals("flows", ranked.first().profile.id)
    }

    @Test
    fun `ties break by real latency, unknown last`() {
        val slow = result("slow", target(yt, 3, 900.0), target(dc, 3, 900.0))
        val fast = result("fast", target(yt, 3, 120.0), target(dc, 3, 140.0))
        val unknown = result("unknown", target(yt, 3), target(dc, 3))

        val ranked = listOf(unknown, slow, fast).sortedWith(profileResultComparator)

        assertEquals(listOf("fast", "slow", "unknown"), ranked.map { it.profile.id })
    }

    @Test
    fun `service ranking puts the service score first`() {
        val allround = result("allround", target(yt, 2), target(dc, 3))
        val youtubeOnly = result("youtube", target(yt, 3), target(dc, 0))

        val ranked = listOf(allround, youtubeOnly).sortedWith(serviceResultComparator { it.youtubeScore })

        assertEquals("youtube", ranked.first().profile.id)
    }

    @Test
    fun `service scores weight content checks`() {
        val r = result("x", target(yt, 3), target(ytFreeze, 0), target(dc, 2))

        // YouTube: 3*1 passed of (3*1 + 3*3) = 3/12
        assertEquals(25, r.youtubeScore)
        assertEquals(66, r.discordScore)
    }
}
