package pl.btmap.core.radio

import pl.btmap.core.model.Band
import pl.btmap.core.model.Rat
import kotlin.math.roundToLong

/**
 * Channel number → band, downlink and uplink frequency, for every band in 3GPP TS 36.101 (LTE),
 * 38.104 (NR), 25.101 (UMTS) and 45.005 (GSM) that phones use in practice, so the app works abroad
 * and in roaming as well as in Poland.
 */
object Bands {

    // ---- Labels ------------------------------------------------------------------------------

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

    /** Common name of a band outside the Polish UKE labels ("700" for B12, "600" for n71…). */
    private val COMMON_NAMES = mapOf(
        1 to "2100", 2 to "1900", 3 to "1800", 4 to "1700", 5 to "850", 6 to "850", 7 to "2600",
        8 to "900", 9 to "1800", 10 to "1700", 11 to "1500", 12 to "700", 13 to "700", 14 to "700",
        17 to "700", 18 to "800", 19 to "800", 20 to "800", 21 to "1500", 22 to "3500", 23 to "2000",
        24 to "1600", 25 to "1900", 26 to "850", 27 to "800", 28 to "700", 29 to "700", 30 to "2300",
        31 to "450", 32 to "1500", 33 to "1900", 34 to "2000", 35 to "1900", 36 to "1900", 37 to "1900",
        38 to "2600", 39 to "1900", 40 to "2300", 41 to "2500", 42 to "3500", 43 to "3700", 44 to "700",
        45 to "1500", 46 to "5200", 47 to "5900", 48 to "3600", 49 to "3600", 50 to "1500", 51 to "1500",
        52 to "3300", 53 to "2500", 65 to "2100", 66 to "1700", 67 to "700", 68 to "700", 69 to "2600",
        70 to "2000", 71 to "600", 72 to "450", 73 to "450", 74 to "1500", 75 to "1500", 76 to "1500",
        77 to "3700", 78 to "3500", 79 to "4700", 85 to "700", 87 to "410", 88 to "410", 90 to "2500",
        91 to "1500", 92 to "1500", 93 to "1500", 94 to "1500", 96 to "6000", 102 to "6000", 104 to "6700",
        257 to "28G", 258 to "26G", 259 to "41G", 260 to "39G", 261 to "28G", 262 to "47G",
    )

    private fun label(mhz: Double?, band: Int, prefix: String) = ukeLabel(mhz) ?: COMMON_NAMES[band] ?: "$prefix$band"

    // ---- LTE (TS 36.101 table 5.7.3-1) ---------------------------------------------------------

    /**
     * @param ulLow uplink frequency of the first channel; null for downlink-only bands; for TDD it
     *   equals [dlLow]. @param ulChannels how many channels have an uplink when fewer than downlink
     *   channels exist (B66, B70).
     */
    private class LteBand(
        val band: Int, val dlLow: Double, val nOffset: Int, val nLast: Int,
        val ulLow: Double?, val ulOffset: Int, val ulChannels: Int = Int.MAX_VALUE,
    )

    private val LTE = listOf(
        LteBand(1, 2110.0, 0, 599, 1920.0, 18000),
        LteBand(2, 1930.0, 600, 1199, 1850.0, 18600),
        LteBand(3, 1805.0, 1200, 1949, 1710.0, 19200),
        LteBand(4, 2110.0, 1950, 2399, 1710.0, 19950),
        LteBand(5, 869.0, 2400, 2649, 824.0, 20400),
        LteBand(6, 875.0, 2650, 2749, 830.0, 20650),
        LteBand(7, 2620.0, 2750, 3449, 2500.0, 20750),
        LteBand(8, 925.0, 3450, 3799, 880.0, 21450),
        LteBand(9, 1844.9, 3800, 4149, 1749.9, 21800),
        LteBand(10, 2110.0, 4150, 4749, 1710.0, 22150),
        LteBand(11, 1475.9, 4750, 4949, 1427.9, 22750),
        LteBand(12, 729.0, 5010, 5179, 699.0, 23010),
        LteBand(13, 746.0, 5180, 5279, 777.0, 23180),
        LteBand(14, 758.0, 5280, 5379, 788.0, 23280),
        LteBand(17, 734.0, 5730, 5849, 704.0, 23730),
        LteBand(18, 860.0, 5850, 5999, 815.0, 23850),
        LteBand(19, 875.0, 6000, 6149, 830.0, 24000),
        LteBand(20, 791.0, 6150, 6449, 832.0, 24150),
        LteBand(21, 1495.9, 6450, 6599, 1447.9, 24450),
        LteBand(22, 3510.0, 6600, 7399, 3410.0, 24600),
        LteBand(23, 2180.0, 7500, 7699, 2000.0, 25500),
        LteBand(24, 1525.0, 7700, 8039, 1626.5, 25700),
        LteBand(25, 1930.0, 8040, 8689, 1850.0, 26040),
        LteBand(26, 859.0, 8690, 9039, 814.0, 26690),
        LteBand(27, 852.0, 9040, 9209, 807.0, 27040),
        LteBand(28, 758.0, 9210, 9659, 703.0, 27210),
        LteBand(29, 717.0, 9660, 9769, null, 0),
        LteBand(30, 2350.0, 9770, 9869, 2305.0, 27660),
        LteBand(31, 462.5, 9870, 9919, 452.5, 27760),
        LteBand(32, 1452.0, 9920, 10359, null, 0),
        LteBand(33, 1900.0, 36000, 36199, 1900.0, 36000),
        LteBand(34, 2010.0, 36200, 36349, 2010.0, 36200),
        LteBand(35, 1850.0, 36350, 36949, 1850.0, 36350),
        LteBand(36, 1930.0, 36950, 37549, 1930.0, 36950),
        LteBand(37, 1910.0, 37550, 37749, 1910.0, 37550),
        LteBand(38, 2570.0, 37750, 38249, 2570.0, 37750),
        LteBand(39, 1880.0, 38250, 38649, 1880.0, 38250),
        LteBand(40, 2300.0, 38650, 39649, 2300.0, 38650),
        LteBand(41, 2496.0, 39650, 41589, 2496.0, 39650),
        LteBand(42, 3400.0, 41590, 43589, 3400.0, 41590),
        LteBand(43, 3600.0, 43590, 45589, 3600.0, 43590),
        LteBand(44, 703.0, 45590, 46589, 703.0, 45590),
        LteBand(45, 1447.0, 46590, 46789, 1447.0, 46590),
        LteBand(46, 5150.0, 46790, 54539, 5150.0, 46790),
        LteBand(47, 5855.0, 54540, 55239, 5855.0, 54540),
        LteBand(48, 3550.0, 55240, 56739, 3550.0, 55240),
        LteBand(49, 3550.0, 56740, 58239, 3550.0, 56740),
        LteBand(50, 1432.0, 58240, 59089, 1432.0, 58240),
        LteBand(51, 1427.0, 59090, 59139, 1427.0, 59090),
        LteBand(52, 3300.0, 59140, 60139, 3300.0, 59140),
        LteBand(53, 2483.5, 60140, 60254, 2483.5, 60140),
        LteBand(65, 2110.0, 65536, 66435, 1920.0, 131072),
        LteBand(66, 2110.0, 66436, 67335, 1710.0, 131972, ulChannels = 700),
        LteBand(67, 738.0, 67336, 67535, null, 0),
        LteBand(68, 753.0, 67536, 67835, 698.0, 132672),
        LteBand(69, 2570.0, 67836, 68335, null, 0),
        LteBand(70, 1995.0, 68336, 68585, 1695.0, 132972, ulChannels = 150),
        LteBand(71, 617.0, 68586, 68935, 663.0, 133122),
        LteBand(72, 461.0, 68936, 68985, 451.0, 133472),
        LteBand(73, 460.0, 68986, 69035, 450.0, 133522),
        LteBand(74, 1475.0, 69036, 69465, 1427.0, 133572),
        LteBand(75, 1432.0, 69466, 70315, null, 0),
        LteBand(76, 1427.0, 70316, 70365, null, 0),
        LteBand(85, 728.0, 70366, 70545, 698.0, 134002),
        LteBand(87, 420.0, 70546, 70595, 410.0, 134182),
        LteBand(88, 422.0, 70596, 70645, 412.0, 134232),
    )

    private fun lteRow(earfcn: Int) = LTE.firstOrNull { earfcn in it.nOffset..it.nLast }

    fun lteDownlinkMhz(earfcn: Int): Double? = lteRow(earfcn)?.let { round2(it.dlLow + 0.1 * (earfcn - it.nOffset)) }

    /** Uplink EARFCN paired with a downlink EARFCN; null for downlink-only channels. */
    fun lteUplinkEarfcn(earfcn: Int): Int? {
        val row = lteRow(earfcn) ?: return null
        val n = earfcn - row.nOffset
        if (row.ulLow == null || n >= row.ulChannels) return null
        return row.ulOffset + n
    }

    fun lte(earfcn: Int?, reportedBands: IntArray? = null): Band? {
        if (earfcn == null || earfcn < 0) return null
        val row = lteRow(earfcn)
        val number = row?.band ?: reportedBands?.firstOrNull { it > 0 } ?: return null
        if (row == null) return Band(Rat.LTE, number, label(null, number, "B"), null)
        val n = earfcn - row.nOffset
        val dl = round2(row.dlLow + 0.1 * n)
        val ul = row.ulLow?.takeIf { n < row.ulChannels }?.let { round2(it + 0.1 * n) }
        return Band(Rat.LTE, number, label(dl, number, "B"), dl, ul)
    }

    // ---- NR (TS 38.104 table 5.2-1) -------------------------------------------------------------

    /**
     * @param ulLow uplink frequency matching [dlMin] for bands with a fixed duplex gap; equals
     *   [dlMin] for TDD; null for downlink-only bands and bands without a fixed gap (n66, n70).
     */
    private class NrBand(val band: Int, val dlMin: Double, val dlMax: Double, val ulLow: Double?)

    /**
     * Downlink ranges. Where bands overlap, the first match wins unless the modem names another
     * band that contains the frequency, so the order puts what European networks use first.
     */
    private val NR = listOf(
        NrBand(71, 617.0, 652.0, 663.0),
        NrBand(29, 717.0, 728.0, null),
        NrBand(12, 729.0, 746.0, 699.0),
        NrBand(85, 728.0, 746.0, 698.0),
        NrBand(67, 738.0, 758.0, null),
        NrBand(13, 746.0, 756.0, 777.0),
        NrBand(20, 791.0, 821.0, 832.0),
        NrBand(28, 758.0, 803.0, 703.0),
        NrBand(14, 758.0, 768.0, 788.0),
        NrBand(5, 869.0, 894.0, 824.0),
        NrBand(18, 860.0, 875.0, 815.0),
        NrBand(26, 859.0, 894.0, 814.0),
        NrBand(8, 925.0, 960.0, 880.0),
        NrBand(76, 1427.0, 1432.0, null),
        NrBand(51, 1427.0, 1432.0, 1427.0),
        NrBand(75, 1432.0, 1517.0, null),
        NrBand(50, 1432.0, 1517.0, 1432.0),
        NrBand(74, 1475.0, 1518.0, 1427.0),
        NrBand(24, 1525.0, 1559.0, 1626.5),
        NrBand(3, 1805.0, 1880.0, 1710.0),
        NrBand(39, 1880.0, 1920.0, 1880.0),
        NrBand(2, 1930.0, 1990.0, 1850.0),
        NrBand(25, 1930.0, 1995.0, 1850.0),
        NrBand(70, 1995.0, 2020.0, null),
        NrBand(34, 2010.0, 2025.0, 2010.0),
        NrBand(1, 2110.0, 2170.0, 1920.0),
        NrBand(66, 2110.0, 2200.0, null),
        NrBand(65, 2110.0, 2200.0, 1920.0),
        NrBand(40, 2300.0, 2400.0, 2300.0),
        NrBand(30, 2350.0, 2360.0, 2305.0),
        NrBand(53, 2483.5, 2495.0, 2483.5),
        NrBand(7, 2620.0, 2690.0, 2500.0),
        // 2600 TDD: NR networks use n41; n38 is its LTE-era subset.
        NrBand(41, 2496.0, 2690.0, 2496.0),
        NrBand(90, 2496.0, 2690.0, 2496.0),
        NrBand(38, 2570.0, 2620.0, 2570.0),
        NrBand(78, 3300.0, 3800.0, 3300.0),
        NrBand(77, 3300.0, 4200.0, 3300.0),
        NrBand(48, 3550.0, 3700.0, 3550.0),
        NrBand(79, 4400.0, 5000.0, 4400.0),
        NrBand(46, 5150.0, 5925.0, 5150.0),
        NrBand(96, 5925.0, 7125.0, 5925.0),
        NrBand(102, 5925.0, 6425.0, 5925.0),
        NrBand(104, 6425.0, 7125.0, 6425.0),
        NrBand(258, 24250.0, 27500.0, 24250.0),
        NrBand(257, 26500.0, 29500.0, 26500.0),
        NrBand(261, 27500.0, 28350.0, 27500.0),
        NrBand(260, 37000.0, 40000.0, 37000.0),
        NrBand(259, 39500.0, 43500.0, 39500.0),
        NrBand(262, 47200.0, 48200.0, 47200.0),
    )

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
        return nrAt(nrMhz(nrarfcn), reportedBands)
    }

    /**
     * NR band at a downlink frequency. A band reported by the modem wins where bands overlap
     * (n7/n38/n41, n77/n78), but only when it actually contains the frequency: MediaTek modems
     * report "n1" for every 5G NSA carrier.
     */
    fun nrAt(mhz: Double?, reportedBands: IntArray? = null): Band? {
        val reported = reportedBands?.filter { it > 0 }.orEmpty()
        val candidates = mhz?.let { f -> NR.filter { f >= it.dlMin && f <= it.dlMax } }.orEmpty()
        val row = candidates.firstOrNull { it.band in reported } ?: candidates.firstOrNull()
        val number = row?.band
            // A frequency outside the table: trust the modem unless it names a band we know elsewhere.
            ?: reported.firstOrNull { r -> mhz == null || NR.none { it.band == r } }
            ?: return null
        val ul = if (row?.ulLow != null && mhz != null) round2(row.ulLow + (mhz - row.dlMin)) else null
        return Band(Rat.NR, number, label(mhz, number, "n"), mhz, ul)
    }

    // ---- UMTS (TS 25.101 table 5.2) -------------------------------------------------------------

    private class UmtsBand(val band: Int, val first: Int, val last: Int, val dlOffset: Double, val duplex: Double)

    private val UMTS = listOf(
        UmtsBand(1, 10562, 10838, 0.0, 190.0),
        UmtsBand(2, 9662, 9938, 0.0, 80.0),
        UmtsBand(3, 1162, 1513, 1575.0, 95.0),
        UmtsBand(4, 1537, 1738, 1805.0, 400.0),
        UmtsBand(5, 4357, 4458, 0.0, 45.0),
        UmtsBand(8, 2937, 3088, 340.0, 45.0),
        UmtsBand(19, 712, 763, 735.0, 45.0),
    )

    fun umts(uarfcn: Int?): Band? {
        if (uarfcn == null || uarfcn < 0) return null
        val row = UMTS.firstOrNull { uarfcn in it.first..it.last } ?: return null
        val dl = round2(uarfcn / 5.0 + row.dlOffset)
        return Band(Rat.WCDMA, row.band, label(dl, row.band, "B"), dl, round2(dl - row.duplex))
    }

    // ---- GSM (TS 45.005 §2) ----------------------------------------------------------------------

    /** ARFCN 512–885 is DCS 1800 here; PCS 1900 reuses 512–810 but Android does not tell them apart. */
    fun gsm(arfcn: Int?): Band? {
        if (arfcn == null || arfcn < 0) return null
        val (band, dl, duplex) = when (arfcn) {
            in 0..124 -> Triple(900, 935 + 0.2 * arfcn, 45.0)
            in 975..1023 -> Triple(900, 935 + 0.2 * (arfcn - 1024), 45.0)
            in 128..251 -> Triple(850, 869.2 + 0.2 * (arfcn - 128), 45.0)
            in 512..885 -> Triple(1800, 1805.2 + 0.2 * (arfcn - 512), 95.0)
            else -> return null
        }
        return Band(Rat.GSM, band, band.toString(), round2(dl), round2(dl - duplex))
    }

    /** Band from a UKE label as used on btmap.pl ("1800" → LTE B3 / NR n3); no channel, so no frequency. */
    fun fromLabel(rat: Rat, label: String): Band? {
        val number = when (rat) {
            Rat.LTE -> mapOf("700" to 28, "800" to 20, "900" to 8, "1500" to 32, "1800" to 3, "2100" to 1, "2600" to 7)[label]
            Rat.NR -> mapOf("700" to 28, "800" to 20, "900" to 8, "1800" to 3, "2100" to 1, "2600" to 7, "3500" to 78, "3600" to 78)[label]
            Rat.WCDMA -> mapOf("900" to 8, "2100" to 1)[label]
            Rat.GSM -> mapOf("900" to 900, "1800" to 1800)[label]
        } ?: return null
        return Band(rat, number, label, null)
    }

    /** Label for a band known only by number (no channel): its common name where known. */
    fun nameOf(rat: Rat, number: Int): String =
        COMMON_NAMES[number] ?: if (rat == Rat.NR) "n$number" else "B$number"

    private fun round2(v: Double) = (v * 100).roundToLong() / 100.0
}
