package pl.btmap.core.telephony

import pl.btmap.core.model.Band
import pl.btmap.core.model.Carrier
import pl.btmap.core.model.CarrierSource
import pl.btmap.core.model.Cell
import pl.btmap.core.model.CellRole
import pl.btmap.core.model.Plmn
import pl.btmap.core.model.Rat
import pl.btmap.core.radio.Bands

/**
 * Works out the component carriers of the connection.
 *
 * With the modem's carrier configuration ([PhysicalChannel], see [PhysicalChannelReader]) every
 * carrier — band, channel, PCI, bandwidth, the NR leg of EN-DC — comes straight from the modem.
 *
 * Without it, Android only gives the list of bandwidths. Each extra bandwidth is then matched to a
 * cell the phone has served on before on the same eNB (or a measured neighbour sharing the PCell's
 * PCI); a bandwidth above 20 MHz can only be NR and goes to the NR leg; anything that cannot be
 * matched stays "unknown" rather than guessed.
 */
object CarrierResolver {

    /** LTE carriers are at most 20 MHz; a wider one in the bandwidth list is the NR leg. */
    private const val MAX_LTE_BW_KHZ = 20_000

    fun resolve(
        cells: List<Cell>,
        bandwidthsKhz: List<Int>,
        plmn: Plmn?,
        dictionary: CellDictionary,
        modem: List<PhysicalChannel>? = null,
    ): List<Carrier> {
        val pcell = cells.firstOrNull { it.role == CellRole.PRIMARY } ?: return emptyList()
        if (!modem.isNullOrEmpty()) return fromModem(cells, pcell, modem, plmn, dictionary)
        return inferred(cells, pcell, bandwidthsKhz, plmn, dictionary)
    }

    // ---- From the modem ------------------------------------------------------------------------

    private fun fromModem(
        cells: List<Cell>,
        pcell: Cell,
        modem: List<PhysicalChannel>,
        plmn: Plmn?,
        dictionary: CellDictionary,
    ): List<Carrier> = modem.map { pc ->
        val cell = when {
            pc.primary -> pcell
            else -> cells.firstOrNull { it !== pcell && it.rat == pc.rat && it.identity.channel == pc.channel && it.identity.physicalId == pc.pci }
                ?: cells.firstOrNull { it.role == CellRole.SECONDARY && it.rat == pc.rat && it.identity.channel == null }
        }
        val identity = cell?.identity?.takeIf { it.cid != null }
            ?: dictionary.lookup(plmn, pc.rat, pc.channel, pc.pci)
            ?: cell?.identity
        Carrier(
            rat = pc.rat,
            isPrimary = pc.primary,
            // Without a channel from the modem, the cell the phone measures knows its band better.
            band = (if (pc.channel == null) cell?.identity?.band else null) ?: bandOf(pc) ?: cell?.identity?.band,
            channel = pc.channel ?: cell?.identity?.channel,
            physicalId = pc.pci ?: cell?.identity?.physicalId,
            bandwidthKhz = pc.bandwidthKhz,
            source = CarrierSource.REPORTED,
            cell = cell,
            identity = identity,
        )
    }

    private fun bandOf(pc: PhysicalChannel): Band? {
        val reported = pc.band?.let { intArrayOf(it) }
        val fromChannel = when (pc.rat) {
            Rat.LTE -> Bands.lte(pc.channel, reported)
            Rat.NR -> Bands.nr(pc.channel, reported)
            Rat.WCDMA -> Bands.umts(pc.channel)
            Rat.GSM -> Bands.gsm(pc.channel)
        }
        if (fromChannel != null) return fromChannel
        val mhz = pc.downlinkKhz?.let { it / 1000.0 }
        if (pc.rat == Rat.NR) return Bands.nrAt(mhz, reported)
        val number = pc.band ?: return null
        return Band(pc.rat, number, Bands.ukeLabel(mhz) ?: Bands.nameOf(pc.rat, number), mhz)
    }

    // ---- Inferred (no modem access) ------------------------------------------------------------

    private fun inferred(
        cells: List<Cell>,
        pcell: Cell,
        bandwidthsKhz: List<Int>,
        plmn: Plmn?,
        dictionary: CellDictionary,
    ): List<Carrier> {
        val rat = pcell.rat
        val secondaries = cells.filter { it.role == CellRole.SECONDARY }
        val sameRatSecondaries = secondaries.filter { it.rat == rat }
        val nrLegs = secondaries.filter { it.rat != rat }

        // In EN-DC the bandwidth list also holds the NR carrier; it is the only one above 20 MHz.
        val (lteBws, wideBws) = if (rat == Rat.LTE) bandwidthsKhz.partition { it <= MAX_LTE_BW_KHZ } else bandwidthsKhz to emptyList()
        val legBws = wideBws.toMutableList()

        val carriers = mutableListOf(
            pcell.toCarrier(primary = true, bandwidth = lteBws.firstOrNull() ?: pcell.identity.bandwidthKhz),
        )
        sameRatSecondaries.forEachIndexed { i, cell ->
            carriers += cell.toCarrier(primary = false, bandwidth = lteBws.getOrNull(i + 1) ?: cell.identity.bandwidthKhz)
        }

        val used = carriers.mapNotNull { it.channel }.toMutableSet()
        val remaining = lteBws.drop(1 + sameRatSecondaries.size)
        if (remaining.isNotEmpty()) {
            val candidates = candidates(cells, pcell, plmn, dictionary, used)
            for (bw in remaining) {
                // Only a band that can actually carry this bandwidth.
                val fitting = candidates.filter { fits(it.band, bw) }
                val pick = fitting.firstOrNull { it.bandwidthKhz == bw } ?: fitting.firstOrNull()
                if (pick == null) {
                    carriers += Carrier(rat, false, null, null, null, bw, CarrierSource.UNKNOWN, null)
                    continue
                }
                candidates.removeAll { it.channel == pick.channel }
                used += listOfNotNull(pick.channel)
                carriers += pick.copy(bandwidthKhz = bw)
            }
        }

        nrLegs.forEachIndexed { i, leg ->
            val known = leg.identity.band != null || leg.identity.channel != null
            carriers += leg.toCarrier(primary = false, bandwidth = legBws.getOrNull(i) ?: leg.identity.bandwidthKhz)
                .copy(source = if (known) CarrierSource.REPORTED else CarrierSource.UNKNOWN)
        }
        // A wide carrier without any NR cell reported: show it as 5G of unknown band.
        if (nrLegs.isEmpty()) legBws.forEach { bw ->
            carriers += Carrier(Rat.NR, false, null, null, null, bw, CarrierSource.UNKNOWN, null)
        }
        return carriers
    }

    /** Polish LTE deployments: low bands are at most 10 MHz wide, others up to 20 MHz. */
    private fun fits(band: Band?, bwKhz: Int): Boolean {
        if (band == null || band.rat != Rat.LTE) return true
        val max = when (band.number) {
            8, 20, 28, 32 -> 10_000
            else -> MAX_LTE_BW_KHZ
        }
        return bwKhz <= max
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
