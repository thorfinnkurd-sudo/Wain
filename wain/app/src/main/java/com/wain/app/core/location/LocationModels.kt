package com.wain.app.core.location

import com.wain.app.core.geo.GeoMath
import com.wain.app.core.geo.LatLon

/** A real measurement from the device's location provider (never interpolated or predicted). */
data class LocationFix(
    val position: LatLon,
    val accuracyM: Float,
    val speedMps: Float?,
    val speedAccuracyMps: Float?,
    val bearingDeg: Float?,
    val bearingAccuracyDeg: Float?,
    val elapsedRealtimeMs: Long,
    val provider: String,
)

enum class FixQuality { GOOD, POOR, STALE }

object LocationQuality {
    const val GOOD_ACCURACY_M = 30f
    const val STALE_AFTER_MS = 6_000L

    fun classify(fix: LocationFix, nowElapsedMs: Long): FixQuality = when {
        nowElapsedMs - fix.elapsedRealtimeMs > STALE_AFTER_MS -> FixQuality.STALE
        fix.accuracyM > GOOD_ACCURACY_M -> FixQuality.POOR
        else -> FixQuality.GOOD
    }
}

/**
 * Rejects fixes that would make the marker jump: very inaccurate fixes, out-of-order fixes, and
 * physically implausible teleports. After [MAX_REJECT_STREAK] consecutive rejections the filter
 * re-baselines on the new fix so a genuine relocation (e.g. after a tunnel) is not blocked forever.
 */
class FixFilter(
    private val maxAccuracyM: Float = 100f,
    private val maxPlausibleSpeedMps: Double = 80.0,
) {
    private var last: LocationFix? = null
    private var rejectStreak = 0

    fun accept(fix: LocationFix): Boolean {
        if (!fix.position.isValid() || fix.accuracyM > maxAccuracyM || fix.accuracyM <= 0f) return false
        val prev = last
        if (prev != null) {
            val dtS = (fix.elapsedRealtimeMs - prev.elapsedRealtimeMs) / 1000.0
            if (dtS <= 0.0) return false
            val dist = GeoMath.distanceM(prev.position, fix.position)
            val implied = dist / dtS
            val beyondNoise = dist > fix.accuracyM + prev.accuracyM
            if (implied > maxPlausibleSpeedMps && beyondNoise) {
                rejectStreak++
                if (rejectStreak < MAX_REJECT_STREAK) return false
            }
        }
        rejectStreak = 0
        last = fix
        return true
    }

    fun reset() {
        last = null
        rejectStreak = 0
    }

    companion object {
        const val MAX_REJECT_STREAK = 3
    }
}
