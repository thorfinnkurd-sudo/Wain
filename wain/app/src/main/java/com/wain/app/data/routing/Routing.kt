package com.wain.app.data.routing

import com.wain.app.core.geo.GeoMath
import com.wain.app.core.geo.LatLon
import com.wain.app.core.geo.Polyline
import com.wain.app.data.net.HttpFailure
import com.wain.app.data.net.WainJson
import com.wain.app.data.net.fetch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class RoutingException(message: String, val code: Int? = null) : Exception(message)

data class Maneuver(
    val type: Int,
    /** English instruction text as returned by the routing engine (used only as a fallback). */
    val instruction: String,
    val streetNames: List<String>,
    val lengthM: Double,
    val timeS: Double,
    val beginIndex: Int,
    val roundaboutExit: Int?,
)

class Route(
    val id: String,
    val points: List<LatLon>,
    val distanceM: Double,
    val durationS: Double,
    val maneuvers: List<Maneuver>,
) {
    /** Cumulative distance along the polyline at each point, in metres. */
    val cumulativeM: DoubleArray by lazy {
        val c = DoubleArray(points.size)
        for (i in 1 until points.size) c[i] = c[i - 1] + GeoMath.distanceM(points[i - 1], points[i])
        c
    }
    val destination: LatLon get() = points.last()
}

interface Router {
    suspend fun route(origin: LatLon, destination: LatLon, headingDeg: Double?): List<Route>
}

object ValhallaParser {
    fun buildRequest(origin: LatLon, destination: LatLon, headingDeg: Double?, alternates: Int): String =
        buildJsonObject {
            put("locations", buildJsonArray {
                add(buildJsonObject {
                    put("lat", origin.lat)
                    put("lon", origin.lon)
                    put("type", "break")
                    if (headingDeg != null) {
                        put("heading", headingDeg.toInt())
                        put("heading_tolerance", 60)
                    }
                })
                add(buildJsonObject {
                    put("lat", destination.lat)
                    put("lon", destination.lon)
                    put("type", "break")
                })
            })
            put("costing", "auto")
            put("directions_options", buildJsonObject {
                put("units", "kilometers")
                put("language", "en-US")
            })
            if (alternates > 0) put("alternates", alternates)
        }.toString()

    fun parse(body: String): List<Route> {
        val root = WainJson.parseToJsonElement(body).jsonObject
        root["error"]?.let {
            throw RoutingException(it.jsonPrimitive.contentOrNull ?: "Routing error", root["error_code"]?.jsonPrimitive?.intOrNull)
        }
        val trips = buildList {
            root["trip"]?.jsonObject?.let { add(it) }
            root["alternates"]?.jsonArray?.forEach { a -> a.jsonObject["trip"]?.jsonObject?.let { add(it) } }
        }
        if (trips.isEmpty()) throw RoutingException("The routing service returned no route")
        return trips.mapIndexed { i, t -> parseTrip(t, "route-$i") }
    }

    private fun JsonObject.dbl(key: String): Double? = this[key]?.jsonPrimitive?.doubleOrNull
    private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull

    private fun parseTrip(trip: JsonObject, id: String): Route {
        val points = ArrayList<LatLon>()
        val maneuvers = ArrayList<Maneuver>()
        val legs: JsonArray = trip["legs"]?.jsonArray ?: throw RoutingException("Route has no legs")
        for (leg in legs) {
            val lo = leg.jsonObject
            val shape = Polyline.decode(lo["shape"]?.jsonPrimitive?.contentOrNull ?: "", 6)
            val offset = if (points.isEmpty()) 0 else points.size - 1
            if (points.isEmpty()) points.addAll(shape) else points.addAll(shape.drop(1))
            lo["maneuvers"]?.jsonArray?.forEach { m ->
                val o = m.jsonObject
                maneuvers.add(
                    Maneuver(
                        type = o.int("type") ?: 0,
                        instruction = o["instruction"]?.jsonPrimitive?.contentOrNull ?: "",
                        streetNames = o["street_names"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
                        lengthM = (o.dbl("length") ?: 0.0) * 1000.0,
                        timeS = o.dbl("time") ?: 0.0,
                        beginIndex = ((o.int("begin_shape_index") ?: 0) + offset).coerceIn(0, maxOf(0, points.size - 1)),
                        roundaboutExit = o.int("roundabout_exit_count"),
                    )
                )
            }
        }
        if (points.size < 2) throw RoutingException("Route geometry is empty")
        val summary = trip["summary"]?.jsonObject
        val distanceM = (summary?.dbl("length"))?.times(1000.0)
            ?: points.zipWithNext { a, b -> GeoMath.distanceM(a, b) }.sum()
        val durationS = summary?.dbl("time") ?: maneuvers.sumOf { it.timeS }
        return Route(id, points, distanceM, durationS, maneuvers)
    }
}

/** Valhalla-compatible routing client (self-hosted, or any hosted Valhalla API). */
class ValhallaRouter(
    private val http: OkHttpClient,
    private val baseUrl: String,
    private val apiKey: String,
) : Router {
    override suspend fun route(origin: LatLon, destination: LatLon, headingDeg: Double?): List<Route> {
        val url = "$baseUrl/route".toHttpUrl().newBuilder().apply {
            if (apiKey.isNotBlank()) addQueryParameter("api_key", apiKey)
        }.build()
        val body = ValhallaParser.buildRequest(origin, destination, headingDeg, alternates = 2)
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(url).post(body).build()
        val text = try {
            http.fetch(request)
        } catch (e: HttpFailure) {
            val msg = runCatching {
                WainJson.parseToJsonElement(e.body).jsonObject["error"]?.jsonPrimitive?.contentOrNull
            }.getOrNull()
            throw RoutingException(msg ?: "Routing service error (HTTP ${e.code})", e.code)
        }
        return ValhallaParser.parse(text)
    }
}
