package pl.btmap.core.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Position fix in the shape the rest of the core needs. */
data class GeoFix(
    val lat: Double,
    val lng: Double,
    val accuracyMeters: Float?,
    val altitudeMeters: Double?,
    val speedKmh: Float?,
    val bearing: Float?,
    val timeMillis: Long,
    val provider: String?,
)

/**
 * GPS + network location without Google Play services (works on de-Googled ROMs too).
 * Network fixes are only used until the first GPS fix arrives, or when GPS goes stale.
 */
class LocationTracker(context: Context) {
    private val lm = context.applicationContext.getSystemService(LocationManager::class.java)

    /**
     * @param wanted providers to use, best first; disabled ones are skipped.
     * @param minIntervalMs / [minDistanceM] pace of updates (larger = less battery).
     */
    @SuppressLint("MissingPermission")
    fun fixes(
        wanted: List<String> = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER),
        minIntervalMs: Long = 1_000,
        minDistanceM: Float = 0f,
    ): Flow<GeoFix> = callbackFlow {
        var lastGpsAt = 0L
        val listener = LocationListener { loc ->
            val isGps = loc.provider == LocationManager.GPS_PROVIDER
            if (isGps) lastGpsAt = loc.time
            else if (loc.time - lastGpsAt < STALE_GPS_MS) return@LocationListener
            trySend(loc.toFix())
        }
        val providers = wanted.filter {
            it == LocationManager.PASSIVE_PROVIDER || runCatching { lm.isProviderEnabled(it) }.getOrDefault(false)
        }
        providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
            ?.let { trySend(it.toFix()) }
        try {
            providers.forEach { lm.requestLocationUpdates(it, minIntervalMs, minDistanceM, listener, Looper.getMainLooper()) }
        } catch (e: SecurityException) {
            close(e)
        }
        awaitClose { lm.removeUpdates(listener) }
    }.conflate()

    private fun Location.toFix() = GeoFix(
        lat = latitude,
        lng = longitude,
        accuracyMeters = if (hasAccuracy()) accuracy else null,
        altitudeMeters = if (hasAltitude()) altitude else null,
        speedKmh = if (hasSpeed()) speed * 3.6f else null,
        bearing = if (hasBearing()) bearing else null,
        timeMillis = time,
        provider = provider,
    )

    private companion object {
        const val STALE_GPS_MS = 10_000L
    }
}

object Geo {
    private const val EARTH_RADIUS_M = 6_371_008.8

    fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Int {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
        return (EARTH_RADIUS_M * 2 * atan2(sqrt(a), sqrt(1 - a))).roundToInt()
    }

    /** Initial bearing from point 1 to point 2, degrees 0..360. */
    fun bearing(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lng2 - lng1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }
}
