package pl.btmap.core.model

/** How sure we are which cell a component carrier is. */
enum class CarrierSource {
    /** The modem reported the cell itself (PCell, or an SCell listed as secondary serving). */
    REPORTED,
    /** Matched from the bandwidth list against cells learned on the same eNB / measured now. */
    LEARNED,
    /** Band deduced from the BTMap station: the only band on the site not used by another carrier. */
    STATION,
    /** Only the bandwidth is known. */
    UNKNOWN,
}

/** One component carrier of the current connection (LTE CA, or the NR leg of EN-DC). */
data class Carrier(
    val rat: Rat,
    val isPrimary: Boolean,
    val band: Band?,
    val channel: Int?,
    val physicalId: Int?,
    val bandwidthKhz: Int?,
    val source: CarrierSource,
    /** The measured cell, when there is one (signal for the carrier). */
    val cell: Cell?,
    /** Best known identity: the measured cell's, or one learned earlier on this channel. */
    val identity: CellIdentity? = cell?.identity,
) {
    val bandwidthMhz: Int? get() = bandwidthKhz?.let { it / 1000 }

    /** "20", "1,4" — LTE has 1.4 MHz carriers, so integer MHz is not enough. */
    val bandwidthLabel: String?
        get() = bandwidthKhz?.let { if (it % 1000 == 0) "${it / 1000}" else "%.1f".format(it / 1000.0) }
}
