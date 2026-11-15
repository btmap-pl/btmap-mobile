package pl.btmap.core

import android.location.LocationManager

/**
 * How hard the core works. The modem pushes changes (cell handover, signal) through callbacks in
 * every mode; the mode only sets how often we additionally ask for fresh measurements, how often
 * GPS runs, and how often the log is processed and written.
 */
enum class PowerMode(
    /** Explicit cell-info requests; between them we rely on modem callbacks. */
    val cellRefreshMs: Long,
    /** Minimum time between processed snapshots (log, notification). */
    val sampleMs: Long,
    val locationProviders: List<String>,
    val locationIntervalMs: Long,
    val locationMinDistanceM: Float,
    val flushMs: Long,
) {
    /** App on screen: everything live. */
    LIVE(
        cellRefreshMs = 2_000, sampleMs = 0,
        locationProviders = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER),
        locationIntervalMs = 1_000, locationMinDistanceM = 0f, flushMs = 15_000,
    ),

    /** Screen on, app in the background (e.g. navigation in front): GPS at a relaxed pace. */
    BACKGROUND(
        cellRefreshMs = 5_000, sampleMs = 3_000,
        locationProviders = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER),
        locationIntervalMs = 5_000, locationMinDistanceM = 15f, flushMs = 60_000,
    ),

    /**
     * Screen off: no own GPS. Network location plus the passive provider, which reuses fixes other
     * apps (navigation) request anyway. No wake lock — when the CPU sleeps, the modem still wakes it
     * on handovers, which is exactly when a log entry matters.
     */
    IDLE(
        cellRefreshMs = 15_000, sampleMs = 10_000,
        locationProviders = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER),
        locationIntervalMs = 30_000, locationMinDistanceM = 50f, flushMs = 120_000,
    ),
}
