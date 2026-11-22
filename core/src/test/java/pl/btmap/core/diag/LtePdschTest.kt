package pl.btmap.core.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** PDSCH statistics recorded from a Galaxy S25 (Snapdragon 8 Elite, Orange RO, B20 10 MHz). */
class LtePdschTest {
    private val frames = javaClass.getResource("/diag/b173_s25.hex")!!.readText().trim().lines()
        .map { line -> line.chunked(2).map { it.toInt(16).toByte() }.toByteArray() }

    @Test
    fun `multi-sim wrapped packets are recognised`() {
        assertEquals(LtePdsch.CODE, DiagCommands.logCode(frames[0]))
    }

    @Test
    fun `first record matches the 3gpp tbs table`() {
        val blocks = LtePdsch.decode(DiagCommands.logPayload(frames[0])!!)
        assertEquals(23, blocks.size)
        val b = blocks[0]
        assertEquals(17, b.mcs)
        assertEquals(23, b.rbs)
        // 256QAM table: MCS 17 → I_TBS 19; TS 36.213 TBS(19, 23 PRB) = 12576 bits.
        assertEquals(12576, b.tbsBytes * 8)
        assertEquals(6, b.modulation)
        assertTrue(b.crcOk)
        assertEquals(0, b.rv)
    }

    @Test
    fun `mimo subframes carry two transport blocks`() {
        val blocks = LtePdsch.decode(DiagCommands.logPayload(frames[1])!!)
        val twoLayer = blocks.filter { it.layers == 2 }
        assertTrue(twoLayer.isNotEmpty())
        // Both blocks of a rank-2 subframe share its time.
        assertTrue(twoLayer.groupBy { it.timeMs }.values.any { it.size == 2 })
    }

    @Test
    fun `window summarises throughput and bler`() {
        val window = DownlinkWindow(1_000)
        val blocks = frames.flatMap { LtePdsch.decode(DiagCommands.logPayload(it)!!) }
        window.add(blocks, now = 0)
        val s = window.stats(now = 500)!!
        assertTrue(s.mbps > 0)
        assertTrue(s.bler in 0.0..1.0)
        assertEquals(1.0, s.modulation.values.sum(), 1e-9)
        assertEquals(null, window.stats(now = 5_000))
    }
}
