package com.rfsentinel.app.util

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The WiGLE upload format (WigleWifi-1.6), as WiGLE's own Android app writes it
 * (wigle-wifi-wardriving, ObservationUploader): a pre-header line, the column header line, then one
 * row per observation. RFC 4180 quoting, "\n" line ends, UTF-8, times in UTC.
 * Plain Kotlin (no Android classes) so it can be unit tested on its own.
 */
object WigleCsv {

    const val COLUMNS = "MAC,SSID,AuthMode,FirstSeen,Channel,Frequency,RSSI,CurrentLatitude,CurrentLongitude," +
        "AltitudeMeters,AccuracyMeters,RCOIs,MfgrId,Type"

    enum class Type { WIFI, BLE }

    /** One observation: where this phone was when it heard [mac]. */
    data class Row(
        val mac: String,
        val ssid: String?,
        /** WiFi: Android's capability text ("[WPA2-PSK-CCMP][ESS]"); BLE: the device class ("Misc"). */
        val authMode: String,
        val time: Long,
        /** WiFi only, MHz. */
        val frequencyMhz: Int?,
        val rssi: Int,
        val lat: Double,
        val lon: Double,
        val altitudeM: Double,
        val accuracyM: Double,
        /** BLE only: the Bluetooth SIG company id from its manufacturer data. */
        val mfgrId: Int?,
        val type: Type
    )

    /** The two header lines. [appRelease], [model]... describe the phone and app, as WiGLE's app does. */
    fun header(appRelease: String, model: String, release: String, device: String, display: String,
               board: String, brand: String): String =
        listOf("WigleWifi-1.6", "appRelease=$appRelease", "model=$model", "release=$release", "device=$device",
            "display=$display", "board=$board", "brand=$brand", "star=Sol", "body=3", "subBody=0")
            .joinToString(",") { field(it) } + "\n" + COLUMNS + "\n"

    fun row(r: Row): String {
        val f = r.frequencyMhz?.takeIf { it > 0 && r.type == Type.WIFI }
        return listOf(
            field(r.mac.lowercase(Locale.US)),
            field(cleanSsid(r.ssid)),
            field(r.authMode),
            utc(r.time),
            f?.let { channelOf(it) }?.toString().orEmpty(),
            f?.toString().orEmpty(),
            r.rssi.toString(),
            num(r.lat, 8),
            num(r.lon, 8),
            num(r.altitudeM, 1),
            num(r.accuracyM, 1),
            "", // RCOIs (Passpoint roaming consortium ids): not read here
            r.mfgrId?.takeIf { it != 0 }?.toString().orEmpty(),
            r.type.name
        ).joinToString(",") + "\n"
    }

    /**
     * The WiFi channel of a frequency in MHz: 2.4 GHz 1-14, 5 GHz 32-177 (and 4.9 GHz), 6 GHz 1-233
     * (5935 MHz is 6 GHz channel 2). Null for anything else.
     */
    fun channelOf(mhz: Int): Int? = when {
        mhz == 2484 -> 14
        mhz in 2412..2472 -> (mhz - 2407) / 5
        mhz == 5935 -> 2
        mhz in 5955..7115 -> (mhz - 5950) / 5
        mhz in 5160..5925 -> (mhz - 5000) / 5
        mhz in 4910..4990 -> (mhz - 4000) / 5
        else -> null
    }

    /** "yyyy-MM-dd HH:mm:ss" in UTC, as WiGLE's app writes FirstSeen. */
    fun utc(time: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(time))

    /** A network name as text: control characters (zero bytes of a blank hidden name) left out. */
    fun cleanSsid(ssid: String?): String = ssid.orEmpty().filterNot { it.isISOControl() }

    /** RFC 4180: quoted when it holds a comma, a quote or a line break (quotes doubled), or starts or ends with a space. */
    fun field(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' } || s.trim() != s)
            "\"" + s.replace("\"", "\"\"") + "\""
        else s

    /** No grouping, no exponent, at most [decimals] places, trailing zeros dropped. */
    private fun num(v: Double, decimals: Int): String =
        if (v.isNaN() || v.isInfinite()) "0"
        else BigDecimal(v).setScale(decimals, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
            .let { if (it == "-0") "0" else it }

    /** WiGLE (and Google, Mozilla) leave out networks whose owner asked not to be mapped by ending the name in _nomap. */
    fun optedOut(ssid: String?): Boolean = ssid?.trim()?.endsWith("_nomap", ignoreCase = true) == true
}
