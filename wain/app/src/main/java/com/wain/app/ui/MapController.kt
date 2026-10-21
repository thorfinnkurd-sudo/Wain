package com.wain.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.wain.app.core.geo.BoundingBox
import com.wain.app.core.geo.LatLon
import com.wain.app.core.location.LocationFix
import com.wain.app.data.poi.Poi
import com.wain.app.data.reports.Report
import com.wain.app.data.routing.Route
import com.wain.app.data.safety.SafetyFeature
import com.wain.app.data.safety.SafetyKind
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import kotlin.math.cos
import kotlin.math.pow

sealed interface CameraRequest {
    data class FlyTo(val position: LatLon, val zoom: Double) : CameraRequest
    data class Fit(val points: List<LatLon>) : CameraRequest
}

/**
 * MapLibre controller.
 * Handles map styles, overlays, camera and user location.
 */
class MapController(
    private val mapView: MapView,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun onCameraIdle(bounds: BoundingBox, zoom: Double, center: LatLon)
        fun onUserGesture()
        fun onMapTap(p: LatLon)
        fun onMapLongPress(p: LatLon): Boolean
        fun onFeatureTap(kind: String, id: String): Boolean
        fun onStyleLoaded(hasBuildings: Boolean)
        fun onMapError(message: String)
    }

    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var requestedStyle: String? = null
    private var appliedStyle: String? = null
    private var styleLoadGeneration = 0

    private var pois: List<Poi> = emptyList()
    private var poiLang = "ar"
    private var safety: List<SafetyFeature> = emptyList()
    private var reports: List<Report> = emptyList()
    private var routes: List<Route> = emptyList()
    private var selectedRoute = 0
    private var selectedPoint: LatLon? = null
    private var fix: LocationFix? = null
    private var heading: Float? = null
    private var tilt3d = false

    init {
        mapView.getMapAsync { m ->
            map = m

            m.uiSettings.apply {
                isCompassEnabled = true
                isRotateGesturesEnabled = true
                isTiltGesturesEnabled = true
                isAttributionEnabled = true
                isLogoEnabled = true
            }

            m.cameraPosition = CameraPosition.Builder()
                .target(LatLng(33.2, 43.7))
                .zoom(5.2)
                .build()

            m.addOnCameraIdleListener {
                val bounds = m.projection.visibleRegion.latLngBounds
                val target = m.cameraPosition.target
                    ?: return@addOnCameraIdleListener

                callbacks.onCameraIdle(
                    BoundingBox(
                        bounds.latitudeSouth,
                        bounds.longitudeWest,
                        bounds.latitudeNorth,
                        bounds.longitudeEast,
                    ),
                    m.cameraPosition.zoom,
                    LatLon(target.latitude, target.longitude),
                )
            }

            m.addOnCameraMoveStartedListener { reason ->
                if (
                    reason ==
                    MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE
                ) {
                    callbacks.onUserGesture()
                }
            }

            m.addOnMapClickListener { latLng ->
                handleTap(m, latLng)
            }

            m.addOnMapLongClickListener { latLng ->
                callbacks.onMapLongPress(
                    LatLon(latLng.latitude, latLng.longitude)
                )
            }

            requestedStyle?.let { applyStyle(it, force = true) }
        }

        mapView.addOnDidFailLoadingMapListener { message ->
            callbacks.onMapError("Map loading failed: $message")
        }
    }

    // ---- Style loading -------------------------------------------------------

    fun setStyle(url: String) {
        requestedStyle = url

        if (map != null && (url != appliedStyle || style == null)) {
            applyStyle(url, force = true)
        }
    }

    fun reloadStyle() {
        val url = requestedStyle ?: appliedStyle
        if (url != null && map != null) {
            applyStyle(url, force = true)
        }
    }

    private fun applyStyle(url: String, force: Boolean = false) {
        val m = map ?: return

        if (!force && url == appliedStyle && style != null) return

        requestedStyle = url
        appliedStyle = url
        style = null

        val generation = ++styleLoadGeneration

        try {
            m.setStyle(Style.Builder().fromUri(url)) { loadedStyle ->
                if (generation != styleLoadGeneration) {
                    return@setStyle
                }

                style = loadedStyle

                try {
                    installLayers(loadedStyle)
                    callbacks.onStyleLoaded(
                        findBuildingLayer(loadedStyle) != null
                    )
                    pushAll()
                    apply3d()
                } catch (e: Exception) {
                    style = null
                    callbacks.onMapError(
                        "Map style loaded, but layer setup failed: " +
                            (e.message ?: e.javaClass.simpleName)
                    )
                }
            }
        } catch (e: Exception) {
            style = null
            callbacks.onMapError(
                "Could not start map style loading: " +
                    (e.message ?: e.javaClass.simpleName)
            )
        }
    }

    private fun installLayers(s: Style) {
        s.addImage(IMG_ARROW, arrowBitmap())

        val empty = FeatureCollection.fromFeatures(
            ArrayList<Feature>()
        )

        listOf(
            SRC_ROUTE_ALT,
            SRC_ROUTE,
            SRC_SAFETY,
            SRC_REPORTS,
            SRC_POI,
            SRC_SELECTED,
            SRC_ACCURACY,
            SRC_POSITION,
        ).forEach { id ->
            if (s.getSource(id) == null) {
                s.addSource(GeoJsonSource(id, empty))
            }
        }

        s.addLayer(
            LineLayer(LYR_ROUTE_ALT, SRC_ROUTE_ALT).withProperties(
                PropertyFactory.lineColor("#8A97AD"),
                PropertyFactory.lineWidth(6f),
                PropertyFactory.lineOpacity(0.85f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            )
        )

        s.addLayer(
            LineLayer(LYR_ROUTE_CASING, SRC_ROUTE).withProperties(
                PropertyFactory.lineColor("#0B2A66"),
                PropertyFactory.lineWidth(11f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            )
        )

        s.addLayer(
            LineLayer(LYR_ROUTE, SRC_ROUTE).withProperties(
                PropertyFactory.lineColor("#3D8BFF"),
                PropertyFactory.lineWidth(7f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            )
        )

        s.addLayer(circleLayer(LYR_SAFETY, SRC_SAFETY, 7f))
        s.addLayer(circleLayer(LYR_REPORTS, SRC_REPORTS, 9f))

        s.addLayer(
            CircleLayer(LYR_POI, SRC_POI).withProperties(
                PropertyFactory.circleRadius(
                    Expression.interpolate(
                        Expression.linear(),
                        Expression.zoom(),
                        Expression.stop(11, 3f),
                        Expression.stop(18, 8f),
                    )
                ),
                PropertyFactory.circleColor(
                    Expression.toColor(Expression.get("color"))
                ),
                PropertyFactory.circleStrokeWidth(1.5f),
                PropertyFactory.circleStrokeColor("#FFFFFF"),
            )
        )

        val labels = SymbolLayer(LYR_POI_LABEL, SRC_POI).withProperties(
            PropertyFactory.textField(Expression.get("label")),
            PropertyFactory.textSize(12f),
            PropertyFactory.textOffset(arrayOf(0f, 1.1f)),
            PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
            PropertyFactory.textColor("#1B1F2A"),
            PropertyFactory.textHaloColor("#FFFFFF"),
            PropertyFactory.textHaloWidth(1.5f),
            PropertyFactory.textOptional(true),
        )
        labels.setMinZoom(15f)
        s.addLayer(labels)

        s.addLayer(
            CircleLayer(LYR_SELECTED, SRC_SELECTED).withProperties(
                PropertyFactory.circleRadius(10f),
                PropertyFactory.circleColor("#F2B01E"),
                PropertyFactory.circleStrokeWidth(3f),
                PropertyFactory.circleStrokeColor("#17408B"),
            )
        )

        s.addLayer(
            CircleLayer(LYR_ACCURACY, SRC_ACCURACY).withProperties(
                PropertyFactory.circleColor("#3D8BFF"),
                PropertyFactory.circleOpacity(0.15f),
                PropertyFactory.circleStrokeColor("#3D8BFF"),
                PropertyFactory.circleStrokeWidth(1f),
                PropertyFactory.circlePitchAlignment(
                    Property.CIRCLE_PITCH_ALIGNMENT_MAP
                ),
                PropertyFactory.circleRadius(
                    Expression.interpolate(
                        Expression.exponential(2f),
                        Expression.zoom(),
                        Expression.stop(0, Expression.get("r0")),
                        Expression.stop(22, Expression.get("r22")),
                    )
                ),
            )
        )

        // Outer soft green glow.
        val glowOuter = CircleLayer(
            LYR_POS_GLOW_OUTER,
            SRC_POSITION,
        ).withProperties(
            PropertyFactory.circleRadius(30f),
            PropertyFactory.circleColor("#20C968"),
            PropertyFactory.circleOpacity(0.14f),
            PropertyFactory.circleBlur(0.9f),
        )
        s.addLayer(glowOuter)

        // Inner glow for a brighter, more polished marker.
        val glowInner = CircleLayer(
            LYR_POS_GLOW_INNER,
            SRC_POSITION,
        ).withProperties(
            PropertyFactory.circleRadius(21f),
            PropertyFactory.circleColor("#20C968"),
            PropertyFactory.circleOpacity(0.27f),
            PropertyFactory.circleBlur(0.65f),
        )
        s.addLayer(glowInner)

        // Large green position dot with a clean white outline.
        val dot = CircleLayer(
            LYR_POS_DOT,
            SRC_POSITION,
        ).withProperties(
            PropertyFactory.circleRadius(14f),
            PropertyFactory.circleColor("#20C968"),
            PropertyFactory.circleStrokeWidth(4f),
            PropertyFactory.circleStrokeColor("#FFFFFF"),
        )
        dot.setFilter(
            Expression.eq(
                Expression.get("hb"),
                Expression.literal(0),
            )
        )
        s.addLayer(dot)

        // Direction arrow shown when a heading is available.
        val arrow = SymbolLayer(
            LYR_POS_ARROW,
            SRC_POSITION,
        ).withProperties(
            PropertyFactory.iconImage(IMG_ARROW),
            PropertyFactory.iconSize(0.85f),
            PropertyFactory.iconRotate(Expression.get("bearing")),
            PropertyFactory.iconRotationAlignment(
                Property.ICON_ROTATION_ALIGNMENT_MAP
            ),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
        )
        arrow.setFilter(
            Expression.eq(
                Expression.get("hb"),
                Expression.literal(1),
            )
        )
        s.addLayer(arrow)
    }

    private fun circleLayer(
        id: String,
        source: String,
        radius: Float,
    ) = CircleLayer(id, source).withProperties(
        PropertyFactory.circleRadius(radius),
        PropertyFactory.circleColor(
            Expression.toColor(Expression.get("color"))
        ),
        PropertyFactory.circleStrokeWidth(2f),
        PropertyFactory.circleStrokeColor("#FFFFFF"),
    )

    private fun findBuildingLayer(s: Style): FillLayer? =
        s.layers.filterIsInstance<FillLayer>()
            .firstOrNull { it.sourceLayer == "building" }

    // ---- Data ----------------------------------------------------------------

    fun setPois(list: List<Poi>, lang: String) {
        pois = list
        poiLang = lang
        pushPois()
    }

    fun setSafety(list: List<SafetyFeature>) {
        safety = list
        pushSafety()
    }

    fun setReports(list: List<Report>) {
        reports = list
        pushReports()
    }

    fun setRoutes(list: List<Route>, selected: Int) {
        routes = list
        selectedRoute = selected
        pushRoutes()
    }

    fun setSelected(p: LatLon?) {
        selectedPoint = p
        pushSelected()
    }

    fun setPosition(f: LocationFix?, bearing: Float?) {
        fix = f
        heading = bearing
        pushPosition()
    }

    private fun pushAll() {
        pushPois()
        pushSafety()
        pushReports()
        pushRoutes()
        pushSelected()
        pushPosition()
    }

    private fun source(id: String): GeoJsonSource? =
        style?.getSourceAs<GeoJsonSource>(id)

    private fun point(p: LatLon) = Point.fromLngLat(p.lon, p.lat)

    private fun pushPois() {
        val features = pois.map { p ->
            Feature.fromGeometry(point(p.position)).apply {
                addStringProperty("id", p.id)
                addStringProperty("color", p.category.colorHex)
                addStringProperty(
                    "label",
                    p.displayName(poiLang) ?: "",
                )
            }
        }

        source(SRC_POI)?.setGeoJson(
            FeatureCollection.fromFeatures(features)
        )
    }

    private fun pushSafety() {
        val features = safety.map { f ->
            Feature.fromGeometry(point(f.position)).apply {
                addStringProperty("id", f.id)
                addStringProperty(
                    "color",
                    if (f.kind == SafetyKind.SPEED_CAMERA) {
                        "#455A64"
                    } else {
                        "#FB8C00"
                    },
                )
            }
        }

        source(SRC_SAFETY)?.setGeoJson(
            FeatureCollection.fromFeatures(features)
        )
    }

    private fun pushReports() {
        val features = reports.map { r ->
            Feature.fromGeometry(point(r.position)).apply {
                addStringProperty("id", r.id)
                addStringProperty("color", r.type.colorHex)
            }
        }

        source(SRC_REPORTS)?.setGeoJson(
            FeatureCollection.fromFeatures(features)
        )
    }

    private fun pushRoutes() {
        fun line(route: Route) =
            Feature.fromGeometry(
                LineString.fromLngLats(
                    route.points.map { point(it) }
                )
            )

        val selected = routes.getOrNull(selectedRoute)

        source(SRC_ROUTE)?.setGeoJson(
            FeatureCollection.fromFeatures(
                listOfNotNull(selected?.let { line(it) })
            )
        )

        source(SRC_ROUTE_ALT)?.setGeoJson(
            FeatureCollection.fromFeatures(
                routes.filterIndexed { index, _ ->
                    index != selectedRoute
                }.map { line(it) }
            )
        )
    }

    private fun pushSelected() {
        source(SRC_SELECTED)?.setGeoJson(
            FeatureCollection.fromFeatures(
                listOfNotNull(
                    selectedPoint?.let {
                        Feature.fromGeometry(point(it))
                    }
                )
            )
        )
    }

    private fun pushPosition() {
        val currentFix = fix

        if (currentFix == null) {
            source(SRC_POSITION)?.setGeoJson(
                FeatureCollection.fromFeatures(ArrayList<Feature>())
            )
            source(SRC_ACCURACY)?.setGeoJson(
                FeatureCollection.fromFeatures(ArrayList<Feature>())
            )
            return
        }

        val positionFeature =
            Feature.fromGeometry(point(currentFix.position)).apply {
                addNumberProperty(
                    "hb",
                    if (heading != null) 1 else 0,
                )
                addNumberProperty(
                    "bearing",
                    (heading ?: 0f).toDouble(),
                )
            }

        source(SRC_POSITION)?.setGeoJson(
            FeatureCollection.fromFeatures(listOf(positionFeature))
        )

        val metresPerPixelAtZoom22 =
            78271.52 * cos(Math.toRadians(currentFix.position.lat)) /
                2.0.pow(22)

        val radiusAtZoom22 =
            (currentFix.accuracyM / metresPerPixelAtZoom22)
                .coerceAtMost(1.0e7)

        val accuracyFeature =
            Feature.fromGeometry(point(currentFix.position)).apply {
                addNumberProperty("r22", radiusAtZoom22)
                addNumberProperty(
                    "r0",
                    radiusAtZoom22 / 2.0.pow(22),
                )
            }

        source(SRC_ACCURACY)?.setGeoJson(
            FeatureCollection.fromFeatures(listOf(accuracyFeature))
        )
    }

    // ---- Camera --------------------------------------------------------------

    fun set3d(enabled: Boolean) {
        tilt3d = enabled
        apply3d()

        val m = map ?: return
        val camera = m.cameraPosition
        val target = camera.target ?: return

        m.easeCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(target)
                    .zoom(camera.zoom)
                    .bearing(camera.bearing)
                    .tilt(if (enabled) 60.0 else 0.0)
                    .build()
            ),
            700,
        )
    }

    private fun apply3d() {
        val currentStyle = style ?: return

        currentStyle.getLayer(LYR_3D)?.let {
            currentStyle.removeLayer(it)
        }

        if (!tilt3d) return

        val base = findBuildingLayer(currentStyle) ?: return

        val layer = FillExtrusionLayer(
            LYR_3D,
            base.sourceId,
        ).withSourceLayer("building").withProperties(
            PropertyFactory.fillExtrusionColor("#C7CCD6"),
            PropertyFactory.fillExtrusionHeight(
                Expression.get("render_height")
            ),
            PropertyFactory.fillExtrusionBase(
                Expression.get("render_min_height")
            ),
            PropertyFactory.fillExtrusionOpacity(0.85f),
        )

        layer.setMinZoom(15f)
        currentStyle.addLayerBelow(layer, LYR_ROUTE_ALT)
    }

    fun follow(
        position: LatLon,
        bearing: Float?,
        zoom: Double,
        tiltDeg: Double,
        durationMs: Int,
    ) {
        val m = map ?: return

        val camera = CameraPosition.Builder()
            .target(LatLng(position.lat, position.lon))
            .zoom(zoom)
            .tilt(tiltDeg)

        camera.bearing(
            bearing?.toDouble() ?: m.cameraPosition.bearing
        )

        m.easeCamera(
            CameraUpdateFactory.newCameraPosition(camera.build()),
            durationMs,
        )
    }

    fun setNavigationPadding(enabled: Boolean) {
        val m = map ?: return
        val height = mapView.height

        if (enabled) {
            m.setPadding(0, (height * 0.35).toInt(), 0, 0)
        } else {
            m.setPadding(0, 0, 0, 0)
        }
    }

    fun handle(request: CameraRequest) {
        val m = map ?: return

        when (request) {
            is CameraRequest.FlyTo -> {
                m.animateCamera(
                    CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder()
                            .target(
                                LatLng(
                                    request.position.lat,
                                    request.position.lon,
                                )
                            )
                            .zoom(request.zoom)
                            .bearing(0.0)
                            .tilt(if (tilt3d) 60.0 else 0.0)
                            .build()
                    ),
                    800,
                )
            }

            is CameraRequest.Fit -> {
                if (request.points.size < 2) return

                val bounds = LatLngBounds.Builder()
                    .includes(
                        request.points.map {
                            LatLng(it.lat, it.lon)
                        }
                    )
                    .build()

                val padding =
                    (mapView.resources.displayMetrics.density * 48).toInt()

                m.animateCamera(
                    CameraUpdateFactory.newLatLngBounds(
                        bounds,
                        padding,
                        padding * 3,
                        padding,
                        padding * 5,
                    ),
                    900,
                )
            }
        }
    }

    // ---- Input ----------------------------------------------------------------

    private fun handleTap(
        m: MapLibreMap,
        latLng: LatLng,
    ): Boolean {
        if (style == null) return false

        val pixel = m.projection.toScreenLocation(latLng)
        val rect = RectF(
            pixel.x - 28f,
            pixel.y - 28f,
            pixel.x + 28f,
            pixel.y + 28f,
        )

        for (
            (layer, kind) in listOf(
                LYR_SAFETY to "safety",
                LYR_REPORTS to "report",
                LYR_POI to "poi",
            )
        ) {
            val feature = m.queryRenderedFeatures(rect, layer)
                .firstOrNull() ?: continue

            val id = feature.getStringProperty("id") ?: continue

            if (callbacks.onFeatureTap(kind, id)) return true
        }

        callbacks.onMapTap(
            LatLon(latLng.latitude, latLng.longitude)
        )
        return true
    }

    private fun arrowBitmap(): Bitmap {
        val size = 128
        val bitmap = Bitmap.createBitmap(
            size,
            size,
            Bitmap.Config.ARGB_8888,
        )

        val canvas = Canvas(bitmap)
        val path = Path().apply {
            moveTo(64f, 8f)
            cubicTo(68f, 8f, 71f, 12f, 73f, 17f)
            lineTo(116f, 108f)
            cubicTo(120f, 117f, 113f, 122f, 105f, 117f)
            lineTo(64f, 91f)
            lineTo(23f, 117f)
            cubicTo(15f, 122f, 8f, 117f, 12f, 108f)
            lineTo(55f, 17f)
            cubicTo(57f, 12f, 60f, 8f, 64f, 8f)
            close()
        }

        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 9f
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#20C968")
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        canvas.drawPath(path, stroke)
        canvas.drawPath(path, fill)

        return bitmap
    }

    companion object {
        private const val IMG_ARROW = "wain-arrow"

        private const val SRC_ROUTE_ALT = "src-route-alt"
        private const val SRC_ROUTE = "src-route"
        private const val SRC_SAFETY = "src-safety"
        private const val SRC_REPORTS = "src-reports"
        private const val SRC_POI = "src-poi"
        private const val SRC_SELECTED = "src-selected"
        private const val SRC_ACCURACY = "src-accuracy"
        private const val SRC_POSITION = "src-position"

        private const val LYR_ROUTE_ALT = "lyr-route-alt"
        private const val LYR_ROUTE_CASING = "lyr-route-casing"
        private const val LYR_ROUTE = "lyr-route"
        private const val LYR_SAFETY = "lyr-safety"
        private const val LYR_REPORTS = "lyr-reports"
        private const val LYR_POI = "lyr-poi"
        private const val LYR_POI_LABEL = "lyr-poi-label"
        private const val LYR_SELECTED = "lyr-selected"
        private const val LYR_ACCURACY = "lyr-accuracy"
        private const val LYR_POS_GLOW_OUTER = "lyr-pos-glow-outer"
        private const val LYR_POS_GLOW_INNER = "lyr-pos-glow-inner"
        private const val LYR_POS_DOT = "lyr-pos-dot"
        private const val LYR_POS_ARROW = "lyr-pos-arrow"
        private const val LYR_3D = "lyr-3d-buildings"
    }
}
