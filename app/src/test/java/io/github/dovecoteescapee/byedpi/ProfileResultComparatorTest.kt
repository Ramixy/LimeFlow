package io.github.dovecoteescapee.byedpi

import io.github.dovecoteescapee.byedpi.core.ProfileTestResult
import io.github.dovecoteescapee.byedpi.core.ServiceCategory
import io.github.dovecoteescapee.byedpi.core.TargetResult
import io.github.dovecoteescapee.byedpi.core.TestTarget
import io.github.dovecoteescapee.byedpi.core.profileResultComparator
import io.github.dovecoteescapee.byedpi.data.FlowsealProfile
import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileResultComparatorTest {

    private fun profile(id: String) = FlowsealProfile(
        id = id,
        name = id.uppercase(),
        method = "test",
        description = "",
        arguments = "-T4",
    )

    private fun result(
        id: String,
        protocolSuccess: Int,
        pingSuccess: Int,
        averagePingMs: Double?,
    ) = ProfileTestResult(
        profile = profile(id),
        protocolSuccess = protocolSuccess,
        pingSuccess = pingSuccess,
        averagePingMs = averagePingMs,
        targetResults = emptyList(),
    )

    @Test
    fun `best strategy sorts first`() {
        val worst = result("worst", 0, 0, 500.0)
        val middle = result("middle", 20, 6, 80.0)
        val best = result("best", 45, 18, 20.0)

        val ranked = listOf(worst, best, middle).sortedWith(profileResultComparator)

        assertEquals("best", ranked.first().profile.id)
        assertEquals("worst", ranked.last().profile.id)
    }

    @Test
    fun `ties break by ping success then lower ping`() {
        val a = result("a", 30, 10, 100.0)
        val b = result("b", 30, 12, 300.0)
        val c = result("c", 30, 12, 40.0)

        val ranked = listOf(a, c, b).sortedWith(profileResultComparator)

        assertEquals(listOf("c", "b", "a"), ranked.map { it.profile.id })
    }

    @Test
    fun `missing ping sorts last within a tie`() {
        val timedOut = result("timeout", 10, 0, null)
        val slow = result("slow", 10, 0, 900.0)

        val ranked = listOf(timedOut, slow).sortedWith(profileResultComparator)

        assertEquals("slow", ranked.first().profile.id)
        assertEquals("timeout", ranked.last().profile.id)
    }

    @Test
    fun `service scores count protocol checks per category`() {
        val youtubeTarget = TestTarget("YouTubeWeb", "www.youtube.com", ServiceCategory.YOUTUBE)
        val discordTarget = TestTarget("DiscordAPI", "discord.com", ServiceCategory.DISCORD)
        val result = ProfileTestResult(
            profile = profile("x"),
            protocolSuccess = 0,
            pingSuccess = 0,
            averagePingMs = null,
            targetResults = listOf(
                TargetResult(youtubeTarget, httpOk = true, tls12Ok = true, tls13Ok = false),
                TargetResult(discordTarget, httpOk = false, tls12Ok = false, tls13Ok = false),
            ),
        )

        assertEquals(66, result.youtubeScore)
        assertEquals(0, result.discordScore)
    }
}
