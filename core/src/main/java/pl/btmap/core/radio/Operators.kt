package pl.btmap.core.radio

import pl.btmap.core.model.Plmn

/** Operator known to BTMap; [btmapName] matches the operator names on btmap.pl. */
data class Operator(
    val plmn: Plmn,
    val btmapName: String,
    val displayName: String,
    /** Brand colour, ARGB. */
    val color: Long,
)

object Operators {

    private val KNOWN = listOf(
        Operator(Plmn("260", "01"), "Plus", "Plus", 0xFF13FB76),
        Operator(Plmn("260", "02"), "T-Mobile", "T-Mobile", 0xFFE20074),
        Operator(Plmn("260", "03"), "Orange", "Orange", 0xFFFF7900),
        Operator(Plmn("260", "06"), "Play", "Play", 0xFF6C43BF),
        Operator(Plmn("260", "11"), "Plus", "Nordisk / Plus", 0xFF13FB76),
        Operator(Plmn("260", "34"), "T-Mobile", "NetWorkS!", 0xFFE20074),
        Operator(Plmn("260", "98"), "Play", "Play (testowa)", 0xFF6C43BF),
    ).associateBy { it.plmn.numeric }

    fun byPlmn(plmn: Plmn?): Operator? = plmn?.let { KNOWN[it.numeric] }

    fun isPolish(plmn: Plmn?): Boolean = plmn?.mcc == "260"

    fun displayName(plmn: Plmn?, alpha: String?): String =
        byPlmn(plmn)?.displayName ?: alpha?.takeIf { it.isNotBlank() } ?: plmn?.toString() ?: "?"
}
