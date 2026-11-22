package pl.btmap.core.track

import org.junit.Assert.assertEquals
import org.junit.Test
import pl.btmap.core.model.Rat

class TrackRecorderTest {
    private fun p(t: Long, lat: Double) = TrackPoint(t, lat, 16.0, Rat.LTE, -90, "1800")

    @Test
    fun `a long pause starts a new trip`() {
        val minute = 60_000L
        val points = listOf(p(0, 52.0), p(minute, 52.001), p(2 * minute, 52.002), p(30 * minute, 52.1), p(31 * minute, 52.101), p(90 * minute, 53.0))
        val trips = TrackRecorder.split(points)
        assertEquals(2, trips.size) // the lone last point is not a trip
        assertEquals(3, trips[0].size)
        assertEquals(2, trips[1].size)
    }

    @Test
    fun `trip length adds up the steps`() {
        val trip = listOf(p(0, 52.0), p(1, 52.001), p(2, 52.002))
        assertEquals(222.0, TrackRecorder.lengthMeters(trip), 3.0)
    }
}
