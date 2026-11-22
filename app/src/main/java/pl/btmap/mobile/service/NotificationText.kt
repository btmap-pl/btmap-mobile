package pl.btmap.mobile.service

import pl.btmap.core.LiveState
import pl.btmap.core.location.Geo
import pl.btmap.core.model.Carrier
import pl.btmap.core.model.Rat
import pl.btmap.core.model.SimState
import pl.btmap.core.radio.Operators
import pl.btmap.mobile.settings.Settings
import pl.btmap.mobile.i18n.tr

/** What the ongoing notification shows; compared to skip identical updates. */
data class NotificationContent(val title: String, val text: String, val lines: List<String>, val header: String? = null)

/**
 * Builds the notification text: the data SIM in the title and text, every SIM as one line in the
 * expanded view. Signal is rounded to 5 dB so small jitter does not rebuild the notification.
 */
object NotificationText {

    fun from(state: LiveState, settings: Settings = Settings()): NotificationContent {
        val sims = state.snapshot.sims.filter { it.primary != null }
        if (sims.isEmpty()) return NotificationContent("BTMap", tr("Brak zasięgu", "No service"), emptyList())
        val main = sims.firstOrNull { it.isDataSim } ?: sims.first()
        val title = "${operator(main)} · ${main.mode.label} · ${bands(state, main)}"
        val text = details(state, main, settings)
        val showOthers = settings.notificationOtherSim && sims.size > 1
        val lines = if (showOthers) sims.map { "${operator(it)} ${it.mode.label} ${bands(state, it)} · ${details(state, it, settings)}" } else emptyList()
        // Header line (next to the app name): the other SIM at a glance, like "Play 4G 900".
        val header = if (!showOthers) "" else sims.filter { it !== main }.joinToString(" · ") { "${operator(it)} ${it.mode.label} ${bands(state, it)}" }
            .ifEmpty { null }
        return NotificationContent(title, text, lines, header)
    }

    private fun operator(sim: SimState) = Operators.displayName(sim.registeredPlmn, sim.networkOperatorName)

    /** "1800 + 900", "900 + 5G 3500". */
    private fun bands(state: LiveState, sim: SimState): String {
        val carriers = state.carriersOf(sim)
        if (carriers.isEmpty()) return sim.primary?.identity?.band?.label ?: "?"
        val primaryRat = carriers.first().rat
        return carriers.joinToString(" + ") { label(it, primaryRat) }
    }

    private fun label(c: Carrier, primaryRat: Rat) =
        (if (c.rat == Rat.NR && primaryRat != Rat.NR) "5G " else "") + (c.band?.label ?: "?")

    private fun details(state: LiveState, sim: SimState, settings: Settings): String {
        val primary = sim.primary ?: return ""
        val dbm = primary.signal.mainDbm?.let { (it / 5) * 5 }
        val station = state.stationOf(primary)
        val parts = mutableListOf<String>()
        if (settings.notificationSignal) dbm?.let { parts += "≈$it dBm" }
        if (!settings.notificationStation) {
            primary.identity.nodeLabel?.let { parts += (if (primary.rat == Rat.NR) "gNB " else "eNB ") + it }
        } else if (station != null) {
            parts += station.address
            state.fix?.let { f ->
                val m = Geo.distanceMeters(f.lat, f.lng, station.lat, station.lng)
                // Rounded so moving a few metres does not rebuild the notification.
                parts += if (m >= 1000) "%.1f km".format(m / 1000.0) else "${(m / 50) * 50} m"
            }
        } else {
            primary.identity.nodeLabel?.let { parts += (if (primary.rat == Rat.NR) "gNB " else "eNB ") + it }
        }
        return parts.joinToString(" · ")
    }
}
