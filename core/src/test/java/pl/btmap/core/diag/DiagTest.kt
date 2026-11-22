package pl.btmap.core.diag

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class DiagTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun `known requests encode as qualcomm tools send them`() {
        assertArrayEquals(bytes(0x00, 0x78, 0xF0, 0x7E), Hdlc.encode(bytes(0x00)))
        assertArrayEquals(bytes(0x7C, 0x93, 0x49, 0x7E), Hdlc.encode(bytes(0x7C)))
    }

    @Test
    fun `escapes and decodes round trip`() {
        val payload = bytes(0x10, 0x7E, 0x00, 0x7D, 0x55)
        val frames = mutableListOf<ByteArray>()
        Hdlc.Decoder().feed(Hdlc.encode(payload) + Hdlc.encode(bytes(0x00))) { frames += it }
        assertEquals(2, frames.size)
        assertArrayEquals(payload, frames[0])
    }

    @Test
    fun `decodes the version reply from a galaxy s25`() {
        // As received through diag_socket_log on an SM-S931B (Snapdragon 8 Elite, "pakala").
        val reply = "00 41 75 67 20 31 31 20 32 30 32 36 30 34 3a 34 31 3a 31 30 4d 61 79 20 30 37 20 32 30 32 36 32 32 3a 30 30 3a 30 30 70 61 6b 61 6c 61 2e 67 00 00 ff 11 05 00 02 8c fe a6 7e"
            .split(' ').map { it.toInt(16).toByte() }.toByteArray()
        val frames = mutableListOf<ByteArray>()
        val decoder = Hdlc.Decoder()
        decoder.feed(reply) { frames += it }
        assertEquals(0, decoder.badFrames)
        assertEquals(1, frames.size)
        assertEquals(0, frames[0][0].toInt())
        assertEquals("pakala", String(frames[0], 39, 6))
    }

    @Test
    fun `set mask enables exactly the chosen log items`() {
        val cmd = DiagCommands.setMask(0x0B, 0x9FF, listOf(0xB173, 0xB887, 0x1234))
        assertEquals(0x73, cmd[0].toInt())
        val mask = cmd.copyOfRange(16, cmd.size)
        assertEquals((0x9FF + 8) / 8, mask.size)
        assertEquals(1 shl (0x173 % 8), mask[0x173 / 8].toInt() and 0xFF)
        assertEquals(1 shl (0x887 % 8), mask[0x887 / 8].toInt() and 0xFF)
        assertEquals(2, mask.sumOf { Integer.bitCount(it.toInt() and 0xFF) })
    }

    @Test
    fun `reads the log code of a log packet`() {
        val frame = bytes(0x10, 0x00, 0x20, 0x00, 0x20, 0x00, 0x73, 0xB1, 0, 0, 0, 0, 0, 0, 0, 0)
        assertEquals(0xB173, DiagCommands.logCode(frame))
    }

    @Test
    fun `mask variants differ only in the item count`() {
        val a = DiagCommands.setMaskBits(0x0B, 0x9FF, listOf(0xB173))
        val b = DiagCommands.setMask(0x0B, 0x9FF, listOf(0xB173))
        assertEquals(0x9FF, java.nio.ByteBuffer.wrap(a).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(12))
        assertEquals(0xA00, java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(12))
        assertEquals(a.size, b.size)
        assertArrayEquals(bytes(0x60, 0x01), DiagCommands.eventReport(true))
        assertEquals("73 op=0 status=0 (12 B)", DiagCommands.describe(DiagCommands.disableAll + bytes(0, 0, 0, 0)))
    }
}
