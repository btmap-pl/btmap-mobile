package pl.btmap.core.radio

/**
 * Identity arithmetic shared with btmap.pl (`shared/cellSearch.ts`), so a cell shown in the app
 * and a cell typed into the website search resolve to the same eNB/gNB.
 */
object CellMath {

    /** Largest 28-bit E-UTRAN cell identity. */
    const val MAX_ECI = 268_435_455L

    /** LTE ECI = eNB (20 bit) * 256 + local cell id (8 bit). */
    fun splitEci(eci: Long): Pair<Long, Int> = (eci shr 8) to (eci and 0xFF).toInt()

    /**
     * NR NCI → gNB + cell. The gNB id length is operator-configured (22..32 bits). Polish networks
     * report 8-bit cell parts for identities below 2^24 (Play: 14510115 = 56680:35) and 12-bit ones
     * for full 36-bit identities — the same rule btmap.pl uses for search.
     */
    fun splitNci(nci: Long): Pair<Long, Int> =
        if (nci >= 16_777_216L) (nci shr 12) to (nci and 0xFFF).toInt()
        else (nci shr 8) to (nci and 0xFF).toInt()

    /** UMTS CI (28 bit) = RNC (12 bit) * 65536 + cell (16 bit). */
    fun splitUmtsCi(ci: Long): Pair<Long, Int> = (ci shr 16) to (ci and 0xFFFF).toInt()

    /**
     * Physical sector (1, 2, 3…) from a local cell id, following Polish carrier numbering:
     * 1/11/21… → sector 1, 2/12/22… → sector 2 and so on.
     */
    fun sector(localCellId: Int?): Int? {
        if (localCellId == null || localCellId < 0) return null
        if (localCellId == 0) return 1
        if (localCellId <= 9) return localCellId
        val last = localCellId % 10
        return if (last in 1..6) last else ((localCellId - 1) % 3) + 1
    }

    // ---- Timing advance → distance ----------------------------------------------------------

    const val MAX_LTE_TA = 1282

    private const val SPEED_OF_LIGHT = 299_792_458.0

    /**
     * LTE: one TA step is 16·Ts = 0.52 µs round trip ≈ 78.12 m one way. 1282 is the field maximum,
     * which modems report when they have no TA (idle mode), so it is treated as missing.
     */
    fun lteTaMeters(ta: Int?): Int? = ta?.takeIf { it in 0 until MAX_LTE_TA }?.let { Math.round(it * 78.12).toInt() }

    /** GSM: one TA step is one bit period (48/13 µs) round trip ≈ 553.5 m one way. */
    fun gsmTaMeters(ta: Int?): Int? = ta?.takeIf { it in 0..219 }?.let { Math.round(it * 553.5).toInt() }

    /** NR: Android reports the round-trip timing advance in microseconds. */
    fun nrTaMeters(micros: Int?): Int? =
        micros?.takeIf { it in 0..2000 }?.let { Math.round(it * 1e-6 * SPEED_OF_LIGHT / 2).toInt() }
}
