package pl.btmap.core.model

/** Radio access technology of a cell. */
enum class Rat(val label: String, val generation: String) {
    GSM("GSM", "2G"),
    WCDMA("UMTS", "3G"),
    LTE("LTE", "4G"),
    NR("NR", "5G"),
}

/** Mobile network (MCC + MNC), e.g. 260 06. */
data class Plmn(val mcc: String, val mnc: String) {
    val numeric: String get() = mcc + mnc
    override fun toString(): String = "$mcc $mnc"

    companion object {
        fun of(mcc: String?, mnc: String?): Plmn? =
            if (mcc.isNullOrBlank() || mnc.isNullOrBlank()) null else Plmn(mcc, mnc)

        /** Parses "26006" or "260-06". */
        fun parse(numeric: String?): Plmn? {
            val digits = numeric?.filter(Char::isDigit) ?: return null
            if (digits.length !in 5..6) return null
            return Plmn(digits.take(3), digits.drop(3))
        }
    }
}

/** How the modem uses a cell. */
enum class CellRole {
    /** Cell the modem is camped on / primary serving cell (PCell, or the anchor in NSA). */
    PRIMARY,
    /** Carrier-aggregation secondary or the NR leg of an EN-DC connection. */
    SECONDARY,
    /** Measured neighbour. */
    NEIGHBOUR,
}

/**
 * Identity of a cell. Values the modem did not report are null; neighbours usually carry only
 * the physical identifiers (PCI/PSC/BSIC + channel).
 */
data class CellIdentity(
    val rat: Rat,
    val plmn: Plmn?,
    /** Full cell identity: CI (GSM/UMTS), ECI (LTE, 28 bit) or NCI (NR, 36 bit). */
    val cid: Long?,
    /** eNodeB / gNodeB / RNC; null for GSM. */
    val nodeId: Long?,
    /** Local cell id inside the node (LTE: low 8 bits, NR: 8 or 12 bits, UMTS: low 16 bits). */
    val localCellId: Int?,
    /** TAC (LTE/NR) or LAC (GSM/UMTS). */
    val areaCode: Int?,
    /** PCI (LTE/NR), PSC (UMTS) or BSIC (GSM). */
    val physicalId: Int?,
    /** EARFCN / NR-ARFCN / UARFCN / ARFCN. */
    val channel: Int?,
    val band: Band?,
    /** Channel bandwidth in kHz when the modem reports it (LTE PCell). */
    val bandwidthKhz: Int? = null,
    /** Operator name from the network (alpha long), when known. */
    val operatorAlpha: String? = null,
    /** CI / node / TAC were filled in from [pl.btmap.core.telephony.CellDictionary], not reported. */
    val learned: Boolean = false,
) {
    /** "141981:22" for LTE/NR, "CI" otherwise. */
    val nodeLabel: String?
        get() = when {
            nodeId != null && localCellId != null -> "$nodeId:$localCellId"
            cid != null -> cid.toString()
            else -> null
        }
}

/**
 * Signal of a cell. All values are in their natural unit (dBm / dB) and null when unavailable.
 * NR NSA carries the SS-* values in [ssRsrp]/[ssRsrq]/[ssSinr]; LTE fields stay for the anchor.
 */
data class CellSignal(
    val rssi: Int? = null,
    val rsrp: Int? = null,
    val rsrq: Int? = null,
    val snr: Int? = null,
    val cqi: Int? = null,
    val ssRsrp: Int? = null,
    val ssRsrq: Int? = null,
    val ssSinr: Int? = null,
    val csiRsrp: Int? = null,
    val csiRsrq: Int? = null,
    val csiSinr: Int? = null,
    /** UMTS RSCP / Ec/No. */
    val rscp: Int? = null,
    val ecno: Int? = null,
    /** GSM bit error rate (0..7). */
    val ber: Int? = null,
    /** Raw timing advance as reported (LTE TA units, GSM TA units). */
    val timingAdvance: Int? = null,
    /** NR timing advance in microseconds (Android 14+). */
    val timingAdvanceMicros: Int? = null,
    /** Android's 0..4 signal level. */
    val level: Int? = null,
) {
    /** The "main" power figure used to sort and colour cells. */
    val mainDbm: Int?
        get() = ssRsrp ?: rsrp ?: rscp ?: rssi
}

/** A cell as seen in one measurement. */
data class Cell(
    val identity: CellIdentity,
    val signal: CellSignal,
    val role: CellRole,
    /** Distance to the antenna estimated from the timing advance, in metres. */
    val taDistanceMeters: Int?,
    /** Modem timestamp of the measurement (ns since boot), useful to drop stale cells. */
    val timestampNanos: Long,
) {
    val rat: Rat get() = identity.rat
    val isServing: Boolean get() = role != CellRole.NEIGHBOUR

    /** Key identifying the same physical cell across measurements. */
    val key: String
        get() = with(identity) {
            if (cid != null && plmn != null) "${rat.name}:${plmn.numeric}:$cid"
            else "${rat.name}:${plmn?.numeric ?: "?"}:${channel ?: "?"}:${physicalId ?: "?"}"
        }
}
