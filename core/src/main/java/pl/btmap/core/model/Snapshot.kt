package pl.btmap.core.model

import pl.btmap.core.radio.Operator

/** What the status bar would show: Android's view of the access technology. */
enum class NetworkMode(val label: String) {
    NONE("—"),
    GSM("2G"),
    UMTS("3G"),
    LTE("4G"),
    /** LTE with carrier aggregation. */
    LTE_CA("4G+"),
    /** EN-DC: LTE anchor with an NR leg (or NR announced in SIB2). */
    NR_NSA("5G NSA"),
    NR_NSA_MMWAVE("5G NSA mmW"),
    NR_SA("5G SA"),
}

/** State of one SIM / subscription at a point in time. */
data class SimState(
    val subscriptionId: Int,
    val slotIndex: Int,
    /** Carrier name as configured on the SIM, e.g. "Plus". */
    val simName: String?,
    /** Home network of the SIM. */
    val simPlmn: Plmn?,
    /** Network currently registered to (differs from [simPlmn] when roaming / national roaming). */
    val registeredPlmn: Plmn?,
    val operator: Operator?,
    val networkOperatorName: String?,
    val mode: NetworkMode,
    val isRoaming: Boolean,
    /** Default data SIM. */
    val isDataSim: Boolean,
    /** Serving cells first (primary, then secondaries), then neighbours by signal. */
    val cells: List<Cell>,
    /** Per-carrier bandwidths reported by ServiceState (PCell + SCells), kHz. */
    val carrierBandwidthsKhz: List<Int>,
    /** Component carriers of the connection, primary first. Empty when idle / no service. */
    val carriers: List<Carrier>,
    /** [carriers] come from the modem's own carrier configuration, not from inference. */
    val carriersFromModem: Boolean = false,
    /** The LTE anchor offers EN-DC (status bar shows 5G) but no NR leg is active right now. */
    val nrAvailable: Boolean = false,
    /** Wall-clock time of the last update. */
    val updatedAtMillis: Long,
    /** Set when the modem could not be read, e.g. a missing permission. */
    val error: String? = null,
) {
    val primary: Cell? get() = cells.firstOrNull { it.role == CellRole.PRIMARY }
    val secondaries: List<Cell> get() = cells.filter { it.role == CellRole.SECONDARY }
    val neighbours: List<Cell> get() = cells.filter { it.role == CellRole.NEIGHBOUR }

    /** Aggregated bandwidth in MHz, e.g. 20 + 20 + 10 + 100. */
    val totalBandwidthKhz: Int get() = carriers.sumOf { it.bandwidthKhz ?: 0 }
}

data class NetworkSnapshot(
    val sims: List<SimState>,
    val timestampMillis: Long,
) {
    companion object {
        val EMPTY = NetworkSnapshot(emptyList(), 0)
    }
}
