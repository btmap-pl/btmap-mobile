package pl.btmap.core.radio

import pl.btmap.core.model.Band
import pl.btmap.core.model.Rat
import kotlin.math.roundToLong

/**
 * Channel number → band and downlink frequency (3GPP TS 36.101, 38.104, 25.101, 45.005).
 * Tables cover bands deployed in Poland and neighbouring countries; anything else falls back to
 * the band list reported by the modem.
 */
object Bands {

    private data class LteBand(val band: Int, val fDlLow: Double, val nOffset: Int, val nLast: Int)

    private val LTE = listOf(
        LteBand(1, 2110.0, 0, 599),
        LteBand(3, 1805.0, 1200, 1949),
        LteBand(7, 2620.0, 2750, 3449),
        LteBand(8, 925.0, 3450, 3799),
        LteBand(20, 791.0, 6150, 6449),
        LteBand(28, 758.0, 9210, 9659),
        LteBand(32, 1452.0, 9920, 10359),
        LteBand(38, 2570.0, 37750, 38249),
        LteBand(40, 2300.0, 38650, 39649),
        LteBand(42, 3400.0, 41590, 43589),
        LteBand(43, 3600.0, 43590, 45589),
    )

    private data class NrBand(val band: Int, val minMhz: Double, val maxMhz: Double)

    /** Downlink ranges; listed in preference order where bands overlap (n78 before n77). */
    private val NR = listOf(
        NrBand(28, 758.0, 803.0),
        NrBand(20, 791.0, 821.0),
        NrBand(8, 925.0, 960.0),
        NrBand(75, 1432.0, 1517.0),
        NrBand(3, 1805.0, 1880.0),
        NrBand(1, 2110.0, 2170.0),
        NrBand(40, 2300.0, 2400.0),
        NrBand(38, 2570.0, 2620.0),
        NrBand(7, 2620.0, 2690.0),
        NrBand(78, 3300.0, 3800.0),
        NrBand(77, 3300.0, 4200.0),
        NrBand(258, 24250.0, 27500.0),
    )

    /** UKE band labels by downlink frequency (as used in BTMap filters). */
    private val UKE_LABELS = listOf(
        Triple(758.0, 788.0, "700"),
        Triple(791.0, 821.0, "800"),
        Triple(925.0, 960.0, "900"),
        Triple(1452.0, 1492.0, "1500"),
        Triple(1805.0, 1880.0, "1800"),
        Triple(2110.0, 2170.0, "2100"),
        Triple(2300.0, 2400.0, "2300"),
        Triple(2570.0, 2690.0, "2600"),
        Triple(3400.0, 3600.0, "3500"),
        Triple(3600.0, 3800.0, "3600"),
    )

    fun ukeLabel(mhz: Double?): String? =
        mhz?.let { f -> UKE_LABELS.firstOrNull { f >= it.first && f <= it.second }?.third }

    // ---- LTE ---------------------------------------------------------------------------------

    fun lteDownlinkMhz(earfcn: Int): Double? =
        LTE.firstOrNull { earfcn in it.nOffset..it.nLast }?.let { round2(it.fDlLow + 0.1 * (earfcn - it.nOffset)) }

    fun lte(earfcn: Int?, reportedBands: IntArray? = null): Band? {
        if (earfcn == null || earfcn < 0) return null
        val row = LTE.firstOrNull { earfcn in it.nOffset..it.nLast }
        val number = row?.band ?: reportedBands?.firstOrNull() ?: return null
        val mhz = row?.let { round2(it.fDlLow + 0.1 * (earfcn - it.nOffset)) }
        return Band(Rat.LTE, number, ukeLabel(mhz) ?: lteFallbackLabel(number), mhz)
    }

    private fun lteFallbackLabel(band: Int) = when (band) {
        1 -> "2100"; 3 -> "1800"; 7 -> "2600"; 8 -> "900"; 20 -> "800"; 28 -> "700"; 38 -> "2600"
        else -> "B$band"
    }

    // ---- NR ----------------------------------------------------------------------------------

    /** NR-ARFCN → frequency on the global raster (TS 38.104 §5.4.2.1). */
    fun nrMhz(nrarfcn: Int): Double? = when {
        nrarfcn < 0 -> null
        nrarfcn < 600_000 -> round2(nrarfcn * 0.005)
        nrarfcn <= 2_016_666 -> round2(3000 + (nrarfcn - 600_000) * 0.015)
        nrarfcn <= 3_279_165 -> round2(24250.08 + (nrarfcn - 2_016_667) * 0.06)
        else -> null
    }

    fun nr(nrarfcn: Int?, reportedBands: IntArray? = null): Band? {
        if (nrarfcn == null || nrarfcn < 0) return null
        val mhz = nrMhz(nrarfcn)
        val fromTable = mhz?.let { f -> NR.firstOrNull { f >= it.minMhz && f <= it.maxMhz }?.band }
        // The modem knows better than a frequency table when bands overlap (n7/n38/n41, n77/n78).
        val reported = reportedBands?.firstOrNull { it > 0 }
        val number = reported ?: fromTable ?: return null
        return Band(Rat.NR, number, ukeLabel(mhz) ?: "n$number", mhz)
    }

    // ---- UMTS --------------------------------------------------------------------------------

    fun umts(uarfcn: Int?): Band? {
        if (uarfcn == null || uarfcn < 0) return null
        return when (uarfcn) {
            in 10562..10838 -> Band(Rat.WCDMA, 1, "2100", round2(uarfcn / 5.0))
            in 2937..3088 -> Band(Rat.WCDMA, 8, "900", round2(uarfcn / 5.0 + 340))
            else -> null
        }
    }

    // ---- GSM ---------------------------------------------------------------------------------

    fun gsm(arfcn: Int?): Band? {
        if (arfcn == null || arfcn < 0) return null
        return when (arfcn) {
            in 0..124 -> Band(Rat.GSM, 900, "900", round2(935 + 0.2 * arfcn))
            in 975..1023 -> Band(Rat.GSM, 900, "900", round2(935 + 0.2 * (arfcn - 1024)))
            in 512..885 -> Band(Rat.GSM, 1800, "1800", round2(1805.2 + 0.2 * (arfcn - 512)))
            else -> null
        }
    }

    private fun round2(v: Double) = (v * 100).roundToLong() / 100.0
}
