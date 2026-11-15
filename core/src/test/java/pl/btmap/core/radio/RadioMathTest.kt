package pl.btmap.core.radio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RadioMathTest {

    @Test
    fun `eci splits into eNB and cell`() {
        // T-Mobile CZ from the NetMonster reference screenshot: CI 36347158 = 141981:22.
        assertEquals(141981L to 22, CellMath.splitEci(36347158))
    }

    @Test
    fun `nci split matches btmap pl search`() {
        assertEquals(56680L to 35, CellMath.splitNci(14510115))
        val full = (1234567L shl 12) + 301
        assertEquals(1234567L to 301, CellMath.splitNci(full))
    }

    @Test
    fun `lte earfcn to band`() {
        val b3 = Bands.lte(1579)!!
        assertEquals(3, b3.number)
        assertEquals("1800", b3.label)
        assertEquals(1842.9, b3.downlinkMhz!!, 0.001)

        assertEquals("800", Bands.lte(6400)!!.label)
        assertEquals(20, Bands.lte(6400)!!.number)
        assertEquals("2100", Bands.lte(100)!!.label)
        assertEquals("2600", Bands.lte(3050)!!.label)
        assertEquals("900", Bands.lte(3500)!!.label)
        assertEquals("700", Bands.lte(9360)!!.label)
    }

    @Test
    fun `nr arfcn to band`() {
        val n78 = Bands.nr(635232)!!
        assertEquals(78, n78.number)
        assertEquals(3528.48, n78.downlinkMhz!!, 0.001)
        assertEquals("3500", n78.label)

        val c3700 = Bands.nr(649344)!!
        assertEquals("3600", c3700.label)

        val n28 = Bands.nr(152690)!!
        assertEquals(28, n28.number)
        assertEquals("700", n28.label)

        // Modem-reported band wins over the table where bands overlap.
        assertEquals(77, Bands.nr(635232, intArrayOf(77))!!.number)
    }

    @Test
    fun `umts and gsm channels`() {
        assertEquals("2100", Bands.umts(10700)!!.label)
        assertEquals("900", Bands.umts(3011)!!.label)
        assertEquals("900", Bands.gsm(60)!!.label)
        assertEquals("1800", Bands.gsm(700)!!.label)
        assertNull(Bands.gsm(5000))
    }

    @Test
    fun `timing advance distance`() {
        assertEquals(234, CellMath.lteTaMeters(3)) // NetMonster shows "3 | 234 m"
        assertEquals(78, CellMath.lteTaMeters(1))
        assertNull(CellMath.lteTaMeters(null))
        assertNull(CellMath.lteTaMeters(1282)) // modem sentinel for "no TA"
        assertEquals(554, CellMath.gsmTaMeters(1))
        assertEquals(150, CellMath.nrTaMeters(1))
    }

    @Test
    fun `sector numbering`() {
        assertEquals(1, CellMath.sector(1))
        assertEquals(2, CellMath.sector(22))
        assertEquals(1, CellMath.sector(11))
        assertEquals(3, CellMath.sector(13))
    }
}
