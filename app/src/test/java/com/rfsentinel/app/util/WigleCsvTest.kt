package com.rfsentinel.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The WiGLE upload file is written exactly as WiGLE's own app writes WigleWifi-1.6. */
class WigleCsvTest {

    @Test
    fun headerIsWigleWifi16() {
        val h = WigleCsv.header("RFSentinel-2.46.0", "Pixel 6", "16", "oriole", "BP2A.250605", "oriole", "google")
        val lines = h.split("\n")
        assertEquals("WigleWifi-1.6,appRelease=RFSentinel-2.46.0,model=Pixel 6,release=16,device=oriole," +
            "display=BP2A.250605,board=oriole,brand=google,star=Sol,body=3,subBody=0", lines[0])
        assertEquals("MAC,SSID,AuthMode,FirstSeen,Channel,Frequency,RSSI,CurrentLatitude,CurrentLongitude," +
            "AltitudeMeters,AccuracyMeters,RCOIs,MfgrId,Type", lines[1])
        assertEquals("", lines[2]) // ends with a line break
    }

    @Test
    fun wifiRow() {
        val r = WigleCsv.Row("AA:BB:CC:DD:EE:0F", "Cafe, \"free\"", "[WPA2-PSK-CCMP][RSN-PSK-CCMP][ESS]",
            1_700_000_000_000L, 2437, -67, 42.123456789, -83.987654321, 201.26, 4.0, null, WigleCsv.Type.WIFI)
        assertEquals("aa:bb:cc:dd:ee:0f,\"Cafe, \"\"free\"\"\",[WPA2-PSK-CCMP][RSN-PSK-CCMP][ESS]," +
            "2023-11-14 22:13:20,6,2437,-67,42.12345679,-83.98765432,201.3,4,,,WIFI\n", WigleCsv.row(r))
    }

    @Test
    fun sixGhzRow() {
        val r = WigleCsv.Row("00:11:22:33:44:55", "lab6e", "[SAE-CCMP][ESS]", 0L, 6415, -80,
            1.0, 2.0, 0.0, 0.0, null, WigleCsv.Type.WIFI)
        assertEquals("00:11:22:33:44:55,lab6e,[SAE-CCMP][ESS],1970-01-01 00:00:00,93,6415,-80,1,2,0,0,,,WIFI\n", WigleCsv.row(r))
    }

    @Test
    fun bleRowHasNoChannel() {
        val r = WigleCsv.Row("C0:FF:EE:00:00:01", null, "Misc", 0L, null, -90, 1.5, -2.5, 10.0, 3.5, 76, WigleCsv.Type.BLE)
        assertEquals("c0:ff:ee:00:00:01,,Misc,1970-01-01 00:00:00,,,-90,1.5,-2.5,10,3.5,,76,BLE\n", WigleCsv.row(r))
    }

    @Test
    fun channels() {
        assertEquals(1, WigleCsv.channelOf(2412))
        assertEquals(13, WigleCsv.channelOf(2472))
        assertEquals(14, WigleCsv.channelOf(2484))
        assertEquals(36, WigleCsv.channelOf(5180))
        assertEquals(165, WigleCsv.channelOf(5825))
        assertEquals(177, WigleCsv.channelOf(5885))
        assertEquals(2, WigleCsv.channelOf(5935))
        assertEquals(1, WigleCsv.channelOf(5955))
        assertEquals(233, WigleCsv.channelOf(7115))
        assertNull(WigleCsv.channelOf(900))
    }

    @Test
    fun hiddenNameControlCharactersLeftOut() {
        assertEquals("", WigleCsv.cleanSsid("\u0000\u0000\u0000"))
        assertEquals("ab", WigleCsv.cleanSsid("a\u0007b"))
        assertEquals("", WigleCsv.cleanSsid(null))
    }

    @Test
    fun quoting() {
        assertEquals("plain", WigleCsv.field("plain"))
        assertEquals("\" lead\"", WigleCsv.field(" lead"))
        assertEquals("\"a\nb\"", WigleCsv.field("a\nb"))
        assertEquals("", WigleCsv.field(""))
    }

    @Test
    fun nomapOptOut() {
        assertTrue(WigleCsv.optedOut("HomeNet_nomap"))
        assertTrue(WigleCsv.optedOut("HomeNet_NOMAP "))
        assertFalse(WigleCsv.optedOut("nomap_HomeNet"))
        assertFalse(WigleCsv.optedOut(null))
    }
}
