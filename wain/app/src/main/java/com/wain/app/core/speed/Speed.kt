package com.wain.app.core.speed

import com.wain.app.core.geo.GeoMath
import com.wain.app.core.geo.LatLon
import com.wain.app.core.location.LocationFix
import kotlin.math.roundToInt

enum class SpeedUnit(val id: String, private val perMps: Double) {
    KMH("kmh", 3.6),
    MPH("mph", 2.236936);

    fun fromMps(mps: Double): Double = mps * perMps

    companion object {
        fun fromId(id: String?): SpeedUnit = entries.firstOrNull { it.id == id } ?: KMH
        const val KMH_PER_MPH = 1.609344
    }
}

/** Speed from real fixes: Doppler speed when its accuracy is acceptable, otherwise derived from positions. */
class SpeedEstimator {
    enum class Source { DOPPLER, DERIVED }

    data class Estimate(val mps: Double, val source: Source, val reliable: Boolean, val atElapsedMs: Long)

    private var prev: LocationFix? = null
    private var smoothed: Double? = null

    fun update(fix: LocationFix): Estimate? {
        val p = prev
        prev = fix
        val doppler = fix.speedMps
        val sacc = fix.speedAccuracyMps
        val raw: Double
        val source: Source
        val reliable: Boolean
        if (doppler != null && doppler >= 0f && (sacc == null || sacc <= MAX_DOPPLER_ACCURACY_MPS)) {
            raw = doppler.toDouble()
            source = Source.DOPPLER
            reliable = fix.accuracyM <= 50f
        } else if (p != null) {
            val dt = (fix.elapsedRealtimeMs - p.elapsedRealtimeMs) / 1000.0
            if (dt < 0.5 || dt > 10.0) {
                smoothed = null
                return null
            }
            raw = GeoMath.distanceM(p.position, fix.position) / dt
            source = Source.DERIVED
            reliable = fix.accuracyM <= 25f && p.accuracyM <= 25f
        } else {
            return null
        }
        val s = smoothed?.let { it + ALPHA * (raw - it) } ?: raw
        smoothed = s
        return Estimate(if (s < STATIONARY_MPS) 0.0 else s, source, reliable, fix.elapsedRealtimeMs)
    }

    fun reset() {
        prev = null
        smoothed = null
    }

    companion object {
        const val MAX_DOPPLER_ACCURACY_MPS = 3f
        const val STATIONARY_MPS = 0.8
        const val ALPHA = 0.5
        const val STALE_AFTER_MS = 5_000L

        fun isStale(e: Estimate, nowElapsedMs: Long): Boolean = nowElapsedMs - e.atElapsedMs > STALE_AFTER_MS
    }
}

sealed interface SpeedLimitStatus {
    /** No reliable limit is known for the current road. The UI must say so rather than guess. */
    data object Unknown : SpeedLimitStatus

    /** Limit taken from a mapped `maxspeed` tag on the matched road segment. */
    data class Verified(val kmh: Int, val source: String, val wayId: Long?) : SpeedLimitStatus
}

object MaxSpeedParser {
    private val kmhRe = Regex("""^(\d{1,3})\s*(km/h|kmh|kph)?$""")
    private val mphRe = Regex("""^(\d{1,3})\s*mph$""")

    /** Returns the limit in km/h, or null for absent, symbolic ("none", "IQ:urban") or implausible values. */
    fun parseKmh(raw: String?): Int? {
        val s = raw?.trim()?.lowercase() ?: return null
        val kmh = mphRe.matchEntire(s)?.let { (it.groupValues[1].toInt() * SpeedUnit.KMH_PER_MPH).roundToInt() }
            ?: kmhRe.matchEntire(s)?.groupValues?.get(1)?.toInt()
            ?: return null
        return if (kmh in 5..140) kmh else null
    }
}

data class WayGeometry(
    val id: Long,
    val maxspeedRaw: String?,
    val oneway: Boolean,
    val points: List<LatLon>,
)

object SpeedLimitMatcher {
    /**
     * Picks the road under the vehicle and reports its mapped limit. The nearest road wins; if that road
     * has no parsable `maxspeed` the result is Unknown (a farther road's limit is never borrowed).
     */
    fun match(position: LatLon, bearingDeg: Double?, accuracyM: Float, ways: List<WayGeometry>): SpeedLimitStatus {
        val maxDistance = accuracyM.toDouble().coerceIn(15.0, 40.0)
        var best: WayGeometry? = null
        var bestScore = Double.MAX_VALUE
        for (w in ways) {
            if (w.points.size < 2) continue
            var nearest = Double.MAX_VALUE
            var segBearing = 0.0
            for (i in 0 until w.points.size - 1) {
                val pr = GeoMath.projectOnSegment(position, w.points[i], w.points[i + 1])
                if (pr.distanceM < nearest) {
                    nearest = pr.distanceM
                    segBearing = GeoMath.bearingDeg(w.points[i], w.points[i + 1])
                }
            }
            if (nearest > maxDistance) continue
            var score = nearest
            if (bearingDeg != null) {
                val diff = GeoMath.angleDiffDeg(bearingDeg, segBearing)
                val aligned = if (w.oneway) diff <= 60.0 else (diff <= 60.0 || diff >= 120.0)
                if (!aligned) score += 25.0
            }
            if (score < bestScore) {
                bestScore = score
                best = w
            }
        }
        val kmh = MaxSpeedParser.parseKmh(best?.maxspeedRaw) ?: return SpeedLimitStatus.Unknown
        return SpeedLimitStatus.Verified(kmh, "OpenStreetMap maxspeed", best?.id)
    }
}

object SpeedWarning {
    /** Warn only for a verified limit and a reliable, non-stale speed. */
    fun isOver(speedKmh: Double, reliable: Boolean, limit: SpeedLimitStatus, toleranceKmh: Int): Boolean {
        if (!reliable) return false
        val l = limit as? SpeedLimitStatus.Verified ?: return false
        return speedKmh > l.kmh + toleranceKmh
    }
}
