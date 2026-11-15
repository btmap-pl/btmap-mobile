package pl.btmap.core.telephony

import org.json.JSONArray
import org.json.JSONObject
import pl.btmap.core.model.Cell
import pl.btmap.core.model.CellIdentity
import pl.btmap.core.model.Plmn
import pl.btmap.core.model.Rat
import pl.btmap.core.radio.Bands
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Learns full identities of cells the phone has been served by, keyed by what neighbour
 * measurements carry: PLMN + RAT + channel + physical id. Neighbours and carrier-aggregation
 * secondaries are reported without CI/TAC/bandwidth; once a cell has been a primary cell, this
 * fills those in.
 */
class CellDictionary(private val file: File? = null) {

    private data class Key(val plmn: String, val rat: Rat, val channel: Int, val physicalId: Int)

    private val byPhysical = ConcurrentHashMap<Key, CellIdentity>()
    @Volatile private var dirty = false

    init {
        file?.let { runCatching { load(it) } }
    }

    /**
     * @param trustedBandwidth the cell's bandwidth came from ServiceState. Many modems (MIUI/MediaTek)
     * report a bogus 1.4 MHz in CellIdentity, so an untrusted value never replaces a known one.
     */
    fun learn(plmn: Plmn?, cell: Cell, trustedBandwidth: Boolean = false) {
        val id = cell.identity
        if (id.learned || id.cid == null) return
        val p = (id.plmn ?: plmn)?.numeric ?: return
        val key = Key(p, id.rat, id.channel ?: return, id.physicalId ?: return)
        val previous = byPhysical[key]
        // Keep a known bandwidth when a later sample (e.g. SCell, idle) lacks it.
        val bandwidth = if (trustedBandwidth) id.bandwidthKhz ?: previous?.bandwidthKhz
        else previous?.bandwidthKhz ?: id.bandwidthKhz
        val merged = id.copy(bandwidthKhz = bandwidth, operatorAlpha = null)
        if (merged != previous) {
            byPhysical[key] = merged
            dirty = true
        }
    }

    fun lookup(plmn: Plmn?, rat: Rat, channel: Int?, physicalId: Int?): CellIdentity? {
        val p = plmn?.numeric ?: return null
        return byPhysical[Key(p, rat, channel ?: return null, physicalId ?: return null)]
    }

    /** Every learned carrier of one eNB/gNB. */
    fun carriersOf(plmn: Plmn?, rat: Rat, nodeId: Long?): List<CellIdentity> {
        val p = plmn?.numeric ?: return emptyList()
        if (nodeId == null) return emptyList()
        return byPhysical.entries.filter { it.key.plmn == p && it.key.rat == rat && it.value.nodeId == nodeId }
            .map { it.value }
    }

    /** Fills a neighbour's missing identity from what was learned. */
    fun enrich(plmn: Plmn?, cell: Cell): Cell {
        val id = cell.identity
        if (id.cid != null) return cell
        val known = lookup(id.plmn ?: plmn, id.rat, id.channel, id.physicalId) ?: return cell
        return cell.copy(
            identity = id.copy(
                plmn = id.plmn ?: known.plmn,
                cid = known.cid,
                nodeId = known.nodeId,
                localCellId = known.localCellId,
                areaCode = id.areaCode ?: known.areaCode,
                bandwidthKhz = id.bandwidthKhz ?: known.bandwidthKhz,
                learned = true,
            ),
        )
    }

    val size: Int get() = byPhysical.size

    /** Writes learned cells to [file] if anything changed. Call from a background thread. */
    @Synchronized
    fun flush() {
        val target = file ?: return
        if (!dirty) return
        dirty = false
        val array = JSONArray()
        byPhysical.forEach { (k, v) ->
            array.put(JSONObject().apply {
                put("p", k.plmn); put("r", k.rat.name); put("ch", k.channel); put("pci", k.physicalId)
                v.cid?.let { put("cid", it) }
                v.areaCode?.let { put("ac", it) }
                v.bandwidthKhz?.let { put("bw", it) }
            })
        }
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(array.toString())
        tmp.renameTo(target)
    }

    private fun load(source: File) {
        if (!source.exists()) return
        val array = JSONArray(source.readText())
        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            val rat = Rat.valueOf(o.getString("r"))
            val plmn = Plmn.parse(o.getString("p")) ?: continue
            val channel = o.getInt("ch")
            val cid = if (o.has("cid")) o.getLong("cid") else null
            val split = cid?.let {
                when (rat) {
                    Rat.LTE -> pl.btmap.core.radio.CellMath.splitEci(it)
                    Rat.NR -> pl.btmap.core.radio.CellMath.splitNci(it)
                    Rat.WCDMA -> pl.btmap.core.radio.CellMath.splitUmtsCi(it)
                    Rat.GSM -> null
                }
            }
            val band = when (rat) {
                Rat.LTE -> Bands.lte(channel)
                Rat.NR -> Bands.nr(channel)
                Rat.WCDMA -> Bands.umts(channel)
                Rat.GSM -> Bands.gsm(channel)
            }
            val key = Key(plmn.numeric, rat, channel, o.getInt("pci"))
            byPhysical[key] = CellIdentity(
                rat = rat, plmn = plmn, cid = cid, nodeId = split?.first, localCellId = split?.second,
                areaCode = if (o.has("ac")) o.getInt("ac") else null, physicalId = key.physicalId,
                channel = channel, band = band, bandwidthKhz = if (o.has("bw")) o.getInt("bw") else null,
            )
        }
    }
}
