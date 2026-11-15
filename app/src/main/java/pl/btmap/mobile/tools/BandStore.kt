package pl.btmap.mobile.tools

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import pl.btmap.core.BtmapCore
import pl.btmap.core.model.Rat
import java.util.UUID

/** A named band configuration the user saved, e.g. "Tylko 1800 + n78". */
data class BandPreset(val id: String, val name: String, val selection: BandSelection)

/**
 * Bands the modem has actually reported on this phone, and the user's saved band configurations.
 *
 * The phone does not expose its band capability list to apps (MediaTek's AT channel is closed and
 * the system-selection query is unsupported), so the bands come from what the modem reports while
 * measuring: serving cells, neighbours and the carriers of the connection.
 */
class BandStore(context: Context, core: BtmapCore, scope: CoroutineScope) {
    private val prefs = context.applicationContext.getSharedPreferences("bands", Context.MODE_PRIVATE)

    private val _observed = MutableStateFlow(readObserved())
    /** Bands seen from the modem: LTE band numbers to NR band numbers. */
    val observed: StateFlow<BandSelection> = _observed.asStateFlow()

    private val _presets = MutableStateFlow(readPresets())
    val presets: StateFlow<List<BandPreset>> = _presets.asStateFlow()

    private val _active = MutableStateFlow(readActive())
    /** Selection last applied per SIM slot (missing = all bands). */
    val active: StateFlow<Map<Int, BandSelection>> = _active.asStateFlow()

    init {
        scope.launch {
            core.samples.collect { state ->
                val lte = mutableSetOf<Int>()
                val nr = mutableSetOf<Int>()
                state.snapshot.sims.forEach { sim ->
                    sim.cells.mapNotNull { it.identity.band }.forEach { b -> if (b.rat == Rat.LTE) lte += b.number else if (b.rat == Rat.NR) nr += b.number }
                    sim.carriers.mapNotNull { it.band }.forEach { b -> if (b.rat == Rat.LTE) lte += b.number else if (b.rat == Rat.NR) nr += b.number }
                }
                val current = _observed.value
                if (!current.lte.containsAll(lte) || !current.nr.containsAll(nr)) {
                    _observed.value = BandSelection(current.lte + lte, current.nr + nr)
                    prefs.edit().putString("observed", encode(_observed.value).toString()).apply()
                }
            }
        }
    }

    fun savePreset(name: String, selection: BandSelection): BandPreset {
        val preset = BandPreset(UUID.randomUUID().toString(), name.trim().ifEmpty { "Konfiguracja" }, selection)
        _presets.update { it + preset }
        writePresets()
        return preset
    }

    fun deletePreset(id: String) {
        _presets.update { list -> list.filterNot { it.id == id } }
        writePresets()
    }

    fun setActive(slot: Int, selection: BandSelection) {
        _active.update { if (selection.isUnrestricted) it - slot else it + (slot to selection) }
        val o = JSONObject()
        _active.value.forEach { (s, sel) -> o.put(s.toString(), encode(sel)) }
        prefs.edit().putString("active", o.toString()).apply()
    }

    private fun writePresets() {
        val a = JSONArray()
        _presets.value.forEach { p -> a.put(JSONObject().put("id", p.id).put("name", p.name).put("sel", encode(p.selection))) }
        prefs.edit().putString("presets", a.toString()).apply()
    }

    private fun readObserved(): BandSelection =
        prefs.getString("observed", null)?.let { runCatching { decode(JSONObject(it)) }.getOrNull() } ?: BandSelection()

    private fun readPresets(): List<BandPreset> = prefs.getString("presets", null)?.let { s ->
        runCatching {
            val a = JSONArray(s)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                BandPreset(o.getString("id"), o.getString("name"), decode(o.getJSONObject("sel")))
            }
        }.getOrNull()
    } ?: emptyList()

    private fun readActive(): Map<Int, BandSelection> = prefs.getString("active", null)?.let { s ->
        runCatching {
            val o = JSONObject(s)
            o.keys().asSequence().associate { it.toInt() to decode(o.getJSONObject(it)) }
        }.getOrNull()
    } ?: emptyMap()

    private fun encode(s: BandSelection) = JSONObject().put("lte", JSONArray(s.lte.sorted())).put("nr", JSONArray(s.nr.sorted()))

    private fun decode(o: JSONObject): BandSelection {
        fun ints(a: JSONArray?) = if (a == null) emptySet() else (0 until a.length()).map { a.getInt(it) }.toSet()
        return BandSelection(ints(o.optJSONArray("lte")), ints(o.optJSONArray("nr")))
    }
}
