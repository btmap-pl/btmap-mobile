package pl.btmap.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.btmap.core.model.Cell
import pl.btmap.core.model.CellIdentity
import pl.btmap.core.model.CellRole
import pl.btmap.core.model.CellSignal
import pl.btmap.core.model.NetworkMode
import pl.btmap.core.model.NetworkSnapshot
import pl.btmap.core.model.Plmn
import pl.btmap.core.model.Rat
import pl.btmap.core.model.SimState

class CellLogTest {

    private val plus = Plmn("260", "01")

    private fun cell(pci: Int, earfcn: Int, eci: Long?, role: CellRole, rsrp: Int) = Cell(
        identity = CellIdentity(
            rat = Rat.LTE, plmn = if (eci != null) plus else null, cid = eci,
            nodeId = eci?.shr(8), localCellId = eci?.and(0xFF)?.toInt(),
            areaCode = null, physicalId = pci, channel = earfcn, band = null,
        ),
        signal = CellSignal(rsrp = rsrp),
        role = role,
        taDistanceMeters = null,
        timestampNanos = 0,
    )

    private fun snapshot(at: Long, vararg cells: Cell) = NetworkSnapshot(
        listOf(
            SimState(
                subscriptionId = 1, slotIndex = 0, simName = "Plus", simPlmn = plus, registeredPlmn = plus,
                operator = null, networkOperatorName = "Plus", mode = NetworkMode.LTE, isRoaming = false,
                isDataSim = true, cells = cells.toList(), carrierBandwidthsKhz = emptyList(), carriers = emptyList(),
                updatedAtMillis = at,
            ),
        ),
        at,
    )

    @Test
    fun `logs only cells the phone is connected to`() {
        val log = CellLog()
        log.record(
            snapshot(
                1_000,
                cell(364, 1355, 15087L * 256 + 2, CellRole.PRIMARY, -110),
                cell(3, 3526, 15087L * 256 + 12, CellRole.SECONDARY, -112),
                cell(103, 3526, null, CellRole.NEIGHBOUR, -112),
            ),
            null,
        )
        val entries = log.entries.value
        assertEquals(setOf(364, 3), entries.map { it.identity.physicalId }.toSet())
        assertTrue(entries.all { it.wasServing })
    }

    @Test
    fun `repeated samples update one entry and keep history`() {
        val log = CellLog()
        val eci = 15087L * 256 + 2
        log.record(snapshot(1_000, cell(364, 1355, eci, CellRole.PRIMARY, -110)), null)
        log.record(snapshot(3_000, cell(364, 1355, eci, CellRole.PRIMARY, -105)), null)
        val e = log.entries.value.single()
        assertEquals(2, e.samples)
        assertEquals(listOf(-110, -105), e.history)
        assertEquals(-105, e.bestDbm)
        assertEquals(1_000, e.firstSeenMillis)
        assertEquals(3_000, e.lastSeenMillis)
    }

    @Test
    fun `neighbour sample of a logged cell is ignored`() {
        val log = CellLog()
        val eci = 15087L * 256 + 2
        log.record(snapshot(1_000, cell(364, 1355, eci, CellRole.PRIMARY, -110)), null)
        log.record(snapshot(2_000, cell(364, 1355, eci, CellRole.NEIGHBOUR, -118)), null)
        val e = log.entries.value.single()
        assertEquals(1, e.samples)
        assertEquals(1_000, e.lastSeenMillis)
    }

    @Test
    fun `ntm export follows the format btsearch and netmonster read`() {
        val log = CellLog()
        val lte = cell(364, 1355, 15087L * 256 + 2, CellRole.PRIMARY, -110).let {
            it.copy(identity = it.identity.copy(areaCode = 11018))
        }
        val nr = Cell(
            identity = CellIdentity(Rat.NR, plus, 947003622L, 231203L, 230, 62, 278, 643296, null),
            signal = CellSignal(ssRsrp = -95), role = CellRole.SECONDARY, taDistanceMeters = null, timestampNanos = 0,
        )
        val umts = Cell(
            identity = CellIdentity(Rat.WCDMA, plus, 3100L * 65536 + 64181, 3100L, 64181, 42003, 211, 10737, null),
            signal = CellSignal(rscp = -90), role = CellRole.PRIMARY, taDistanceMeters = null, timestampNanos = 0,
        )
        log.record(snapshot(1_000, lte, nr), null)
        log.record(snapshot(2_000, umts), null)
        val lines = log.toNtm().trim().lines()
        assertEquals(3, lines.size)
        val byRat = lines.associateBy { it.substringBefore(';') }
        // RAT;MCC;MNC;CID;TAC;NODE;PCI;LAT;LON;DESCRIPTION;CHANNEL
        assertEquals("4G;260;01;2;11018;15087;364;;;BTMap;1355", byRat["4G"])
        assertEquals("5G;260;01;947003622;62;231203;278;;;BTMap;643296", byRat["5G"])
        assertEquals("3G;260;01;64181;42003;3100;211;;;BTMap;10737", byRat["3G"])
        // Our own .ntm reader takes it back.
        lines.forEach { assertEquals(11, it.split(';').size) }
    }
}
