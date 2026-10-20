package com.wain.app.data.search

import com.wain.app.core.geo.LatLon
import com.wain.app.data.net.WainJson
import com.wain.app.data.net.fetch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

data class Place(
    val id: String,
    val name: String?,
    val address: String?,
    val position: LatLon,
    val category: String?,
    val source: String,
)

interface Geocoder {
    suspend fun search(query: String, lang: String): List<Place>
    suspend fun reverse(p: LatLon, lang: String): Place?
}

@Serializable
private data class NominatimItem(
    @SerialName("place_id") val placeId: Long = 0,
    @SerialName("osm_type") val osmType: String? = null,
    @SerialName("osm_id") val osmId: Long? = null,
    val lat: String? = null,
    val lon: String? = null,
    val name: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    val category: String? = null,
    val type: String? = null,
    val error: String? = null,
)

/**
 * Nominatim-compatible geocoder. The public nominatim.openstreetmap.org instance forbids autocomplete and
 * requires a real User-Agent, so the app searches on submit only. Use your own instance or a commercial
 * provider for production (WAIN_GEOCODER_URL).
 */
class NominatimGeocoder(
    private val http: OkHttpClient,
    private val baseUrl: String,
    private val countryCodes: String = "iq",
) : Geocoder {

    override suspend fun search(query: String, lang: String): List<Place> {
        val url = "$baseUrl/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("format", "jsonv2")
            .addQueryParameter("limit", "8")
            .addQueryParameter("accept-language", lang)
            .addQueryParameter("countrycodes", countryCodes)
            .build()
        val body = http.fetch(Request.Builder().url(url).build())
        return WainJson.decodeFromString(ListSerializer(NominatimItem.serializer()), body).mapNotNull { it.toPlace() }
    }

    override suspend fun reverse(p: LatLon, lang: String): Place? {
        val url = "$baseUrl/reverse".toHttpUrl().newBuilder()
            .addQueryParameter("lat", p.lat.toString())
            .addQueryParameter("lon", p.lon.toString())
            .addQueryParameter("format", "jsonv2")
            .addQueryParameter("zoom", "18")
            .addQueryParameter("accept-language", lang)
            .build()
        val body = http.fetch(Request.Builder().url(url).build())
        val item = WainJson.decodeFromString(NominatimItem.serializer(), body)
        if (item.error != null) return null
        return item.toPlace()
    }

    private fun NominatimItem.toPlace(): Place? {
        val la = lat?.toDoubleOrNull() ?: return null
        val lo = lon?.toDoubleOrNull() ?: return null
        val p = LatLon(la, lo)
        if (!p.isValid()) return null
        return Place(
            id = "${osmType ?: "place"}/${osmId ?: placeId}",
            name = name?.ifBlank { null },
            address = displayName,
            position = p,
            category = type?.takeIf { it != "yes" },
            source = "OpenStreetMap / Nominatim",
        )
    }
}
