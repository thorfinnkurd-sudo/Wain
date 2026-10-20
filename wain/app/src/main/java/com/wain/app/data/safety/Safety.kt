package com.wain.app.data.safety

import com.wain.app.core.geo.BoundingBox
import com.wain.app.core.geo.LatLon
import com.wain.app.core.geo.Tiles
import com.wain.app.data.overpass.OverpassClient
import com.wain.app.data.overpass.OverpassParsers
import com.wain.app.data.overpass.OverpassQueries
import com.wain.app.data.tiles.TileLoader

enum class SafetyKind { SPEED_CAMERA, SPEED_BUMP }

/** Operational status of a speed camera. OpenStreetMap carries no such status, so mapped cameras are UNKNOWN. */
enum class CameraStatus { VERIFIED_OPERATIONAL, REPORTED_OPERATIONAL, REPORTED_INACTIVE, UNKNOWN }

data class SafetyFeature(
    val id: String,
    val position: LatLon,
    val kind: SafetyKind,
    val maxspeedRaw: String?,
    val direction: String?,
    val lastCheckedOsm: String?,
    val source: String = "OpenStreetMap",
) {
    /** Presence on the map never proves a camera is working or enforcing a limit. */
    val cameraStatus: CameraStatus get() = CameraStatus.UNKNOWN
}

enum class SafetyStatus { HIDDEN_ZOOM, TOO_WIDE, READY, PARTIAL }

data class SafetyLoad(val status: SafetyStatus, val features: List<SafetyFeature>)

/** Loads mapped speed cameras and speed bumps/humps from OpenStreetMap (no data is invented). */
class SafetyRepository(private val overpass: OverpassClient) {
    private val loader = TileLoader<SafetyFeature> { tile, _ ->
        OverpassParsers.toSafetyFeatures(overpass.run(OverpassQueries.safety(Tiles.bounds(tile), PER_TILE_LIMIT)))
    }

    suspend fun load(bounds: BoundingBox, zoom: Double, allowNetwork: Boolean): SafetyLoad {
        if (zoom < MIN_ZOOM) return SafetyLoad(SafetyStatus.HIDDEN_ZOOM, emptyList())
        val tiles = Tiles.covering(bounds, TILE_Z)
        if (tiles.size > 9) return SafetyLoad(SafetyStatus.TOO_WIDE, emptyList())
        val loaded = loader.load(tiles, 1, allowNetwork)
        val items = loaded.items.distinctBy { it.id }.filter { bounds.contains(it.position) }
        return SafetyLoad(if (loaded.partial) SafetyStatus.PARTIAL else SafetyStatus.READY, items)
    }

    companion object {
        const val MIN_ZOOM = 14.0
        const val TILE_Z = 13
        const val PER_TILE_LIMIT = 800
    }
}

/** Road-speed-limit lookup around the vehicle, throttled by distance and time. */
class SpeedLimitRepository(private val overpass: OverpassClient) {
    suspend fun waysAround(p: LatLon) =
        OverpassParsers.toWays(overpass.run(OverpassQueries.waysAround(p, 35)))
}
