package com.wain.app.data.overpass

import com.wain.app.core.geo.BoundingBox
import com.wain.app.core.geo.LatLon
import com.wain.app.core.speed.WayGeometry
import com.wain.app.data.net.WainJson
import com.wain.app.data.net.fetch
import com.wain.app.data.poi.Poi
import com.wain.app.data.poi.PoiCategory
import com.wain.app.data.safety.SafetyFeature
import com.wain.app.data.safety.SafetyKind
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale

@Serializable
data class OverpassPoint(val lat: Double, val lon: Double)

@Serializable
data class OverpassElement(
    val type: String,
    val id: Long,
    val lat: Double? = null,
    val lon: Double? = null,
    val center: OverpassPoint? = null,
    val tags: Map<String, String> = emptyMap(),
    val geometry: List<OverpassPoint>? = null,
)

@Serializable
data class OverpassResponse(val elements: List<OverpassElement> = emptyList())

/** Thin client for any Overpass API endpoint. Requests are serialised to respect public-server fair use. */
class OverpassClient(private val http: OkHttpClient, private val endpoint: String) {
    private val mutex = Mutex()

    suspend fun run(ql: String): OverpassResponse = mutex.withLock {
        val request = Request.Builder()
            .url(endpoint)
            .post(FormBody.Builder().add("data", ql).build())
            .build()
        WainJson.decodeFromString(OverpassResponse.serializer(), http.fetch(request))
    }
}

object OverpassQueries {
    private fun bbox(b: BoundingBox): String =
        String.format(Locale.US, "%.6f,%.6f,%.6f,%.6f", b.south, b.west, b.north, b.east)

    fun pois(categories: Collection<PoiCategory>, area: BoundingBox, limit: Int): String {
        val byKey = LinkedHashMap<String, MutableSet<String>>()
        for (c in categories) for ((k, v) in c.osm) byKey.getOrPut(k) { LinkedHashSet() }.add(v)
        val b = bbox(area)
        val clauses = byKey.entries.joinToString("\n") { (k, vs) ->
            """  nwr["$k"~"^(${vs.joinToString("|")})$"]($b);"""
        }
        return "[out:json][timeout:25];\n(\n$clauses\n);\nout center tags $limit;"
    }

    fun safety(area: BoundingBox, limit: Int): String {
        val b = bbox(area)
        return "[out:json][timeout:25];\n(\n" +
            """  node["highway"="speed_camera"]($b);""" + "\n" +
            """  node["traffic_calming"~"^(bump|hump|table|cushion)$"]($b);""" + "\n" +
            ");\nout tags $limit;"
    }

    fun waysAround(p: LatLon, radiusM: Int): String =
        String.format(Locale.US, "[out:json][timeout:10];\nway(around:%d,%.6f,%.6f)[\"highway\"];\nout tags geom 20;", radiusM, p.lat, p.lon)
}

object OverpassParsers {
    private val addressKeys = listOf("addr:street", "addr:housenumber", "addr:city")

    fun toPois(r: OverpassResponse): List<Poi> = r.elements.mapNotNull { e ->
        val pos = e.position() ?: return@mapNotNull null
        val category = PoiCategory.classify(e.tags) ?: return@mapNotNull null
        val names = HashMap<String, String>()
        e.tags["name"]?.let { names["default"] = it }
        e.tags["name:ar"]?.let { names["ar"] = it }
        e.tags["name:en"]?.let { names["en"] = it }
        e.tags["name:ckb"]?.let { names["ckb"] = it }
        val address = addressKeys.mapNotNull { e.tags[it] }.joinToString(" ").ifBlank { null }
        Poi(
            id = "${e.type}/${e.id}",
            position = pos,
            category = category,
            names = names,
            address = address,
            phone = e.tags["phone"] ?: e.tags["contact:phone"],
            openingHours = e.tags["opening_hours"],
        )
    }

    fun toSafetyFeatures(r: OverpassResponse): List<SafetyFeature> = r.elements.mapNotNull { e ->
        val pos = e.position() ?: return@mapNotNull null
        val kind = when {
            e.tags["highway"] == "speed_camera" -> SafetyKind.SPEED_CAMERA
            e.tags["traffic_calming"] != null -> SafetyKind.SPEED_BUMP
            else -> return@mapNotNull null
        }
        SafetyFeature(
            id = "${e.type}/${e.id}",
            position = pos,
            kind = kind,
            maxspeedRaw = e.tags["maxspeed"],
            direction = e.tags["direction"],
            lastCheckedOsm = e.tags["check_date"],
        )
    }

    fun toWays(r: OverpassResponse): List<WayGeometry> = r.elements.mapNotNull { e ->
        if (e.type != "way") return@mapNotNull null
        val pts = e.geometry?.map { LatLon(it.lat, it.lon) } ?: return@mapNotNull null
        val oneway = e.tags["oneway"] in setOf("yes", "true", "1") || e.tags["junction"] == "roundabout"
        WayGeometry(e.id, e.tags["maxspeed"], oneway, pts)
    }

    private fun OverpassElement.position(): LatLon? = when {
        lat != null && lon != null -> LatLon(lat, lon)
        center != null -> LatLon(center.lat, center.lon)
        else -> null
    }
}
