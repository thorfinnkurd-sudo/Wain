package com.wain.app

import com.wain.app.core.geo.LatLon
import com.wain.app.core.location.FixFilter
import com.wain.app.core.location.FixQuality
import com.wain.app.core.location.LocationQuality
import com.wain.app.core.speed.MaxSpeedParser
import com.wain.app.core.speed.SpeedEstimator
import com.wain.app.core.speed.SpeedLimitMatcher
import com.wain.app.core.speed.SpeedLimitStatus
import com.wain.app.core.speed.SpeedUnit
import com.wain.app.core.speed.SpeedWarning
import com.wain.app.core.speed.WayGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationSpeedTest {
    @Test fun qualityClassification() {
        assertEquals(FixQuality.GOOD, LocationQuality.classify(fix(33.0, 44.0, acc = 10f, tMs = 1000), 2000))
        assertEquals(FixQuality.POOR, LocationQuality.classify(fix(33.0, 44.0, acc = 60f, tMs = 1000), 2000))
        assertEquals(FixQuality.STALE, LocationQuality.classify(fix(33.0, 44.0, acc = 5f, tMs = 1000), 10_000))
    }

    @Test fun filterRejectsInaccurateAndOutOfOrder() {
        val f = FixFilter()
        assertFalse(f.accept(fix(33.0, 44.0, acc = 150f, tMs = 1000)))
        assertTrue(f.accept(fix(33.0, 44.0, acc = 5f, tMs = 2000)))
        assertFalse(f.accept(fix(33.0, 44.0, acc = 5f, tMs = 1500)))
    }

    @Test fun filterRejectsTeleportThenRebaselines() {
        val f = FixFilter()
        assertTrue(f.accept(fix(33.0, 44.0, tMs = 0)))
        assertFalse(f.accept(fix(33.05, 44.0, tMs = 1000)))
        assertFalse(f.accept(fix(33.05, 44.0, tMs = 2000)))
        assertTrue(f.accept(fix(33.05, 44.0, tMs = 3000)))
    }

    @Test fun dopplerSpeedUsedWhenAccurate() {
        val e = SpeedEstimator().update(fix(33.0, 44.0, speed = 10f, speedAcc = 1f))
        assertNotNull(e)
        assertEquals(10.0, e!!.mps, 1e-6)
        assertEquals(SpeedEstimator.Source.DOPPLER, e.source)
    }

    @Test fun poorDopplerFallsBackToDerived() {
        val s = SpeedEstimator()
        assertNull(s.update(fix(33.0, 44.0, tMs = 0, speed = 10f, speedAcc = 5f)))
        val e = s.update(fix(33.0001, 44.0, tMs = 1000, speed = 10f, speedAcc = 5f))
        assertEquals(SpeedEstimator.Source.DERIVED, e!!.source)
        assertEquals(11.1, e.mps, 0.6)
    }

    @Test fun stationaryNoiseIsClampedToZero() {
        val s = SpeedEstimator()
        s.update(fix(33.0, 44.0, tMs = 0))
        assertEquals(0.0, s.update(fix(33.0, 44.0, tMs = 1000))!!.mps, 1e-9)
    }

    @Test fun staleSpeedDetected() {
        val e = SpeedEstimator.Estimate(5.0, SpeedEstimator.Source.DOPPLER, true, 1000)
        assertTrue(SpeedEstimator.isStale(e, 7000))
        assertFalse(SpeedEstimator.isStale(e, 3000))
    }

    @Test fun unitConversion() {
        assertEquals(36.0, SpeedUnit.KMH.fromMps(10.0), 1e-6)
        assertEquals(22.369, SpeedUnit.MPH.fromMps(10.0), 0.01)
        assertEquals(SpeedUnit.KMH, SpeedUnit.fromId("bogus"))
    }

    @Test fun maxspeedParsing() {
        assertEquals(50, MaxSpeedParser.parseKmh("50"))
        assertEquals(50, MaxSpeedParser.parseKmh("50 km/h"))
        assertEquals(97, MaxSpeedParser.parseKmh("60 mph"))
        assertNull(MaxSpeedParser.parseKmh("none"))
        assertNull(MaxSpeedParser.parseKmh("IQ:urban"))
        assertNull(MaxSpeedParser.parseKmh("4"))
        assertNull(MaxSpeedParser.parseKmh("200"))
        assertNull(MaxSpeedParser.parseKmh(null))
    }

    private fun way(id: Long, lat: Double, max: String?) =
        WayGeometry(id, max, false, listOf(LatLon(lat, 44.000), LatLon(lat, 44.010)))

    @Test fun matcherReturnsVerifiedLimit() {
        val r = SpeedLimitMatcher.match(LatLon(33.0001, 44.005), null, 10f, listOf(way(1, 33.0, "60")))
        assertEquals(60, (r as SpeedLimitStatus.Verified).kmh)
    }

    @Test fun matcherNeverBorrowsLimitFromFartherRoad() {
        val ways = listOf(way(1, 33.00003, null), way(2, 33.00009, "60"))
        assertEquals(SpeedLimitStatus.Unknown, SpeedLimitMatcher.match(LatLon(33.0, 44.005), null, 10f, ways))
    }

    @Test fun matcherUnknownWhenFarFromAnyRoad() {
        assertEquals(SpeedLimitStatus.Unknown, SpeedLimitMatcher.match(LatLon(33.001, 44.005), null, 10f, listOf(way(1, 33.0, "60"))))
        assertEquals(SpeedLimitStatus.Unknown, SpeedLimitMatcher.match(LatLon(33.0, 44.005), null, 10f, emptyList()))
    }

    @Test fun warningRequiresVerifiedLimitAndReliableSpeed() {
        val limit = SpeedLimitStatus.Verified(60, "OSM", 1)
        assertTrue(SpeedWarning.isOver(70.0, true, limit, 5))
        assertFalse(SpeedWarning.isOver(64.0, true, limit, 5))
        assertFalse(SpeedWarning.isOver(90.0, false, limit, 5))
        assertFalse(SpeedWarning.isOver(90.0, true, SpeedLimitStatus.Unknown, 5))
    }
}
