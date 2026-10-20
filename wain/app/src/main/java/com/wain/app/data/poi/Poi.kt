package com.wain.app.data.poi

import com.wain.app.core.geo.BoundingBox
import com.wain.app.core.geo.LatLon
import com.wain.app.core.geo.TileKey
import com.wain.app.core.geo.Tiles
import com.wain.app.data.overpass.OverpassClient
import com.wain.app.data.overpass.OverpassParsers
import com.wain.app.data.overpass.OverpassQueries
import com.wain.app.data.tiles.TileLoader
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToLong

/** tier 1 = major destinations (city zoom), 2 = neighbourhood zoom, 3 = street-level detail. */
enum class PoiCategory(
    val id: String,
    val tier: Int,
    val colorHex: String,
    val osm: List<Pair<String, String>>,
) {
    HOSPITAL("hospital", 1, "#D32F2F", listOf("amenity" to "hospital")),
    MALL("mall", 1, "#7B1FA2", listOf("shop" to "mall", "shop" to "department_store")),
    FUEL("fuel", 1, "#F57C00", listOf("amenity" to "fuel")),
    UNIVERSITY("university", 1, "#5D4037", listOf("amenity" to "university")),
    TRANSPORT("transport", 1, "#455A64", listOf("amenity" to "bus_station")),
    ATTRACTION("attraction", 1, "#00897B", listOf("tourism" to "attraction", "tourism" to "museum")),
    HOTEL("hotel", 1, "#8D6E63", listOf("tourism" to "hotel")),

    SUPERMARKET("supermarket", 2, "#2E7D32", listOf("shop" to "supermarket")),
    PHARMACY("pharmacy", 2, "#00A86B", listOf("amenity" to "pharmacy")),
    RESTAURANT("restaurant", 2, "#E65100", listOf("amenity" to "restaurant", "amenity" to "fast_food")),
    CAFE("cafe", 2, "#6D4C41", listOf("amenity" to "cafe")),
    BANK("bank", 2, "#1565C0", listOf("amenity" to "bank")),
    CLINIC("clinic", 2, "#E53935", listOf("amenity" to "clinic")),
    PARK("park", 2, "#43A047", listOf("leisure" to "park")),
    WORSHIP("worship", 2, "#00695C", listOf("amenity" to "place_of_worship")),
    SCHOOL("school", 2, "#3949AB", listOf("amenity" to "school")),
    CHARGING("charging", 2, "#00ACC1", listOf("amenity" to "charging_station")),
    GOVERNMENT("government", 2, "#37474F", listOf("amenity" to "townhall")),

    CONVENIENCE("convenience", 3, "#558B2F", listOf("shop" to "convenience", "shop" to "greengrocer", "shop" to "butcher")),
    BAKERY("bakery", 3, "#A1887F", listOf("shop" to "bakery")),
    ATM("atm", 3, "#1976D2", listOf("amenity" to "atm")),
    ELECTRONICS("electronics", 3, "#5E35B1", listOf("shop" to "electronics")),
    PHONE_SHOP("phone_shop", 3, "#8E24AA", listOf("shop" to "mobile_phone")),
    CAR_REPAIR("car_repair", 3, "#546E7A", listOf("shop" to "car_repair")),
    CAR_WASH("car_wash", 3, "#039BE5", listOf("amenity" to "car_wash")),
    PARKING("parking", 3, "#607D8B", listOf("amenity" to "parking")),
    DOCTOR("doctor", 3, "#EF5350", listOf("amenity" to "doctors")),
    TOILETS("toilets", 3, "#78909C", listOf("amenity" to "toilets"));

    companion object {
        fun classify(tags: Map<String, String>): PoiCategory? =
            entries.firstOrNull { c -> c.osm.any { (k, v) -> tags[k] == v } }

        fun fromId(id: String?): PoiCategory? = entries.firstOrNull { it.id == id }
    }
}

data class Poi(
    val id: String,
    val position: LatLon,
    val category: PoiCategory,
    /** Keys: "default", "ar", "en", "ckb" — taken straight from OSM name tags, never invented. */
    val names: Map<String, String>,
    val address: String?,
    val phone: String?,
    val openingHours: String?,
) {
    fun displayName(lang: String): String? = names[lang] ?: names["default"] ?: names.values.firstOrNull()
}

object PoiVisibility {
    /** Highest category tier that may be shown at [zoom] (MapLibre zoom, 512px tiles). 0 = none. */
    fun maxTier(zoom: Double): Int = when {
        zoom < 12.0 -> 0
        zoom < 14.0 -> 1
        zoom < 16.0 -> 2
        else -> 3
    }

    fun categoriesFor(tier: Int): List<PoiCategory> = PoiCategory.entries.filter { it.tier <= tier }

    /** Tile zoom used for fetching; chosen so a phone viewport covers at most ~6 tiles at any map zoom. */
    fun fetchTileZoom(zoom: Double): Int = when {
        zoom < 14.0 -> 11
        zoom < 16.0 -> 13
        else -> 15
    }
}

object PoiDeclutter {
    /**
     * Density- and screen-space-aware selection: below street zoom only the highest-priority POI per
     * ~[cellPx] screen cell survives, so dense areas never turn into hundreds of overlapping icons.
     * Exact duplicates (same category, name and ~10 m position) are always dropped.
     */
    fun select(pois: List<Poi>, zoom: Double, maxCount: Int, cellPx: Int = 56): List<Poi> {
        val ordered = pois.sortedWith(compareBy<Poi>({ it.category.tier }, { it.id }))
        val deduped = ordered.distinctBy { p ->
            val name = p.names["default"]
            if (name == null) p.id
            else "${p.category.id}|$name|${(p.position.lat * 1e4).roundToLong()}|${(p.position.lon * 1e4).roundToLong()}"
        }
        if (zoom >= 17.5) return deduped.take(maxCount)
        val cellDeg = 360.0 / (512.0 * 2.0.pow(zoom)) * cellPx
        val taken = LinkedHashMap<Pair<Long, Long>, Poi>()
        for (p in deduped) {
            val key = floor(p.position.lon / cellDeg).toLong() to floor(p.position.lat / cellDeg).toLong()
            if (key !in taken) taken[key] = p
        }
        return taken.values.take(maxCount)
    }
}

enum class PoiStatus { HIDDEN_ZOOM, TOO_WIDE, READY, PARTIAL }

data class PoiLoad(val status: PoiStatus, val pois: List<Poi>)

class PoiRepository(private val overpass: OverpassClient) {
    private val loader = TileLoader<Poi> { tile, level ->
        val q = OverpassQueries.pois(PoiVisibility.categoriesFor(level), Tiles.bounds(tile), PER_TILE_LIMIT)
        OverpassParsers.toPois(overpass.run(q))
    }

    suspend fun load(bounds: BoundingBox, zoom: Double, allowNetwork: Boolean): PoiLoad {
        val tier = PoiVisibility.maxTier(zoom)
        if (tier == 0) return PoiLoad(PoiStatus.HIDDEN_ZOOM, emptyList())
        val tiles: List<TileKey> = Tiles.covering(bounds, PoiVisibility.fetchTileZoom(zoom))
        if (tiles.size > MAX_TILES) return PoiLoad(PoiStatus.TOO_WIDE, emptyList())
        val loaded = loader.load(tiles, tier, allowNetwork)
        val visible = loaded.items
            .asSequence()
            .distinctBy { it.id }
            .filter { it.category.tier <= tier && bounds.contains(it.position) }
            .toList()
        val chosen = PoiDeclutter.select(visible, zoom, MAX_ON_SCREEN)
        return PoiLoad(if (loaded.partial) PoiStatus.PARTIAL else PoiStatus.READY, chosen)
    }

    companion object {
        const val MAX_TILES = 9
        const val PER_TILE_LIMIT = 700
        const val MAX_ON_SCREEN = 250
    }
}
