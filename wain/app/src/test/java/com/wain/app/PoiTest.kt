package com.wain.app

import com.wain.app.core.geo.BoundingBox
import com.wain.app.core.geo.LatLon
import com.wain.app.data.net.WainJson
import com.wain.app.data.overpass.OverpassParsers
import com.wain.app.data.overpass.OverpassQueries
import com.wain.app.data.overpass.OverpassResponse
import com.wain.app.data.poi.Poi
import com.wain.app.data.poi.PoiCategory
import com.wain.app.data.poi.PoiDeclutter
import com.wain.app.data.poi.PoiVisibility
import com.wain.app.data.safety.CameraStatus
import com.wain.app.data.safety.SafetyKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PoiTest {
    private fun poi(id: String, cat: PoiCategory, lat: Double, lon: Double, name: String? = null) =
        Poi(id, LatLon(lat, lon), cat, if (name == null) emptyMap() else mapOf("default" to name), null, null, null)

    @Test fun classifiesOsmTags() {
        assertEquals(PoiCategory.PHARMACY, PoiCategory.classify(mapOf("amenity" to "pharmacy")))
        assertEquals(PoiCategory.PHONE_SHOP, PoiCategory.classify(mapOf("shop" to "mobile_phone")))
        assertNull(PoiCategory.classify(mapOf("highway" to "bus_stop")))
    }

    @Test fun visibilityGrowsWithZoom() {
        assertEquals(0, PoiVisibility.maxTier(10.0))
        assertEquals(1, PoiVisibility.maxTier(12.0))
        assertEquals(2, PoiVisibility.maxTier(14.0))
        assertEquals(3, PoiVisibility.maxTier(16.0))
        assertTrue(PoiVisibility.categoriesFor(1).all { it.tier == 1 })
        assertEquals(11, PoiVisibility.fetchTileZoom(13.0))
        assertEquals(13, PoiVisibility.fetchTileZoom(15.0))
        assertEquals(15, PoiVisibility.fetchTileZoom(17.0))
    }

    @Test fun declutterKeepsHighestPriorityPerCell() {
        val a = poi("a", PoiCategory.BAKERY, 33.0, 44.0)
        val b = poi("b", PoiCategory.HOSPITAL, 33.000001, 44.000001)
        val far = poi("c", PoiCategory.BAKERY, 34.0, 45.0)
        val out = PoiDeclutter.select(listOf(a, b, far), 14.0, 100)
        assertEquals(listOf("b", "c"), out.map { it.id }.sorted())
    }

    @Test fun declutterShowsEverythingAtStreetZoomButDropsDuplicates() {
        val a = poi("a", PoiCategory.CAFE, 33.0, 44.0, "X")
        val dup = poi("n1", PoiCategory.CAFE, 33.0, 44.0, "X")
        val b = poi("b", PoiCategory.BAKERY, 33.000001, 44.000001)
        assertEquals(2, PoiDeclutter.select(listOf(a, dup, b), 18.0, 100).size)
    }

    @Test fun buildsOverpassPoiQuery() {
        val q = OverpassQueries.pois(listOf(PoiCategory.PHARMACY, PoiCategory.SUPERMARKET), BoundingBox(33.0, 44.0, 33.1, 44.1), 100)
        assertTrue(q.contains("\"amenity\"~\"^(pharmacy)$\""))
        assertTrue(q.contains("\"shop\"~\"^(supermarket)$\""))
        assertTrue(q.contains("out center tags 100;"))
    }

    @Test fun parsesPoisFromOverpassWithoutInventingFields() {
        val json = """{"elements":[
          {"type":"node","id":1,"lat":33.3,"lon":44.4,"tags":{"amenity":"pharmacy","name":"Al Noor","name:ar":"النور"}},
          {"type":"way","id":2,"center":{"lat":33.31,"lon":44.41},"tags":{"shop":"supermarket"}},
          {"type":"node","id":3,"lat":33.2,"lon":44.2,"tags":{"foo":"bar"}}]}"""
        val pois = OverpassParsers.toPois(WainJson.decodeFromString(OverpassResponse.serializer(), json))
        assertEquals(2, pois.size)
        assertEquals("النور", pois[0].displayName("ar"))
        assertEquals("Al Noor", pois[0].displayName("en"))
        assertNull(pois[1].displayName("ar"))
        assertNull(pois[0].phone)
    }

    @Test fun mappedCamerasAreAlwaysStatusUnknown() {
        val json = """{"elements":[
          {"type":"node","id":9,"lat":33.3,"lon":44.4,"tags":{"highway":"speed_camera","maxspeed":"80"}},
          {"type":"node","id":10,"lat":33.3,"lon":44.4,"tags":{"traffic_calming":"bump"}}]}"""
        val f = OverpassParsers.toSafetyFeatures(WainJson.decodeFromString(OverpassResponse.serializer(), json))
        assertEquals(SafetyKind.SPEED_CAMERA, f[0].kind)
        assertEquals(CameraStatus.UNKNOWN, f[0].cameraStatus)
        assertEquals(SafetyKind.SPEED_BUMP, f[1].kind)
    }
}
