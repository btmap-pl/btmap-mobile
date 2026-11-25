package pl.btmap.core.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** NR PDSCH status recorded from a Galaxy S25 (Snapdragon 8 Elite, Digi RO, 5G NSA, 52 PRB). */
class NrPdschTest {
    private val frames = javaClass.getResource("/diag/b887_s25.hex")!!.readText().trim().lines()
        .map { line -> line.chunked(2).map { it.toInt(16).toByte() }.toByteArray() }
    private val blocks = frames.flatMap { NrPdsch.decode(DiagCommands.logPayload(it)!!) }

    @Test
    fun `log code is recognised`() {
        assertEquals(NrPdsch.CODE, DiagCommands.logCode(frames[0]))
    }

    @Test
    fun `system information block at mcs 0 is one layer`() {
        val b = NrPdsch.decode(DiagCommands.logPayload(frames[0])!!).first()
        assertEquals(0, b.mcs)
        assertEquals(48, b.rbs)
        assertEquals(1, b.layers)
        assertEquals(153, b.tbsBytes)
        assertTrue(b.crcOk)
    }

    @Test
    fun `one layer block matches the 3gpp tbs calculation`() {
        val b = NrPdsch.decode(DiagCommands.logPayload(frames[56])!!)[1]
        assertEquals(19, b.mcs)
        assertEquals(24, b.rbs)
        assertEquals(1, b.layers)
        assertEquals(6, b.modulation)
        // TS 38.214: 64QAM R 873/1024, 24 PRB × 132 RE, 1 layer → N_info 16 205 → TBS 16 136 bits.
        assertEquals(16136, b.tbsBytes * 8)
    }

    @Test
    fun `two layer block matches the 3gpp tbs calculation`() {
        val b = NrPdsch.decode(DiagCommands.logPayload(frames[74])!!)[1]
        assertEquals(9, b.mcs)
        assertEquals(24, b.rbs)
        assertEquals(2, b.layers)
        assertEquals(4, b.modulation)
        // 16QAM R 616/1024, 24 PRB × 120 RE, 2 layers → N_info 13 860 → TBS 13 832 bits.
        assertEquals(13832, b.tbsBytes * 8)
    }

    @Test
    fun `failed first transmission is followed by a retransmission`() {
        val failed = blocks.indexOfFirst { it.rv == 0 && !it.crcOk }
        assertTrue(failed >= 0)
        val retx = blocks.drop(failed + 1).first { it.harq == blocks[failed].harq }
        assertFalse(retx.rv == 0)
    }

    @Test
    fun `window summarises nr throughput`() {
        val window = DownlinkWindow(1_000)
        window.add(blocks, now = 0)
        val s = window.stats(now = 500)!!
        assertTrue(s.mbps > 0)
        assertEquals(2, s.layersMax)
        assertEquals(1.0, s.layers.values.sum(), 1e-9)
    }
}
