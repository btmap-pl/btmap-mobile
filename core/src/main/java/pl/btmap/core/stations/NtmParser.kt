package pl.btmap.core.stations

import pl.btmap.core.model.Rat
import pl.btmap.core.radio.CellMath

/** One cell row of a cell database, normalised. */
data class DbCell(
    val rat: Rat,
    val mcc: Int,
    val mnc: Int,
    /** eNB / gNB / RNC; null for GSM. */
    val node: Long?,
    /** Local cell id inside the node (LTE/NR), CI for GSM. */
    val cid: Long?,
    val areaCode: Int?,
    val pci: Int?,
    val channel: Int?,
    val lat: Double,
    val lng: Double,
    /** Station code from the description, e.g. "BT31000". */
    val stationId: String?,
    /** Description without the code and antenna details, e.g. "Cigacice - gm. Sulechów, ul. Kolejowa 4". */
    val name: String,
)

/**
 * Parser for NetMonster's `.ntm` cell database (what BTSearch exports):
 * `RAT;MCC;MNC;CID;LAC/TAC;NODE;PCI;LAT;LON;DESCRIPTION;CHANNEL`, unknown values as 2147483647.
 *
 * LTE rows carry the local cell id in CID and the eNB in NODE; some exporters put the full ECI in
 * CID instead, which is split here. NR rows carry the full NCI in CID and usually no NODE.
 */
object NtmParser {

    private const val UNKNOWN = 2147483647L

    fun parse(line: String): DbCell? {
        if (line.isBlank() || line.startsWith("#")) return null
        val f = line.split(';')
        if (f.size < 10) return null
        val rat = when (f[0].trim().uppercase()) {
            "2G", "GSM" -> Rat.GSM
            "3G", "UMTS", "WCDMA" -> Rat.WCDMA
            "4G", "LTE" -> Rat.LTE
            "5G", "NR" -> Rat.NR
            else -> return null
        }
        val mcc = f[1].trim().toIntOrNull() ?: return null
        val mnc = f[2].trim().toIntOrNull() ?: return null
        val rawCid = num(f[3])
        val area = num(f[4])?.toInt()
        val rawNode = num(f[5])?.takeIf { it > 0 }
        val pci = num(f[6])?.toInt()
        val lat = f[7].trim().toDoubleOrNull() ?: return null
        val lng = f[8].trim().toDoubleOrNull() ?: return null
        if (lat == 0.0 && lng == 0.0) return null
        // The description itself may contain ';' — the channel is always the last field.
        val description = f.subList(9, if (f.size > 10) f.size - 1 else f.size).joinToString(";")
        val channel = if (f.size > 10) num(f.last())?.toInt() else null

        val (node, cid) = when (rat) {
            Rat.LTE -> when {
                rawNode != null -> rawNode to (rawCid?.let { if (it > 255) it and 0xFF else it })
                rawCid != null && rawCid > 255 -> CellMath.splitEci(rawCid).let { it.first to it.second.toLong() }
                else -> null to rawCid
            }
            Rat.NR -> when {
                rawCid != null && rawCid > 4095 -> CellMath.splitNci(rawCid).let { (rawNode ?: it.first) to it.second.toLong() }
                else -> rawNode to rawCid
            }
            Rat.WCDMA -> when {
                rawNode != null -> rawNode to rawCid?.let { it and 0xFFFF }
                rawCid != null && rawCid > 65535 -> CellMath.splitUmtsCi(rawCid).let { it.first to it.second.toLong() }
                else -> null to rawCid
            }
            Rat.GSM -> null to rawCid
        }
        val (stationId, name) = splitDescription(description)
        return DbCell(rat, mcc, mnc, node, cid, area, pci, channel, lat, lng, stationId, name)
    }

    private fun num(s: String): Long? = s.trim().toLongOrNull()?.takeIf { it != UNKNOWN && it >= 0 }

    private val CODE = Regex("""^\s*\[([^\]]{1,24})]\s*""")

    /**
     * "[BT31000] Cigacice - gm. Sulechów, ul. Kolejowa 4 - wieża kratowa Cellnex / Plus | A3: 80°"
     * → ("BT31000", "Cigacice - gm. Sulechów, ul. Kolejowa 4 - wieża kratowa Cellnex / Plus")
     */
    fun splitDescription(description: String): Pair<String?, String> {
        val code = CODE.find(description)
        val rest = if (code != null) description.substring(code.range.last + 1) else description
        val name = rest.substringBefore(" | ").trim()
        return code?.groupValues?.get(1)?.trim() to name.ifEmpty { rest.trim() }
    }
}
