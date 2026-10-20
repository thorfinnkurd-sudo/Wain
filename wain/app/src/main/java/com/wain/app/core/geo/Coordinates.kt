package com.wain.app.core.geo

import java.util.Locale
import kotlin.math.abs

object CoordinateFormat {
    fun latitude(lat: Double, decimals: Int = 4): String =
        String.format(Locale.US, "%.${decimals}f° %s", abs(lat), if (lat >= 0) "N" else "S")

    fun longitude(lon: Double, decimals: Int = 4): String =
        String.format(Locale.US, "%.${decimals}f° %s", abs(lon), if (lon >= 0) "E" else "W")

    /** Two-line display, e.g. "Latitude: 33.3152° N\nLongitude: 44.3661° E". */
    fun display(p: LatLon, decimals: Int = 4): String =
        "Latitude: ${latitude(p.lat, decimals)}\nLongitude: ${longitude(p.lon, decimals)}"

    /** Plain decimal pair suitable for copying, e.g. "33.315200, 44.366100". */
    fun decimal(p: LatLon, decimals: Int = 6): String =
        String.format(Locale.US, "%.${decimals}f, %.${decimals}f", p.lat, p.lon)
}

object CoordinateParser {
    private val pair = Regex(
        """^\s*(-?\d{1,3}\.\d+)\s*°?\s*([NSns])?\s*[,;\s]\s*(-?\d{1,3}\.\d+)\s*°?\s*([EWew])?\s*$"""
    )

    /**
     * Parses decimal-degree pairs such as "33.3152, 44.3661", "33.3152 N 44.3661 E" and
     * Arabic-Indic digits. Both numbers must contain a decimal point, so ordinary text such as
     * a street number is never mistaken for coordinates. Returns null if invalid or out of range.
     */
    fun parse(input: String): LatLon? {
        val m = pair.matchEntire(normalize(input)) ?: return null
        var lat = m.groupValues[1].toDoubleOrNull() ?: return null
        var lon = m.groupValues[3].toDoubleOrNull() ?: return null
        if (m.groupValues[2].equals("S", ignoreCase = true)) lat = -abs(lat)
        if (m.groupValues[4].equals("W", ignoreCase = true)) lon = -abs(lon)
        val p = LatLon(lat, lon)
        return if (p.isValid()) p else null
    }

    private fun normalize(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            sb.append(
                when (c) {
                    in '\u0660'..'\u0669' -> '0' + (c - '\u0660')
                    in '\u06F0'..'\u06F9' -> '0' + (c - '\u06F0')
                    '\u066B' -> '.'
                    '\u060C' -> ','
                    else -> c
                }
            )
        }
        return sb.toString()
    }
}
