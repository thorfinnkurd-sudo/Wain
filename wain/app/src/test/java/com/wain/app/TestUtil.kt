package com.wain.app

import com.wain.app.core.geo.LatLon
import com.wain.app.core.location.LocationFix
import kotlin.math.roundToInt

fun fix(
    lat: Double, lon: Double, acc: Float = 5f, tMs: Long = 0L,
    speed: Float? = null, speedAcc: Float? = null, bearing: Float? = null,
) = LocationFix(LatLon(lat, lon), acc, speed, speedAcc, bearing, null, tMs, "test")

/** Reference encoder used to build fixtures; decoding is what production code does. */
fun encodePolyline(points: List<LatLon>, precision: Int = 6): String {
    val f = Math.pow(10.0, precision.toDouble())
    val sb = StringBuilder()
    var pLat = 0
    var pLon = 0
    fun enc(d: Int) {
        var v = (d shl 1) xor (d shr 31)
        while (v >= 0x20) {
            sb.append(((0x20 or (v and 0x1f)) + 63).toChar())
            v = v shr 5
        }
        sb.append((v + 63).toChar())
    }
    for (p in points) {
        val la = (p.lat * f).roundToInt()
        val lo = (p.lon * f).roundToInt()
        enc(la - pLat)
        enc(lo - pLon)
        pLat = la
        pLon = lo
    }
    return sb.toString()
}
