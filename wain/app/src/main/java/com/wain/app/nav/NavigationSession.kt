package com.wain.app.nav

import com.wain.app.core.geo.GeoMath
import com.wain.app.core.geo.LatLon
import com.wain.app.core.geo.SegmentProjection
import com.wain.app.core.location.LocationFix
import com.wain.app.data.routing.Route
import com.wain.app.data.safety.SafetyFeature
import kotlin.math.max

/** Pure route-following logic: snaps real fixes to the route and tracks progress, off-route state and ETA. */
class NavigationSession(val route: Route) {
    data class Progress(
        val snapped: LatLon,
        val alongM: Double,
        val remainingM: Double,
        val remainingS: Double,
        val currentIndex: Int,
        /** Index of the next maneuver to perform, or null once past the last one. */
        val nextIndex: Int?,
        val distToNextM: Double,
        val lateralM: Double,
        val offRoute: Boolean,
        val arrived: Boolean,
    )

    private val pts = route.points
    private val cum = route.cumulativeM
    private val total = cum.last()
    private var lastSeg = 0
    private var offCount = 0
    private val maneuverDist = DoubleArray(route.maneuvers.size) {
        cum[route.maneuvers[it].beginIndex.coerceIn(0, cum.lastIndex)]
    }

    init {
        require(pts.size >= 2) { "A route needs at least two points" }
    }

    private fun scan(p: LatLon, from: Int, to: Int): Pair<Int, SegmentProjection> {
        var bestSeg = from
        var best = GeoMath.projectOnSegment(p, pts[from], pts[from + 1])
        for (i in from + 1..to) {
            val pr = GeoMath.projectOnSegment(p, pts[i], pts[i + 1])
            if (pr.distanceM < best.distanceM) {
                best = pr
                bestSeg = i
            }
        }
        return bestSeg to best
    }

    fun update(fix: LocationFix): Progress {
        val last = pts.size - 2
        val from = max(0, lastSeg - 3)
        val to = minOf(last, lastSeg + WINDOW)
        var (seg, proj) = scan(fix.position, from, to)
        if (proj.distanceM > FULL_SCAN_THRESHOLD_M) {
            val full = scan(fix.position, 0, last)
            if (full.second.distanceM < proj.distanceM) {
                seg = full.first
                proj = full.second
            }
        }
        lastSeg = seg
        val along = cum[seg] + proj.t * (cum[seg + 1] - cum[seg])
        val remaining = (total - along).coerceAtLeast(0.0)

        var cur = 0
        for (i in maneuverDist.indices) if (maneuverDist[i] <= along) cur = i
        var next: Int? = null
        for (i in maneuverDist.indices) if (maneuverDist[i] > along + 0.5) { next = i; break }

        val remainingS = if (route.maneuvers.isEmpty()) {
            route.durationS * (remaining / max(total, 1.0))
        } else {
            val m = route.maneuvers[cur]
            val frac = ((along - maneuverDist[cur]) / max(m.lengthM, 1.0)).coerceIn(0.0, 1.0)
            var s = m.timeS * (1.0 - frac)
            for (j in cur + 1 until route.maneuvers.size) s += route.maneuvers[j].timeS
            s
        }

        val offThreshold = max(OFF_ROUTE_MIN_M, 2.0 * fix.accuracyM)
        if (proj.distanceM > offThreshold) offCount++ else offCount = 0

        return Progress(
            snapped = proj.point,
            alongM = along,
            remainingM = remaining,
            remainingS = remainingS,
            currentIndex = cur,
            nextIndex = next,
            distToNextM = next?.let { maneuverDist[it] - along } ?: remaining,
            lateralM = proj.distanceM,
            offRoute = offCount >= OFF_ROUTE_FIXES,
            arrived = remaining <= ARRIVAL_M,
        )
    }

    /**
     * Mapped safety features lying on the route within [lookaheadM] ahead of [alongM], nearest first.
     * Returns (feature, distance ahead in metres).
     */
    fun featuresAhead(
        alongM: Double,
        features: List<SafetyFeature>,
        lookaheadM: Double,
        corridorM: Double = 30.0,
    ): List<Pair<SafetyFeature, Double>> {
        var startSeg = lastSeg
        while (startSeg > 0 && cum[startSeg] > alongM) startSeg--
        var endSeg = startSeg
        while (endSeg < pts.size - 2 && cum[endSeg + 1] < alongM + lookaheadM) endSeg++
        val out = ArrayList<Pair<SafetyFeature, Double>>()
        for (f in features) {
            var bestLateral = Double.MAX_VALUE
            var bestAlong = 0.0
            for (i in startSeg..endSeg) {
                val pr = GeoMath.projectOnSegment(f.position, pts[i], pts[i + 1])
                if (pr.distanceM < bestLateral) {
                    bestLateral = pr.distanceM
                    bestAlong = cum[i] + pr.t * (cum[i + 1] - cum[i])
                }
            }
            val ahead = bestAlong - alongM
            if (bestLateral <= corridorM && ahead in 0.0..lookaheadM) out.add(f to ahead)
        }
        return out.sortedBy { it.second }
    }

    companion object {
        const val WINDOW = 80
        const val FULL_SCAN_THRESHOLD_M = 60.0
        const val OFF_ROUTE_MIN_M = 40.0
        const val OFF_ROUTE_FIXES = 3
        const val ARRIVAL_M = 25.0
    }
}

/** Decides when a maneuver should be spoken: at most once per stage, never repeating or going backwards. */
class VoiceAnnouncer {
    enum class Stage { FAR, NEAR, NOW }

    private var maneuver = -1
    private var lastStage = -1

    fun next(maneuverIndex: Int, distM: Double, speedMps: Double): Stage? {
        if (maneuverIndex != maneuver) {
            maneuver = maneuverIndex
            lastStage = -1
        }
        val stage = when {
            distM <= NOW_M -> Stage.NOW
            distM <= NEAR_M -> Stage.NEAR
            distM <= FAR_M && speedMps >= FAST_MPS -> Stage.FAR
            else -> return null
        }
        if (stage.ordinal <= lastStage) return null
        lastStage = stage.ordinal
        return stage
    }

    fun reset() {
        maneuver = -1
        lastStage = -1
    }

    companion object {
        const val FAR_M = 800.0
        const val NEAR_M = 300.0
        const val NOW_M = 60.0
        const val FAST_MPS = 14.0
    }
}
