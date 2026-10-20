package com.wain.app

import com.wain.app.core.geo.LatLon
import com.wain.app.data.routing.Maneuver
import com.wain.app.data.routing.Route
import com.wain.app.data.routing.RoutingException
import com.wain.app.data.routing.ValhallaParser
import com.wain.app.nav.DistanceFormat
import com.wain.app.nav.DurationFormat
import com.wain.app.nav.InstructionFormatter
import com.wain.app.nav.NavigationSession
import com.wain.app.nav.VoiceAnnouncer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingNavTest {
    private val pts = listOf(LatLon(33.0, 44.0), LatLon(33.0, 44.01), LatLon(33.01, 44.01))

    private fun route(): Route {
        val m = listOf(
            Maneuver(1, "Drive east", listOf("A St"), 931.0, 90.0, 0, null),
            Maneuver(10, "Turn right", listOf("B St"), 1112.0, 110.0, 1, null),
            Maneuver(4, "Arrive", emptyList(), 0.0, 0.0, 2, null),
        )
        return Route("r", pts, 2043.0, 200.0, m)
    }

    private fun sampleJson(alternates: Boolean): String {
        val shape = encodePolyline(pts)
        val trip = """{"legs":[{"shape":"${shape.replace("\\", "\\\\")}","maneuvers":[
          {"type":1,"instruction":"Drive east.","street_names":["A St"],"length":0.931,"time":90,"begin_shape_index":0},
          {"type":10,"instruction":"Turn right.","length":1.112,"time":110,"begin_shape_index":1},
          {"type":4,"instruction":"Arrive.","length":0,"time":0,"begin_shape_index":2}]}],
          "summary":{"length":2.043,"time":200}}"""
        return if (alternates) """{"trip":$trip,"alternates":[{"trip":$trip}]}""" else """{"trip":$trip}"""
    }

    @Test fun parsesValhallaResponse() {
        val r = ValhallaParser.parse(sampleJson(false)).single()
        assertEquals(3, r.points.size)
        assertEquals(2043.0, r.distanceM, 1e-6)
        assertEquals(200.0, r.durationS, 1e-6)
        assertEquals(listOf(1, 10, 4), r.maneuvers.map { it.type })
        assertEquals(listOf("A St"), r.maneuvers[0].streetNames)
        assertEquals(1, r.maneuvers[1].beginIndex)
    }

    @Test fun parsesAlternates() {
        assertEquals(2, ValhallaParser.parse(sampleJson(true)).size)
    }

    @Test fun routingErrorsSurfaceAsExceptions() {
        try {
            ValhallaParser.parse("""{"error_code":171,"error":"No suitable edges near location","status_code":400}""")
            throw AssertionError("expected RoutingException")
        } catch (e: RoutingException) {
            assertEquals("No suitable edges near location", e.message)
            assertEquals(171, e.code)
        }
    }

    @Test fun requestIncludesHeadingOnlyWhenKnown() {
        val withH = ValhallaParser.buildRequest(pts[0], pts[2], 90.0, 2)
        val without = ValhallaParser.buildRequest(pts[0], pts[2], null, 0)
        assertTrue(withH.contains("\"heading\":90"))
        assertTrue(withH.contains("\"costing\":\"auto\""))
        assertFalse(without.contains("heading"))
        assertFalse(without.contains("alternates"))
    }

    @Test fun progressAlongRoute() {
        val s = NavigationSession(route())
        val p = s.update(fix(33.0, 44.005))
        assertEquals(465.0, p.alongM, 10.0)
        assertEquals(1, p.nextIndex)
        assertEquals(465.0, p.distToNextM, 10.0)
        assertFalse(p.offRoute)
        assertFalse(p.arrived)
        assertTrue(p.remainingS in 100.0..200.0)
    }

    @Test fun detectsOffRouteAfterConsecutiveFixes() {
        val s = NavigationSession(route())
        assertFalse(s.update(fix(33.005, 44.0, tMs = 0)).offRoute)
        assertFalse(s.update(fix(33.005, 44.0, tMs = 1000)).offRoute)
        assertTrue(s.update(fix(33.005, 44.0, tMs = 2000)).offRoute)
    }

    @Test fun detectsArrival() {
        assertTrue(NavigationSession(route()).update(fix(33.01, 44.01)).arrived)
    }

    @Test fun findsMappedFeaturesAheadOnRouteOnly() {
        val s = NavigationSession(route())
        val on = com.wain.app.data.safety.SafetyFeature("on", LatLon(33.0, 44.005), com.wain.app.data.safety.SafetyKind.SPEED_BUMP, null, null, null)
        val off = com.wain.app.data.safety.SafetyFeature("off", LatLon(33.002, 44.005), com.wain.app.data.safety.SafetyKind.SPEED_BUMP, null, null, null)
        val hits = s.featuresAhead(0.0, listOf(on, off), 1000.0)
        assertEquals(listOf("on"), hits.map { it.first.id })
    }

    @Test fun announcerSpeaksEachStageOnce() {
        val a = VoiceAnnouncer()
        assertNull(a.next(1, 900.0, 20.0))
        assertEquals(VoiceAnnouncer.Stage.FAR, a.next(1, 700.0, 20.0))
        assertNull(a.next(1, 650.0, 20.0))
        assertEquals(VoiceAnnouncer.Stage.NEAR, a.next(1, 250.0, 20.0))
        assertEquals(VoiceAnnouncer.Stage.NOW, a.next(1, 40.0, 20.0))
        assertNull(a.next(1, 30.0, 20.0))
        assertEquals(VoiceAnnouncer.Stage.NEAR, a.next(2, 250.0, 20.0))
    }

    @Test fun slowTrafficSkipsFarAnnouncement() {
        assertNull(VoiceAnnouncer().next(1, 700.0, 5.0))
    }

    @Test fun localisedInstructions() {
        val m = Maneuver(10, "x", listOf("Main St"), 0.0, 0.0, 0, null)
        assertEquals("Turn right onto Main St", InstructionFormatter.text(m, "en"))
        assertTrue(InstructionFormatter.text(m, "ar").contains("يميناً"))
        assertEquals(90f, InstructionFormatter.arrowRotationDeg(10), 0f)
        assertEquals("In 300 meters, Turn right onto Main St", InstructionFormatter.spoken(300.0, m, "en"))
    }

    @Test fun distanceAndDurationFormatting() {
        assertEquals("350 m", DistanceFormat.short(350.0, "en"))
        assertEquals("40 m", DistanceFormat.short(42.0, "en"))
        assertEquals("1.2 km", DistanceFormat.short(1234.0, "en"))
        assertEquals("10 min", DurationFormat.short(600.0, "en"))
        assertEquals("1 h 05 min", DurationFormat.short(3900.0, "en"))
    }
}
