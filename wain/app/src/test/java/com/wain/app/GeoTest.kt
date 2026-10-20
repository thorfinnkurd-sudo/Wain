package com.wain.app

import com.wain.app.core.geo.BoundingBox
import com.wain.app.core.geo.CoordinateFormat
import com.wain.app.core.geo.CoordinateParser
import com.wain.app.core.geo.GeoMath
import com.wain.app.core.geo.LatLon
import com.wain.app.core.geo.Polyline
import com.wain.app.core.geo.TileKey
import com.wain.app.core.geo.Tiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoTest {
    @Test fun parsesDecimalPair() {
        assertEquals(LatLon(33.3152, 44.3661), CoordinateParser.parse("33.3152, 44.3661"))
    }

    @Test fun parsesHemisphereLetters() {
        assertEquals(LatLon(33.3152, 44.3661), CoordinateParser.parse("33.3152 N 44.3661 E"))
        assertEquals(LatLon(-33.3, -44.3), CoordinateParser.parse("33.3 S 44.3 W"))
    }

    @Test fun parsesArabicIndicDigits() {
        assertEquals(LatLon(33.3152, 44.3661), CoordinateParser.parse("٣٣٫٣١٥٢، ٤٤٫٣٦٦١"))
    }

    @Test fun rejectsNonCoordinates() {
        assertNull(CoordinateParser.parse("Baghdad"))
        assertNull(CoordinateParser.parse("12 34"))
        assertNull(CoordinateParser.parse("91.0, 44.0"))
        assertNull(CoordinateParser.parse("33.0, 181.0"))
    }

    @Test fun formatsCoordinates() {
        assertEquals("Latitude: 33.3152° N\nLongitude: 44.3661° E", CoordinateFormat.display(LatLon(33.3152, 44.3661)))
        assertEquals("1.5000° S", CoordinateFormat.latitude(-1.5))
        assertEquals("33.315200, 44.366100", CoordinateFormat.decimal(LatLon(33.3152, 44.3661)))
    }

    @Test fun distanceOfOneDegreeLatitude() {
        assertEquals(111_195.0, GeoMath.distanceM(LatLon(0.0, 0.0), LatLon(1.0, 0.0)), 100.0)
    }

    @Test fun bearingsAndAngles() {
        assertEquals(90.0, GeoMath.bearingDeg(LatLon(0.0, 0.0), LatLon(0.0, 1.0)), 0.01)
        assertEquals(20.0, GeoMath.angleDiffDeg(350.0, 10.0), 1e-9)
        val p = GeoMath.offset(LatLon(0.0, 0.0), 0.0, 1000.0)
        assertEquals(0.008993, p.lat, 1e-5)
    }

    @Test fun projectsOntoSegment() {
        val r = GeoMath.projectOnSegment(LatLon(0.001, 0.005), LatLon(0.0, 0.0), LatLon(0.0, 0.01))
        assertEquals(111.2, r.distanceM, 1.0)
        assertEquals(0.5, r.t, 0.01)
    }

    @Test fun polylineRoundTrip() {
        val pts = listOf(LatLon(33.315211, 44.366122), LatLon(33.316, 44.37), LatLon(33.30, 44.35))
        val decoded = Polyline.decode(encodePolyline(pts))
        assertEquals(pts.size, decoded.size)
        for (i in pts.indices) {
            assertEquals(pts[i].lat, decoded[i].lat, 1e-6)
            assertEquals(pts[i].lon, decoded[i].lon, 1e-6)
        }
    }

    @Test fun polylineToleratesTruncatedInput() {
        val s = encodePolyline(listOf(LatLon(33.0, 44.0), LatLon(33.1, 44.1)))
        assertNotNull(Polyline.decode(s.dropLast(2)))
        assertTrue(Polyline.decode("").isEmpty())
    }

    @Test fun tileBoundsContainSourcePoint() {
        val p = LatLon(33.3152, 44.3661)
        val k = TileKey(13, Tiles.xOf(p.lon, 13), Tiles.yOf(p.lat, 13))
        assertTrue(Tiles.bounds(k).contains(p))
        assertTrue(Tiles.covering(BoundingBox(33.30, 44.35, 33.33, 44.38), 13).isNotEmpty())
        assertFalse(Tiles.bounds(k).contains(LatLon(0.0, 0.0)))
    }
}
