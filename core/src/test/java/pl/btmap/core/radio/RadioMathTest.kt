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

    @Test
    fun `wrong band from the modem is ignored`() {
        // MediaTek reports n1 for every NSA carrier; values from a POCO X6 Pro log.
        val n1 = intArrayOf(1)
        assertEquals(78, Bands.nr(640704, n1)!!.number) // Orange 3610 MHz
        assertEquals(41, Bands.nr(518910, n1)!!.number) // Plus 2600 TDD
        assertEquals("2600", Bands.nr(518910, n1)!!.label)
        assertEquals(28, Bands.nr(152690, n1)!!.number) // 700 MHz
        assertEquals(1, Bands.nr(428910, n1)!!.number) // real n1, 2100 MHz
        // A band the modem reports that does contain the frequency still wins.
        assertEquals(38, Bands.nr(518910, intArrayOf(38))!!.number)
        assertEquals(20, Bands.nrAt(800.0, intArrayOf(20))!!.number)
        // No channel but a reported downlink frequency (PhysicalChannelConfig).
        assertEquals(78, Bands.nrAt(3610.56, n1)!!.number)
        assertNull(Bands.nrAt(null, n1)?.takeIf { it.number != 1 })
    }

    @Test
    fun `lte bands outside Poland with uplink`() {
        // T-Mobile US values from a BTSearch app screenshot.
        val b12 = Bands.lte(5035)!!
        assertEquals(12, b12.number)
        assertEquals("700", b12.label)
        assertEquals(731.5, b12.downlinkMhz!!, 0.001)
        assertEquals(701.5, b12.uplinkMhz!!, 0.001)
        assertEquals(23035, Bands.lteUplinkEarfcn(5035))

        val b66 = Bands.lte(66736)!!
        assertEquals(66, b66.number)
        assertEquals(2140.0, b66.downlinkMhz!!, 0.001)
        assertEquals(1740.0, b66.uplinkMhz!!, 0.001)
        // Upper part of B66 is downlink-only.
        assertNull(Bands.lte(67300)!!.uplinkMhz)

        assertEquals(71, Bands.lte(68861)!!.number)
        assertEquals("600", Bands.lte(68861)!!.label)
        assertEquals(2, Bands.lte(975)!!.number)
        assertEquals(1967.5, Bands.lte(975)!!.downlinkMhz!!, 0.001)
        // Polish B3 keeps its UKE label and gets an uplink.
        assertEquals(1747.9, Bands.lte(1579)!!.uplinkMhz!!, 0.001)
        // Downlink-only B32 and TDD B38.
        assertNull(Bands.lte(10000)!!.uplinkMhz)
        assertEquals(Bands.lte(38000)!!.downlinkMhz, Bands.lte(38000)!!.uplinkMhz)
    }

    @Test
    fun `nr bands outside Poland`() {
        assertEquals(71, Bands.nr(126000)!!.number) // 630 MHz
        assertEquals("600", Bands.nr(126000)!!.label)
        assertEquals(41, Bands.nr(520110, intArrayOf(41))!!.number)
        assertEquals(79, Bands.nr(720000)!!.number) // 4800 MHz
        assertEquals(258, Bands.nr(2070000)!!.number) // ~27.4 GHz
        assertEquals(66, Bands.nr(428000, intArrayOf(66))!!.number) // reported n66 inside 2110–2170
        // n78 3528.48 MHz is TDD: uplink equals downlink; n28 has a 55 MHz gap.
        assertEquals(3528.48, Bands.nr(635232)!!.uplinkMhz!!, 0.001)
        assertEquals(763.45 - 55, Bands.nr(152690)!!.uplinkMhz!!, 0.001)
    }

    @Test
    fun `umts and gsm outside Poland`() {
        assertEquals(2, Bands.umts(9800)!!.number)
        assertEquals(1960.0, Bands.umts(9800)!!.downlinkMhz!!, 0.001)
        assertEquals(1880.0, Bands.umts(9800)!!.uplinkMhz!!, 0.001)
        assertEquals(5, Bands.umts(4400)!!.number)
        assertEquals(850, Bands.gsm(190)!!.number)
        assertEquals(881.6, Bands.gsm(190)!!.downlinkMhz!!, 0.001)
        assertEquals(890.0, Bands.gsm(0)!!.uplinkMhz!!, 0.001)
    }
}
