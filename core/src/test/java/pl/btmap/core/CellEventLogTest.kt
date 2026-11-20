package pl.btmap.core

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import pl.btmap.core.model.Carrier
import pl.btmap.core.model.CarrierSource
import pl.btmap.core.model.CellIdentity
import pl.btmap.core.model.NetworkMode
import pl.btmap.core.model.NetworkSnapshot
import pl.btmap.core.model.Plmn
import pl.btmap.core.model.Rat
import pl.btmap.core.model.SimState
import pl.btmap.core.radio.Bands

class CellEventLogTest {
    @get:Rule val tmp = TemporaryFolder()
    private val plus = Plmn("260", "01")

    private fun c(earfcn: Int, pci: Int, primary: Boolean, cid: Long = 15087L * 256 + 2): Carrier {
        val id = CellIdentity(Rat.LTE, plus, cid, cid shr 8, (cid and 0xFF).toInt(), 1, pci, earfcn, Bands.lte(earfcn))
        return Carrier(Rat.LTE, primary, id.band, earfcn, pci, 20_000, CarrierSource.REPORTED, null, id)
    }

    private fun snap(t: Long, vararg carriers: Carrier) = NetworkSnapshot(
        listOf(SimState(1, 0, "Plus", plus, plus, null, "Plus", NetworkMode.LTE, false, true, emptyList(), emptyList(), carriers.toList(), updatedAtMillis = t)),
        t,
    )

    @Test
    fun `logs changes only`() {
        val log = CellEventLog()
        log.record(snap(1, c(1355, 364, true)), null)
        log.record(snap(2, c(1355, 364, true)), null)          // nothing changed
        log.record(snap(3, c(1355, 365, true)), null)          // PCI change
        log.record(snap(4, c(1355, 365, true), c(3526, 3, false)), null) // SCell added
        log.record(snap(5), null)                              // no service
        log.record(snap(6, c(1355, 365, true)), null)          // back
        val e = log.events.value
        assertEquals(5, e.size)
        assertEquals(listOf("odzyskano zasięg"), e[0].changes)
        assertEquals(listOf("utrata zasięgu"), e[1].changes)
        assertEquals(listOf("+ 900"), e[2].changes)
        assertEquals(listOf("PCI 364 → 365"), e[3].changes)
        assertEquals(listOf("start"), e[4].changes)
    }

    @Test
    fun `restart continues without a fake change`() {
        val f = tmp.newFile("e.json")
        CellEventLog(f).apply { record(snap(1, c(1355, 364, true)), null); flush() }
        val again = CellEventLog(f).apply { loadFromDisk() }
        again.record(snap(2, c(1355, 364, true)), null)
        assertEquals(1, again.events.value.size)
    }
}
