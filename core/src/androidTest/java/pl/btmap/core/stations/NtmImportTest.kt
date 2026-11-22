package pl.btmap.core.stations

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import pl.btmap.core.api.NodeKey
import pl.btmap.core.model.Plmn
import pl.btmap.core.model.Rat

/** Importing a NetMonster file into the on-device database and finding stations from it. */
@RunWith(AndroidJUnit4::class)
class NtmImportTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var repo: StationRepository

    // Shaped like a real NetMonster export: BOM, CRLF, "null" for unknown values, rows without
    // coordinates, emoji in descriptions, a foreign country (Romania, MCC 226).
    private val file = "﻿" + listOf(
        "4G;226;10;2;20004;96001;31;47.4960;23.7902;MM, Satu Test ,Strada 1;1600",
        "4G;226;10;10;20004;96001;31;47.4960;23.7902;MM, Satu Test ,Strada 1;6350",
        "4G;226;5;82;46202;211001;478;47.4959;23.7917;✨ MM, Alt Sat;6425",
        "5G;226;30;947003622;62;2001001;278;47.4259;24.1021;5G SA 700Mhz;152190",
        "2G;226;1;30992;42160;0;55;;;-;982",
        "4G;226;10;4;20004;95001;null;;;Fara loc;1600",
    ).joinToString("\r\n") + "\r\n"

    @Before
    fun setUp() {
        repo = StationRepository(context)
        repo.clear(StationSource.CUSTOM)
    }

    @After
    fun tearDown() = repo.clear(StationSource.CUSTOM)

    @Test
    fun importsAndFindsForeignStations() = runBlocking {
        val result = repo.importNtm(file.byteInputStream(), "test.ntm").getOrThrow()
        assertEquals(4, result.info.cells)
        assertEquals(3, result.info.sites)
        assertEquals(2, result.skipped)
        assertEquals("test.ntm", repo.info(StationSource.CUSTOM)?.detail)

        // Found even though the selected database is BTSearch (Poland only).
        repo.setSource(StationSource.BTSEARCH)
        val orange = repo.find(NodeKey(Plmn("226", "10"), Rat.LTE, 96001))
        assertEquals("MM, Satu Test ,Strada 1", orange?.address)
        assertEquals(47.4960, orange!!.lat, 1e-6)
        assertNotNull(repo.find(NodeKey(Plmn("226", "05"), Rat.LTE, 211001)))
        assertNotNull(repo.find(NodeKey(Plmn("226", "30"), Rat.NR, 2001001)))
        assertNull(repo.find(NodeKey(Plmn("226", "10"), Rat.LTE, 95001)))

        // Neighbours by channel + PCI near the phone.
        val neighbour = repo.findNeighbour(Plmn("226", "10"), Rat.LTE, 6350, 31, 47.49, 23.79)
        assertEquals(96001L, neighbour?.nodeId)
        assertTrue(repo.sitesIn(47.0, 23.0, 48.0, 25.0).isNotEmpty())

        // Deleting the own database leaves nothing behind.
        repo.clear(StationSource.CUSTOM)
        assertNull(repo.find(NodeKey(Plmn("226", "10"), Rat.LTE, 96001)))
    }

    @Test
    fun fileWithoutLocationsIsRejected() = runBlocking {
        val result = repo.importNtm("2G;226;1;30992;42160;0;55;;;-;982\r\n".byteInputStream(), "empty.ntm")
        assertTrue(result.isFailure)
        assertNull(repo.info(StationSource.CUSTOM))
    }
}
