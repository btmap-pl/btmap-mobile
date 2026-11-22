package pl.btmap.core.telephony

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.btmap.core.model.Rat

class PhysicalChannelsTest {

    // Taken from `dumpsys telephony.registry` on the POCO X6 Pro (Plus, B8 + B3 CA).
    private val dump = """
  Phone Id=0
    mCallState=0
    mPhysicalChannelConfigs=[{mConnectionStatus=PrimaryServing,mCellBandwidthDownlinkKhz=5000,mCellBandwidthUplinkKhz=5000,mNetworkType=LTE,mFrequencyRange=LOW,mDownlinkChannelNumber=3526,mUplinkChannelNumber=21526,mContextIds=[7201, 8403],mPhysicalCellId=3,mBand=8,mDownlinkFrequency=932600,mUplinkFrequency=887600}, {mConnectionStatus=SecondaryServing,mCellBandwidthDownlinkKhz=20000,mCellBandwidthUplinkKhz=0,mNetworkType=LTE,mFrequencyRange=MID,mDownlinkChannelNumber=1355,mUplinkChannelNumber=0,mContextIds=[7201, 8403],mPhysicalCellId=364,mBand=3,mDownlinkFrequency=1820500,mUplinkFrequency=-1}]
  Phone Id=1
    mPhysicalChannelConfigs=[{mConnectionStatus=PrimaryServing,mCellBandwidthDownlinkKhz=20000,mNetworkType=LTE,mDownlinkChannelNumber=1355,mPhysicalCellId=94,mBand=3,mDownlinkFrequency=1820500}, {mConnectionStatus=SecondaryServing,mCellBandwidthDownlinkKhz=40000,mNetworkType=NR,mFrequencyRange=MID,mDownlinkChannelNumber=518910,mPhysicalCellId=508,mBand=41,mDownlinkFrequency=2594550}]
  local logs:
"""

    @Test
    fun `parses carriers per phone`() {
        val parsed = PhysicalChannelReader.parse(dump)
        val plus = parsed[0]!!
        assertEquals(2, plus.size)
        assertTrue(plus[0].primary)
        assertEquals(8, plus[0].band)
        assertEquals(3526, plus[0].channel)
        assertEquals(5000, plus[0].bandwidthKhz)
        assertEquals(364, plus[1].pci)
        assertEquals(3, plus[1].band)
    }

    @Test
    fun `nsa leg is reported as nr carrier`() {
        val leg = PhysicalChannelReader.parse(dump)[1]!![1]
        assertEquals(Rat.NR, leg.rat)
        assertEquals(41, leg.band)
        assertEquals(40000, leg.bandwidthKhz)
        assertEquals(508, leg.pci)
    }

    @Test
    fun `empty list when idle`() {
        val parsed = PhysicalChannelReader.parse("  Phone Id=0\n    mPhysicalChannelConfigs=[]\n")
        assertEquals(emptyList<PhysicalChannel>(), parsed[0])
    }

    @Test
    fun `samsung channel 0 means unknown`() {
        // Samsung Galaxy S25 (Orange RO, B20 + B1 CA): the modem leaves the channel at 0.
        val samsung = """
  Phone Id=0
    mPhysicalChannelConfigs=[{mConnectionStatus=PrimaryServing,mCellBandwidthDownlinkKhz=10000,mNetworkType=LTE,mDownlinkChannelNumber=0,mPhysicalCellId=31,mBand=0}, {mConnectionStatus=SecondaryServing,mCellBandwidthDownlinkKhz=20000,mNetworkType=LTE,mDownlinkChannelNumber=0,mPhysicalCellId=31,mBand=1}]
"""
        val carriers = PhysicalChannelReader.parse(samsung)[0]!!
        assertEquals(null, carriers[0].channel)
        assertEquals(null, carriers[0].band)
        assertEquals(10000, carriers[0].bandwidthKhz)
        assertEquals(null, carriers[1].channel)
        assertEquals(1, carriers[1].band)
    }
}
