package pl.btmap.core.coverage

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import pl.btmap.core.LiveState
import pl.btmap.core.location.GeoFix
import pl.btmap.core.model.Carrier
import pl.btmap.core.model.CarrierSource
import pl.btmap.core.model.Cell
import pl.btmap.core.model.CellIdentity
import pl.btmap.core.model.CellRole
import pl.btmap.core.model.CellSignal
import pl.btmap.core.model.NetworkMode
import pl.btmap.core.model.NetworkSnapshot
import pl.btmap.core.model.Plmn
import pl.btmap.core.model.Rat
import pl.btmap.core.model.SimState
import pl.btmap.core.radio.Bands

class CoverageRecorderTest {
    @get:Rule val tmp = TemporaryFolder()

    private val plus = Plmn("260", "01")

    private fun carrier(earfcn: Int, rsrp: Int?, primary: Boolean, age: Long? = null): Carrier {
        val id = CellIdentity(Rat.LTE, plus, 1, 1, 1, 1, 1, earfcn, Bands.lte(earfcn))
        val cell = rsrp?.let { Cell(id, CellSignal(rsrp = it), if (primary) CellRole.PRIMARY else CellRole.SECONDARY, null, 0) }
        return Carrier(Rat.LTE, primary, id.band, earfcn, 1, 20_000, CarrierSource.REPORTED, cell, id, age)
    }

    private fun state(accuracy: Float, vararg carriers: Carrier) = LiveState(
        snapshot = NetworkSnapshot(
            listOf(
                SimState(
                    1, 0, "Plus", plus, plus, null, "Plus", NetworkMode.LTE_CA, false, true,
                    emptyList(), emptyList(), carriers.toList(), updatedAtMillis = 0,
                ),
            ),
            0,
        ),
        fix = GeoFix(52.4192, 21.3044, accuracy, null, null, null, System.currentTimeMillis(), "gps"),
    )

    @Test
    fun `records every measured band once per cell`() {
        val rec = CoverageRecorder(tmp.newFile("q.json"))
        val s = state(10f, carrier(1355, -100, true), carrier(3526, -105, false, age = 2_000), carrier(6300, null, false))
        rec.record(s)
        rec.record(s)
        assertEquals(2, rec.pending)
    }

    @Test
    fun `skips imprecise fixes and stale scell values`() {
        val rec = CoverageRecorder(tmp.newFile("q.json"))
        rec.record(state(200f, carrier(1355, -100, true)))
        rec.record(state(10f, carrier(3526, -105, false, age = 40_000)))
        assertEquals(0, rec.pending)
    }

    @Test
    fun `queue survives a restart`() {
        val f = tmp.newFile("q.json")
        CoverageRecorder(f).apply { record(state(10f, carrier(1355, -100, true))); flush() }
        assertEquals(1, CoverageRecorder(f).pending)
    }
}
