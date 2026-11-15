package pl.btmap.core.telephony

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.btmap.core.model.CarrierSource
import pl.btmap.core.model.Cell
import pl.btmap.core.model.CellIdentity
import pl.btmap.core.model.CellRole
import pl.btmap.core.model.CellSignal
import pl.btmap.core.model.Plmn
import pl.btmap.core.model.Rat
import pl.btmap.core.radio.Bands
import pl.btmap.core.radio.CellMath

class CarrierResolverTest {

    private val plus = Plmn("260", "01")

    private fun lte(earfcn: Int, pci: Int, eci: Long?, role: CellRole, bw: Int? = null, rsrp: Int = -100): Cell {
        val split = eci?.let(CellMath::splitEci)
        return Cell(
            identity = CellIdentity(
                rat = Rat.LTE, plmn = if (eci != null) plus else null, cid = eci, nodeId = split?.first,
                localCellId = split?.second, areaCode = null, physicalId = pci, channel = earfcn,
                band = Bands.lte(earfcn), bandwidthKhz = bw,
            ),
            signal = CellSignal(rsrp = rsrp),
            role = role,
            taDistanceMeters = null,
            timestampNanos = 0,
        )
    }

    @Test
    fun `scell learned on the same eNB is matched by bandwidth`() {
        val dict = CellDictionary()
        // Earlier the phone camped on 15087:22, B3 EARFCN 1355, PCI 364, 20 MHz.
        dict.learn(plus, lte(1355, 364, 15087L * 256 + 22, CellRole.PRIMARY, bw = 20_000))

        val pcell = lte(3526, 3, 15087L * 256 + 12, CellRole.PRIMARY, bw = 5_000)
        val carriers = CarrierResolver.resolve(listOf(pcell), listOf(5_000, 20_000), plus, dict)

        assertEquals(2, carriers.size)
        assertEquals(8, carriers[0].band!!.number)
        assertEquals(3, carriers[1].band!!.number)
        assertEquals(1355, carriers[1].channel)
        assertEquals(20, carriers[1].bandwidthMhz)
        assertEquals(CarrierSource.LEARNED, carriers[1].source)
    }

    @Test
    fun `measured neighbour of the same eNB becomes the scell`() {
        val dict = CellDictionary()
        dict.learn(plus, lte(1355, 364, 15087L * 256 + 22, CellRole.PRIMARY, bw = 20_000))
        val pcell = lte(3526, 3, 15087L * 256 + 12, CellRole.PRIMARY, bw = 5_000)
        val neighbour = dict.enrich(plus, lte(1355, 364, null, CellRole.NEIGHBOUR, rsrp = -97))
        val other = lte(6300, 120, null, CellRole.NEIGHBOUR, rsrp = -80)

        val carriers = CarrierResolver.resolve(listOf(pcell, neighbour, other), listOf(5_000, 20_000), plus, dict)

        assertEquals(1355, carriers[1].channel)
        assertEquals(-97, carriers[1].cell!!.signal.rsrp)
    }

    @Test
    fun `unknown scell keeps its bandwidth`() {
        val pcell = lte(3526, 3, 15087L * 256 + 12, CellRole.PRIMARY, bw = 5_000)
        val carriers = CarrierResolver.resolve(listOf(pcell), listOf(5_000, 20_000), plus, CellDictionary())

        assertEquals(2, carriers.size)
        assertNull(carriers[1].band)
        assertEquals(20, carriers[1].bandwidthMhz)
        assertEquals(CarrierSource.UNKNOWN, carriers[1].source)
    }

    @Test
    fun `no aggregation gives only the pcell`() {
        val pcell = lte(3476, 325, 24144L * 256 + 50, CellRole.PRIMARY)
        val carriers = CarrierResolver.resolve(listOf(pcell), listOf(5_000), plus, CellDictionary())
        assertEquals(1, carriers.size)
        assertEquals(5, carriers[0].bandwidthMhz)
    }

    @Test
    fun `dictionary fills neighbour identity`() {
        val dict = CellDictionary()
        dict.learn(plus, lte(1355, 364, 15087L * 256 + 22, CellRole.PRIMARY, bw = 20_000))
        val n = dict.enrich(plus, lte(1355, 364, null, CellRole.NEIGHBOUR))
        assertEquals("15087:22", n.identity.nodeLabel)
        assertEquals(true, n.identity.learned)
    }

    @Test
    fun `bogus identity bandwidth never replaces a trusted one`() {
        val dict = CellDictionary()
        val eci = 15087L * 256 + 12
        dict.learn(plus, lte(3526, 3, eci, CellRole.PRIMARY, bw = 5_000), trustedBandwidth = true)
        dict.learn(plus, lte(3526, 3, eci, CellRole.PRIMARY, bw = 1_400))
        assertEquals(5_000, dict.lookup(plus, Rat.LTE, 3526, 3)!!.bandwidthKhz)
        dict.learn(plus, lte(3526, 3, eci, CellRole.PRIMARY, bw = 10_000), trustedBandwidth = true)
        assertEquals(10_000, dict.lookup(plus, Rat.LTE, 3526, 3)!!.bandwidthKhz)
    }

    private fun nr(arfcn: Int, pci: Int, role: CellRole) = Cell(
        identity = CellIdentity(
            rat = Rat.NR, plmn = null, cid = null, nodeId = null, localCellId = null, areaCode = null,
            physicalId = pci, channel = arfcn, band = Bands.nr(arfcn),
        ),
        signal = CellSignal(ssRsrp = -110),
        role = role,
        taDistanceMeters = null,
        timestampNanos = 0,
    )

    @Test
    fun `modem carriers are used as reported`() {
        val pcell = lte(1355, 94, 12646L * 256 + 12, CellRole.PRIMARY)
        val leg = nr(518910, 508, CellRole.SECONDARY)
        val modem = listOf(
            PhysicalChannel(true, Rat.LTE, 3, 1355, 94, 20_000, 1_820_500),
            PhysicalChannel(false, Rat.LTE, 8, 3526, 103, 5_000, 932_600),
            PhysicalChannel(false, Rat.NR, 41, 518910, 508, 40_000, 2_594_550),
        )
        val carriers = CarrierResolver.resolve(listOf(pcell, leg), listOf(20_000, 5_000, 40_000), plus, CellDictionary(), modem)
        assertEquals(listOf(3, 8, 41), carriers.map { it.band!!.number })
        assertEquals(listOf(20, 5, 40), carriers.map { it.bandwidthMhz })
        assertEquals(Rat.NR, carriers[2].rat)
        assertEquals(leg, carriers[2].cell)
        assertTrue(carriers.all { it.source == CarrierSource.REPORTED })
    }

    @Test
    fun `wide bandwidth goes to the nr leg, never to lte`() {
        val dict = CellDictionary()
        // B8 cell learned earlier on the same eNB must not be offered 40 MHz.
        dict.learn(plus, lte(3526, 103, 12646L * 256 + 2, CellRole.PRIMARY, bw = 5_000), trustedBandwidth = true)
        val pcell = lte(1355, 94, 12646L * 256 + 12, CellRole.PRIMARY, bw = 20_000)
        val leg = nr(518910, 508, CellRole.SECONDARY)
        val carriers = CarrierResolver.resolve(listOf(pcell, leg), listOf(20_000, 40_000), plus, dict)
        assertEquals(2, carriers.size)
        assertEquals(Rat.NR, carriers[1].rat)
        assertEquals(40, carriers[1].bandwidthMhz)
    }

    @Test
    fun `implausible bandwidth stays unknown`() {
        val dict = CellDictionary()
        dict.learn(plus, lte(3526, 103, 12646L * 256 + 2, CellRole.PRIMARY, bw = 5_000), trustedBandwidth = true)
        val pcell = lte(1355, 94, 12646L * 256 + 12, CellRole.PRIMARY, bw = 20_000)
        val carriers = CarrierResolver.resolve(listOf(pcell), listOf(20_000, 15_000), plus, dict)
        assertEquals(CarrierSource.UNKNOWN, carriers[1].source)
    }
}
