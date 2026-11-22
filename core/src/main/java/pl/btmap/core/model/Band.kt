package pl.btmap.core.model

/**
 * Frequency band of a channel.
 *
 * @property number 3GPP band number (LTE band 3, NR n78, UMTS band I = 1, GSM uses 900/1800).
 * @property label UKE-style label used on btmap.pl, e.g. "1800", "3500".
 * @property downlinkMhz downlink centre frequency of the channel.
 */
data class Band(
    val rat: Rat,
    val number: Int,
    val label: String,
    val downlinkMhz: Double?,
    /** Uplink centre frequency; null for downlink-only bands or when unknown. */
    val uplinkMhz: Double? = null,
) {
    /** "B3", "n78", "B1" (UMTS), "GSM900". */
    val shortName: String
        get() = when (rat) {
            Rat.LTE -> "B$number"
            Rat.NR -> "n$number"
            Rat.WCDMA -> "B$number"
            Rat.GSM -> "GSM$label"
        }
}
