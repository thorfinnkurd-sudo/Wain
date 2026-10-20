package com.wain.app.core.geo

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan

data class LatLon(val lat: Double, val lon: Double) {
    fun isValid(): Boolean =
        !lat.isNaN() && !lon.isNaN() && lat in -90.0..90.0 && lon in -180.0..180.0
}

data class BoundingBox(val south: Double, val west: Double, val north: Double, val east: Double) {
    val center: LatLon get() = LatLon((south + north) / 2, (west + east) / 2)
    fun contains(p: LatLon): Boolean = p.lat in south..north && p.lon in west..east
}

data class SegmentProjection(val distanceM: Double, val t: Double, val point: LatLon)

object GeoMath {
    const val EARTH_RADIUS_M = 6_371_008.8
    private const val M_PER_DEG = 111_194.93

    fun distanceM(a: LatLon, b: LatLon): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).pow(2)
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(h)))
    }

    fun bearingDeg(from: LatLon, to: LatLon): Double {
        val p1 = Math.toRadians(from.lat)
        val p2 = Math.toRadians(to.lat)
        val dl = Math.toRadians(to.lon - from.lon)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return normalizeDeg(Math.toDegrees(atan2(y, x)))
    }

    fun normalizeDeg(d: Double): Double {
        val r = d % 360.0
        return if (r < 0) r + 360.0 else r
    }

    /** Smallest absolute angle between two bearings, 0..180. */
    fun angleDiffDeg(a: Double, b: Double): Double {
        val d = abs(normalizeDeg(a) - normalizeDeg(b))
        return if (d > 180.0) 360.0 - d else d
    }

    fun offset(from: LatLon, bearingDeg: Double, distanceM: Double): LatLon {
        val br = Math.toRadians(bearingDeg)
        val d = distanceM / EARTH_RADIUS_M
        val lat1 = Math.toRadians(from.lat)
        val lon1 = Math.toRadians(from.lon)
        val lat2 = asin(sin(lat1) * cos(d) + cos(lat1) * sin(d) * cos(br))
        val lon2 = lon1 + atan2(sin(br) * sin(d) * cos(lat1), cos(d) - sin(lat1) * sin(lat2))
        return LatLon(Math.toDegrees(lat2), Math.toDegrees(lon2))
    }

    /** Projects [p] onto segment a-b using a local planar approximation (accurate for short segments). */
    fun projectOnSegment(p: LatLon, a: LatLon, b: LatLon): SegmentProjection {
        val cosLat = cos(Math.toRadians(p.lat))
        val ax = (a.lon - p.lon) * cosLat * M_PER_DEG
        val ay = (a.lat - p.lat) * M_PER_DEG
        val bx = (b.lon - p.lon) * cosLat * M_PER_DEG
        val by = (b.lat - p.lat) * M_PER_DEG
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 < 1e-9) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
        val cx = ax + t * dx
        val cy = ay + t * dy
        return SegmentProjection(
            distanceM = sqrt(cx * cx + cy * cy),
            t = t,
            point = LatLon(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t),
        )
    }
}

object Polyline {
    /** Decodes a Google-style encoded polyline. Valhalla uses precision 6. Malformed tails are ignored. */
    fun decode(encoded: String, precision: Int = 6): List<LatLon> {
        val factor = 10.0.pow(precision)
        val out = ArrayList<LatLon>()
        var index = 0
        var lat = 0
        var lon = 0
        while (index < encoded.length) {
            var result = 0
            var shift = 0
            var b: Int
            do {
                if (index >= encoded.length) return out
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            lat += if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)

            result = 0
            shift = 0
            do {
                if (index >= encoded.length) return out
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            lon += if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)

            out.add(LatLon(lat / factor, lon / factor))
        }
        return out
    }
}

data class TileKey(val z: Int, val x: Int, val y: Int)

object Tiles {
    fun xOf(lon: Double, z: Int): Int {
        val n = 1 shl z
        return floor((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
    }

    fun yOf(lat: Double, z: Int): Int {
        val n = 1 shl z
        val r = Math.toRadians(lat.coerceIn(-85.05112878, 85.05112878))
        return floor((1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0 * n).toInt().coerceIn(0, n - 1)
    }

    private fun latOfEdge(y: Int, z: Int): Double =
        Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * y / (1 shl z)))))

    fun bounds(k: TileKey): BoundingBox {
        val n = (1 shl k.z).toDouble()
        return BoundingBox(
            south = latOfEdge(k.y + 1, k.z),
            west = k.x / n * 360.0 - 180.0,
            north = latOfEdge(k.y, k.z),
            east = (k.x + 1) / n * 360.0 - 180.0,
        )
    }

    fun covering(b: BoundingBox, z: Int): List<TileKey> {
        val x0 = xOf(b.west, z)
        val x1 = xOf(b.east, z)
        val y0 = yOf(b.north, z)
        val y1 = yOf(b.south, z)
        val out = ArrayList<TileKey>()
        for (x in minOf(x0, x1)..maxOf(x0, x1)) {
            for (y in minOf(y0, y1)..maxOf(y0, y1)) out.add(TileKey(z, x, y))
        }
        return out
    }
}
