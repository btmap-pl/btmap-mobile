package pl.btmap.core.stations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import pl.btmap.core.model.Rat

class NtmParserTest {

    @Test
    fun `btsearch gsm row`() {
        val c = NtmParser.parse(
            "2G;260;01;10000;34001;2147483647;2147483647;52.037035;15.618711;[BT31000] Cigacice - gm. Sulechów, ul. Kolejowa 4 - wieża kratowa Cellnex / Plus | A3: 80°;2147483647",
        )!!
        assertEquals(Rat.GSM, c.rat)
        assertEquals(1, c.mnc)
        assertEquals(10000L, c.cid)
        assertNull(c.node)
        assertNull(c.pci)
        assertEquals("BT31000", c.stationId)
        assertEquals("Cigacice - gm. Sulechów, ul. Kolejowa 4 - wieża kratowa Cellnex / Plus", c.name)
    }

    @Test
    fun `lte row with enb and local cid`() {
        val c = NtmParser.parse("4G;260;01;12;11018;15087;3;52.4;21.3;[BT15087] Klembów;3526")!!
        assertEquals(Rat.LTE, c.rat)
        assertEquals(15087L, c.node)
        assertEquals(12L, c.cid)
        assertEquals(3, c.pci)
        assertEquals(3526, c.channel)
    }

    @Test
    fun `lte row with full eci and no enb`() {
        val c = NtmParser.parse("4G;260;01;3862284;11018;2147483647;3;52.4;21.3;Klembów;3526")!!
        assertEquals(15087L, c.node)
        assertEquals(12L, c.cid)
    }

    @Test
    fun `nr row with nci`() {
        val c = NtmParser.parse("5G;260;06;14510115;1350;0;338;52.4;21.3;Wola;635232")!!
        assertEquals(56680L, c.node)
        assertEquals(35L, c.cid)
        assertEquals(635232, c.channel)
    }

    @Test
    fun `description with semicolon keeps channel last`() {
        val c = NtmParser.parse("4G;260;02;1;100;200;7;52.0;21.0;Dach; budynek A;1300")!!
        assertEquals("Dach; budynek A", c.name)
        assertEquals(1300, c.channel)
    }

    @Test
    fun `rejects junk`() {
        assertNull(NtmParser.parse("error code: 504"))
        assertNull(NtmParser.parse("4G;260;01;1;1;1;1;0;0;x;1"))
    }
}
