package io.github.dovecoteescapee.byedpi

import io.github.dovecoteescapee.byedpi.data.BypassHost
import io.github.dovecoteescapee.byedpi.data.BypassHostKind
import io.github.dovecoteescapee.byedpi.data.BypassHosts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BypassHostsTest {
    @Test
    fun defaultsAreEnabledAndCoverZapretLists() {
        val selection = BypassHosts.defaultSelection
        assertTrue(selection.hasYoutube)
        assertTrue(selection.hasDiscord)
        assertTrue(selection.hasExtra)
        assertTrue("youtube.com" in selection.youtube)
        assertTrue("googlevideo.com" in selection.youtube)
        assertTrue("discord.com" in selection.discord)
        assertTrue("cloudflare-ech.com" in selection.extra)
    }

    @Test
    fun disabledYoutubeLeavesDiscordAndExtra() {
        val selection = BypassHosts.selection(setOf("youtube.com", "googlevideo.com"))
        assertFalse("youtube.com" in selection.youtube)
        assertTrue("discord.com" in selection.discord)
    }

    @Test
    fun customHostsJoinTheExtraScope() {
        val custom = listOf(BypassHost("blocked.example", BypassHostKind.CUSTOM, builtin = false))
        val selection = BypassHosts.selection(emptySet(), custom)
        assertTrue("blocked.example" in selection.extra)
    }

    @Test
    fun normalizeAcceptsUrlsAndRejectsJunk() {
        assertEquals("youtube.com", BypassHosts.normalize("https://www.youtube.com/watch?v=1"))
        assertNull(BypassHosts.normalize("not a domain"))
        assertNull(BypassHosts.normalize("localhost"))
    }
}
