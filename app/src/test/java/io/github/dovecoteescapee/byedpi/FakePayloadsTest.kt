package io.github.dovecoteescapee.byedpi

import io.github.dovecoteescapee.byedpi.data.FakePayloads
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FakePayloadsTest {

    private fun decode(escaped: String): ByteArray {
        val regex = Regex("\\\\x([0-9A-Fa-f]{2})")
        val matches = regex.findAll(escaped).toList()
        return matches.map { it.groupValues[1].toInt(16).toByte() }.toByteArray()
    }

    private fun checkRaw(name: String, escaped: String, size: Int, first: IntArray) {
        val bytes = decode(escaped)
        assertEquals("$name: string must be pure \\xHH escapes", size * 4, escaped.length)
        assertEquals("$name: decoded size", size, bytes.size)
        first.forEachIndexed { i, b ->
            assertEquals("$name: byte #$i", b.toByte(), bytes[i])
        }
        assertFalse("$name: no single quotes", escaped.contains('\''))
    }

    @Test
    fun `google tls fake matches zapret binary`() {
        checkRaw("FAKE_TLS_GOOGLE", FakePayloads.FAKE_TLS_GOOGLE, 681, intArrayOf(0x16, 0x03, 0x01))
    }

    @Test
    fun `max ru tls fake matches zapret binary`() {
        checkRaw("FAKE_TLS_MAXRU", FakePayloads.FAKE_TLS_MAXRU, 664, intArrayOf(0x16, 0x03, 0x01))
    }

    @Test
    fun `4pda tls fake matches zapret binary`() {
        checkRaw("FAKE_TLS_4PDA", FakePayloads.FAKE_TLS_4PDA, 284, intArrayOf(0x16, 0x03, 0x01))
    }

    @Test
    fun `quic fake matches zapret binary`() {
        checkRaw("FAKE_QUIC_GOOGLE", FakePayloads.FAKE_QUIC_GOOGLE, 1200, intArrayOf(0xC3))
    }

    @Test
    fun `discord voice fake matches zapret binary`() {
        checkRaw("FAKE_DISCORD_UDP", FakePayloads.FAKE_DISCORD_UDP, 1200, intArrayOf(0xC5))
    }
}
