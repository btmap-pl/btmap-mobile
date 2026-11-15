package pl.btmap.core.telephony

import pl.btmap.core.model.Carrier
import pl.btmap.core.model.CarrierSource
import pl.btmap.core.model.Cell
import pl.btmap.core.model.CellRole
import pl.btmap.core.model.Plmn

/**
 * Works out the component carriers of the connection.
 *
 * Android only gives regular apps the list of carrier bandwidths (ServiceState) — the precise
 * per-carrier config is a privileged API. Many modems (MIUI/MediaTek included) do not list SCells
 * in CellInfo either, so each extra bandwidth is matched to a cell: first one learned on the same
 * eNB, then a measured neighbour on another channel sharing the PCell's PCI. If nothing fits, the
 * carrier is still shown with its bandwidth and an unknown band.
 */
object CarrierResolver {

    fun resolve(cells: List<Cell>, bandwidthsKhz: List<Int>, plmn: Plmn?, dictionary: CellDictionary): List<Carrier> {
        val pcell = cells.firstOrNull { it.role == CellRole.PRIMARY } ?: return emptyList()
        val rat = pcell.rat
        val secondaries = cells.filter { it.role == CellRole.SECONDARY }
        val sameRatSecondaries = secondaries.filter { it.rat == rat }
        val otherRatLegs = secondaries.filter { it.rat != rat }

        val carriers = mutableListOf(
            // ServiceState lists the configured carriers; CellIdentity bandwidth is often stale (MIUI reports 1.4 MHz).
            pcell.toCarrier(primary = true, bandwidth = bandwidthsKhz.firstOrNull() ?: pcell.identity.bandwidthKhz),
        )
        sameRatSecondaries.forEachIndexed { i, cell ->
            carriers += cell.toCarrier(primary = false, bandwidth = bandwidthsKhz.getOrNull(i + 1) ?: cell.identity.bandwidthKhz)
        }

        val used = carriers.mapNotNullTo(mutableSetOf()) { it.channel }
        val remaining = bandwidthsKhz.drop(1 + sameRatSecondaries.size)
        if (remaining.isNotEmpty()) {
            val candidates = candidates(cells, pcell, plmn, dictionary, used)
            for (bw in remaining) {
                val pick = candidates.firstOrNull { it.bandwidthKhz == bw } ?: candidates.firstOrNull()
                if (pick == null) {
                    carriers += Carrier(rat, false, null, null, null, bw, CarrierSource.UNKNOWN, null)
                    continue
                }
                // One channel can carry only one component carrier.
                candidates.removeAll { it.channel == pick.channel }
                carriers += pick.copy(bandwidthKhz = bw)
            }
        }

        otherRatLegs.forEach { leg ->
            val known = leg.identity.band != null || leg.identity.channel != null
            carriers += leg.toCarrier(primary = false, bandwidth = leg.identity.bandwidthKhz)
                .copy(source = if (known) CarrierSource.REPORTED else CarrierSource.UNKNOWN)
        }
        return carriers
    }

    private fun candidates(
        cells: List<Cell>,
        pcell: Cell,
        plmn: Plmn?,
        dictionary: CellDictionary,
        used: Set<Int>,
    ): MutableList<Carrier> {
        val rat = pcell.rat
        val node = pcell.identity.nodeId
        val neighbours = cells.filter {
            it.role == CellRole.NEIGHBOUR && it.rat == rat && it.identity.channel != null && it.identity.channel !in used
        }
        val sameNode = neighbours.filter { node != null && it.identity.nodeId == node }
        val samePci = neighbours.filter { it !in sameNode && it.identity.physicalId == pcell.identity.physicalId }
        val measuredChannels = (sameNode + samePci).mapNotNull { it.identity.channel }.toSet()
        val learnedOnly = dictionary.carriersOf(plmn, rat, node)
            .filter { it.channel != null && it.channel !in used && it.channel !in measuredChannels }
            .distinctBy { it.channel }

        return (
            sameNode.map { it.toCarrier(false, it.identity.bandwidthKhz, CarrierSource.LEARNED) } +
                learnedOnly.map {
                    Carrier(rat, false, it.band, it.channel, it.physicalId, it.bandwidthKhz, CarrierSource.LEARNED, null, it)
                } +
                samePci.map { it.toCarrier(false, it.identity.bandwidthKhz, CarrierSource.LEARNED) }
            ).distinctBy { it.channel }.toMutableList()
    }

    private fun Cell.toCarrier(primary: Boolean, bandwidth: Int?, source: CarrierSource = CarrierSource.REPORTED) =
        Carrier(rat, primary, identity.band, identity.channel, identity.physicalId, bandwidth, source, this)
}
