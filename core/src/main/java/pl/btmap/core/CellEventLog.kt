package pl.btmap.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import pl.btmap.core.location.GeoFix
import pl.btmap.core.model.Carrier
import pl.btmap.core.model.NetworkSnapshot
import pl.btmap.core.model.Rat
import java.io.File

/** One carrier as it was at the time of an event. */
data class EventCarrier(
    val rat: Rat,
    val primary: Boolean,
    val band: String?,
    val channel: Int?,
    val pci: Int?,
    val node: String?,
    val bandwidthKhz: Int?,
    val dbm: Int?,
) {
    val label: String get() = (if (rat == Rat.NR) "5G " else "") + (band ?: rat.label)
}

/** Something changed on one SIM: a new serving cell, PCI, band, technology, or loss of service. */
data class CellEvent(
    val timeMillis: Long,
    val slot: Int,
    val plmn: String?,
    val mode: String,
    /** Serving carriers after the change; empty = no service. */
    val carriers: List<EventCarrier>,
    /** Human-readable changes, e.g. "PCI 364 → 3", "+ 900 MHz", "utrata zasięgu". */
    val changes: List<String>,
    val lat: Double?,
    val lng: Double?,
)

/**
 * Timeline of serving-cell changes per SIM, kept across restarts. A new event is written whenever
 * the set of serving carriers changes in any way the modem reports — cell, PCI, channel, band,
 * technology, carrier added or dropped — or the SIM loses / regains service. Signal changes alone
 * do not create events.
 */
class CellEventLog(private val file: File? = null, private val max: Int = 5_000) {
    private val _events = MutableStateFlow<List<CellEvent>>(emptyList())

    /** Newest first. */
    val events: StateFlow<List<CellEvent>> = _events.asStateFlow()

    private val lastSignature = HashMap<Int, String>()
    private val lastCarriers = HashMap<Int, List<EventCarrier>>()
    @Volatile private var dirty = false

    fun loadFromDisk() {
        val f = file ?: return
        val saved = runCatching { decode(JSONArray(f.readText())) }.getOrDefault(emptyList())
        if (saved.isEmpty()) return
        _events.update { (it + saved).sortedByDescending { e -> e.timeMillis }.take(max) }
        // Continue from the last known state so a restart does not log a fake change.
        saved.groupBy { it.slot }.forEach { (slot, list) ->
            val newest = list.maxBy { it.timeMillis }
            synchronized(lastSignature) {
                if (slot !in lastSignature) {
                    lastSignature[slot] = signature(newest.carriers)
                    lastCarriers[slot] = newest.carriers
                }
            }
        }
    }

    fun record(snapshot: NetworkSnapshot, fix: GeoFix?) {
        val now = snapshot.timestampMillis
        val added = mutableListOf<CellEvent>()
        synchronized(lastSignature) {
            for (sim in snapshot.sims) {
                val carriers = sim.carriers.map(::toEvent)
                val sig = signature(carriers)
                val previous = lastSignature[sim.slotIndex]
                if (sig == previous) continue
                val before = lastCarriers[sim.slotIndex].orEmpty()
                lastSignature[sim.slotIndex] = sig
                lastCarriers[sim.slotIndex] = carriers
                if (previous == null && carriers.isEmpty()) continue
                added += CellEvent(
                    timeMillis = now,
                    slot = sim.slotIndex,
                    plmn = (sim.registeredPlmn ?: sim.simPlmn)?.toString(),
                    mode = sim.mode.label,
                    carriers = carriers,
                    changes = describe(before, carriers, previous == null),
                    lat = fix?.lat,
                    lng = fix?.lng,
                )
            }
        }
        if (added.isEmpty()) return
        _events.update { (added + it).take(max) }
        dirty = true
    }

    fun clear() {
        _events.value = emptyList()
        dirty = true
    }

    @Synchronized
    fun flush() {
        val f = file ?: return
        if (!dirty) return
        dirty = false
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(encode(_events.value).toString())
        tmp.renameTo(f)
    }

    private fun toEvent(c: Carrier) = EventCarrier(
        rat = c.rat,
        primary = c.isPrimary,
        band = c.band?.label,
        channel = c.channel,
        pci = c.physicalId,
        node = c.identity?.nodeLabel,
        bandwidthKhz = c.bandwidthKhz,
        dbm = c.cell?.signal?.mainDbm,
    )

    private fun signature(carriers: List<EventCarrier>) =
        carriers.joinToString(";") { "${it.rat}|${it.primary}|${it.channel}|${it.pci}|${it.node}|${it.bandwidthKhz}" }

    companion object {
        internal fun describe(before: List<EventCarrier>, after: List<EventCarrier>, first: Boolean): List<String> {
            if (after.isEmpty()) return listOf("utrata zasięgu")
            if (first) return listOf("start")
            if (before.isEmpty()) return listOf("odzyskano zasięg")
            val out = mutableListOf<String>()
            val pBefore = before.firstOrNull { it.primary }
            val pAfter = after.firstOrNull { it.primary }
            if (pBefore != null && pAfter != null) {
                if (pBefore.rat != pAfter.rat) out += "${pBefore.rat.generation} → ${pAfter.rat.generation}"
                if (pBefore.channel != pAfter.channel) out += "PCell ${pBefore.label} → ${pAfter.label}"
                else if (pBefore.pci != pAfter.pci) out += "PCI ${pBefore.pci ?: "?"} → ${pAfter.pci ?: "?"}"
                if (pBefore.node != pAfter.node && pAfter.node != null) out += "komórka ${pBefore.node ?: "?"} → ${pAfter.node}"
            }
            val sBefore = before.filter { !it.primary }.associateBy { "${it.rat}|${it.channel}" }
            val sAfter = after.filter { !it.primary }.associateBy { "${it.rat}|${it.channel}" }
            (sAfter.keys - sBefore.keys).forEach { out += "+ ${sAfter.getValue(it).label}" }
            (sBefore.keys - sAfter.keys).forEach { out += "− ${sBefore.getValue(it).label}" }
            sAfter.keys.intersect(sBefore.keys).forEach { k ->
                val a = sBefore.getValue(k)
                val b = sAfter.getValue(k)
                if (a.pci != b.pci) out += "${b.label}: PCI ${a.pci ?: "?"} → ${b.pci ?: "?"}"
            }
            if (out.isEmpty()) out += "zmiana konfiguracji"
            return out
        }
    }

    private fun encode(list: List<CellEvent>) = JSONArray().apply {
        list.forEach { e ->
            put(JSONObject().apply {
                put("t", e.timeMillis); put("s", e.slot); e.plmn?.let { put("p", it) }; put("m", e.mode)
                e.lat?.let { put("lat", it) }; e.lng?.let { put("lng", it) }
                put("ch", JSONArray(e.changes))
                put("c", JSONArray().apply {
                    e.carriers.forEach { c ->
                        put(JSONObject().apply {
                            put("r", c.rat.name); put("pr", c.primary)
                            c.band?.let { put("b", it) }; c.channel?.let { put("ch", it) }; c.pci?.let { put("pci", it) }
                            c.node?.let { put("n", it) }; c.bandwidthKhz?.let { put("bw", it) }; c.dbm?.let { put("d", it) }
                        })
                    }
                })
            })
        }
    }

    private fun decode(a: JSONArray): List<CellEvent> = (0 until a.length()).mapNotNull { i ->
        runCatching {
            val o = a.getJSONObject(i)
            val cs = o.getJSONArray("c")
            val ch = o.optJSONArray("ch") ?: JSONArray()
            fun JSONObject.int(k: String) = if (has(k)) getInt(k) else null
            CellEvent(
                timeMillis = o.getLong("t"), slot = o.getInt("s"), plmn = o.optString("p").takeIf { it.isNotEmpty() },
                mode = o.optString("m"),
                carriers = (0 until cs.length()).map { j ->
                    val c = cs.getJSONObject(j)
                    EventCarrier(
                        Rat.valueOf(c.getString("r")), c.optBoolean("pr"), c.optString("b").takeIf { it.isNotEmpty() },
                        c.int("ch"), c.int("pci"), c.optString("n").takeIf { it.isNotEmpty() }, c.int("bw"), c.int("d"),
                    )
                },
                changes = (0 until ch.length()).map { ch.getString(it) },
                lat = if (o.has("lat")) o.getDouble("lat") else null,
                lng = if (o.has("lng")) o.getDouble("lng") else null,
            )
        }.getOrNull()
    }
}
