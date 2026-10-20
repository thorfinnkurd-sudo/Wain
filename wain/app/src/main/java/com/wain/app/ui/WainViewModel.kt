package com.wain.app.ui

import android.app.Application
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wain.app.R
import com.wain.app.WainApplication
import com.wain.app.core.geo.BoundingBox
import com.wain.app.core.geo.CoordinateFormat
import com.wain.app.core.geo.CoordinateParser
import com.wain.app.core.geo.GeoMath
import com.wain.app.core.geo.LatLon
import com.wain.app.core.location.FixFilter
import com.wain.app.core.location.FixQuality
import com.wain.app.core.location.LocationFix
import com.wain.app.core.location.LocationQuality
import com.wain.app.core.speed.SpeedEstimator
import com.wain.app.core.speed.SpeedLimitMatcher
import com.wain.app.core.speed.SpeedLimitStatus
import com.wain.app.core.speed.SpeedUnit
import com.wain.app.core.speed.SpeedWarning
import com.wain.app.core.speed.WayGeometry
import com.wain.app.data.poi.Poi
import com.wain.app.data.poi.PoiStatus
import com.wain.app.data.prefs.AppSettings
import com.wain.app.data.prefs.StoredPlace
import com.wain.app.data.reports.Report
import com.wain.app.data.reports.ReportDraft
import com.wain.app.data.reports.ReportPolicy
import com.wain.app.data.reports.ReportSubmitException
import com.wain.app.data.reports.ReportType
import com.wain.app.data.reports.ReportValidation
import com.wain.app.data.reports.RateLimiter
import com.wain.app.data.routing.Maneuver
import com.wain.app.data.routing.Route
import com.wain.app.data.routing.RoutingException
import com.wain.app.data.safety.SafetyFeature
import com.wain.app.data.safety.SafetyKind
import com.wain.app.data.safety.SafetyStatus
import com.wain.app.data.search.Place
import com.wain.app.nav.InstructionFormatter
import com.wain.app.nav.NavigationSession
import com.wain.app.nav.VoiceAnnouncer
import com.wain.app.nav.VoiceGuide
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

enum class MapMode { TWO_D, THREE_D }
enum class RouteError { NETWORK, SERVICE, NEEDS_GPS }

sealed interface SearchState {
    data object Idle : SearchState
    data object Loading : SearchState
    data object Empty : SearchState
    data object Error : SearchState
    data class Results(val places: List<Place>) : SearchState
}

sealed interface RouteState {
    data object Idle : RouteState
    data object Loading : RouteState
    data class Failed(val kind: RouteError, val detail: String?) : RouteState
    data class Ready(val routes: List<Route>, val selected: Int, val destination: Place) : RouteState
}

data class NavState(
    val route: Route,
    val destination: Place,
    val progress: NavigationSession.Progress?,
    val rerouting: Boolean,
    val rerouteFailed: Boolean,
)

sealed interface Selection {
    data class PlaceSel(val place: Place, val poi: Poi? = null) : Selection
    data class ReportSel(val report: Report) : Selection
    data class SafetySel(val feature: SafetyFeature) : Selection
}

data class ProximityWarning(val kind: SafetyKind, val distanceM: Double, val featureId: String)

data class Notice(@StringRes val res: Int, val arg: String?, val id: Long)

data class UiState(
    val language: String = "ar",
    val locationGranted: Boolean = false,
    val fix: LocationFix? = null,
    val quality: FixQuality? = null,
    val speedMps: Double? = null,
    val speedReliable: Boolean = false,
    val heading: Float? = null,
    val speedLimit: SpeedLimitStatus = SpeedLimitStatus.Unknown,
    val overSpeed: Boolean = false,
    val warning: ProximityWarning? = null,
    val mapMode: MapMode = MapMode.TWO_D,
    val hasBuildings: Boolean? = null,
    val follow: Boolean = true,
    val online: Boolean = true,
    val zoom: Double = 5.2,
    val searchOpen: Boolean = false,
    val query: String = "",
    val search: SearchState = SearchState.Idle,
    val selection: Selection? = null,
    val route: RouteState = RouteState.Idle,
    val nav: NavState? = null,
    val pois: List<Poi> = emptyList(),
    val poiStatus: PoiStatus = PoiStatus.HIDDEN_ZOOM,
    val safety: List<SafetyFeature> = emptyList(),
    val safetyStatus: SafetyStatus = SafetyStatus.HIDDEN_ZOOM,
    val reports: List<Report> = emptyList(),
    val reportsShared: Boolean = false,
    val reportSheetOpen: Boolean = false,
    val reportSending: Boolean = false,
    val places: List<StoredPlace> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val settingsOpen: Boolean = false,
    val notice: Notice? = null,
    val mapError: String? = null,
    val voiceMissing: Boolean = false,
)

private data class Viewport(val bounds: BoundingBox, val zoom: Double, val tick: Int = 0)

@OptIn(FlowPreview::class)
class WainViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as WainApplication).container
    private val voice = VoiceGuide(app)

    private val _ui = MutableStateFlow(UiState(reportsShared = container.reports.shared))
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val _camera = MutableSharedFlow<CameraRequest>(extraBufferCapacity = 8)
    val cameraRequests: SharedFlow<CameraRequest> = _camera.asSharedFlow()

    private val filter = FixFilter()
    private val speedEstimator = SpeedEstimator()
    private val announcer = VoiceAnnouncer()
    private val reportLimiter = RateLimiter()
    private var session: NavigationSession? = null
    private var lastFix: LocationFix? = null
    private var lastSpeedAtMs = 0L

    private var trackingJob: Job? = null
    private var routeJob: Job? = null
    private var searchJob: Job? = null
    private var reverseJob: Job? = null
    private var limitJob: Job? = null
    private var limitWays: List<WayGeometry> = emptyList()
    private var limitQueryPos: LatLon? = null
    private var limitQueryAt = 0L
    private var lastRerouteAt = 0L
    private var lastWarningId: String? = null
    private var wasOverSpeed = false
    private var noticeSeq = 0L
    private val viewport = MutableStateFlow<Viewport?>(null)
    private var tone: ToneGenerator? = null

    private val connectivity = app.getSystemService(ConnectivityManager::class.java)
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            _ui.update { it.copy(online = true) }
            viewport.value?.let { v -> viewport.value = v.copy(tick = v.tick + 1) }
        }

        override fun onLost(network: Network) {
            _ui.update { it.copy(online = isOnlineNow()) }
        }
    }

    private val lang: String get() = _ui.value.language

    init {
        _ui.update { it.copy(online = isOnlineNow()) }
        runCatching { connectivity.registerDefaultNetworkCallback(networkCallback) }

        viewModelScope.launch { container.store.settings.collect { s -> _ui.update { it.copy(settings = s) } } }
        viewModelScope.launch { container.store.places.collect { p -> _ui.update { it.copy(places = p) } } }
        viewModelScope.launch {
            viewport.filterNotNull().debounce(400).collectLatest { loadViewportData(it) }
        }
        viewModelScope.launch {
            while (true) {
                delay(1000)
                val f = lastFix ?: continue
                val now = SystemClock.elapsedRealtime()
                val quality = LocationQuality.classify(f, now)
                val speedStale = now - lastSpeedAtMs > SpeedEstimator.STALE_AFTER_MS
                _ui.update {
                    it.copy(quality = quality, speedMps = if (speedStale) null else it.speedMps)
                }
            }
        }
    }

    private fun isOnlineNow(): Boolean {
        val n = connectivity.activeNetwork ?: return false
        return connectivity.getNetworkCapabilities(n)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    // ---- map callbacks ------------------------------------------------------------------------------

    val mapCallbacks = object : MapController.Callbacks {
        override fun onCameraIdle(bounds: BoundingBox, zoom: Double, center: LatLon) {
            _ui.update { it.copy(zoom = zoom) }
            viewport.value = Viewport(bounds, zoom, viewport.value?.tick ?: 0)
        }

        override fun onUserGesture() {
            if (_ui.value.follow) _ui.update { it.copy(follow = false) }
        }

        override fun onMapTap(p: LatLon) {
            if (_ui.value.nav == null) selectPoint(p)
        }

        override fun onMapLongPress(p: LatLon) {
            if (_ui.value.nav == null) selectPoint(p)
        }

        override fun onFeatureTap(kind: String, id: String): Boolean {
            val st = _ui.value
            if (st.nav != null) return true // no dialogs while driving a route
            val sel: Selection = when (kind) {
                "poi" -> st.pois.firstOrNull { it.id == id }?.let { poiSelection(it) }
                "report" -> st.reports.firstOrNull { it.id == id }?.let { Selection.ReportSel(it) }
                "safety" -> st.safety.firstOrNull { it.id == id }?.let { Selection.SafetySel(it) }
                else -> null
            } ?: return false
            _ui.update { it.copy(selection = sel, route = RouteState.Idle, reportSheetOpen = false) }
            return true
        }

        override fun onStyleLoaded(hasBuildings: Boolean) {
            _ui.update { it.copy(hasBuildings = hasBuildings, mapError = null) }
        }

        override fun onMapError(message: String) {
            _ui.update { it.copy(mapError = message) }
        }
    }

    private fun poiSelection(p: Poi) = Selection.PlaceSel(
        Place(p.id, p.displayName(lang), p.address, p.position, p.category.id, "OpenStreetMap"),
        p,
    )

    private suspend fun loadViewportData(v: Viewport) = coroutineScope {
        val online = _ui.value.online
        launch {
            try {
                val r = container.pois.load(v.bounds, v.zoom, online)
                _ui.update { it.copy(pois = r.pois, poiStatus = r.status) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(poiStatus = PoiStatus.PARTIAL) }
            }
        }
        launch {
            try {
                val r = container.safety.load(v.bounds, v.zoom, online)
                _ui.update { it.copy(safety = r.features, safetyStatus = r.status) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(safetyStatus = SafetyStatus.PARTIAL) }
            }
        }
        launch {
            try {
                val r = container.reports.load(v.bounds, System.currentTimeMillis())
                _ui.update { it.copy(reports = r) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // keep previously loaded reports; do not invent any
            }
        }
    }

    // ---- language / permission / location -------------------------------------------------------------

    fun setLanguage(code: String) {
        _ui.update { it.copy(language = code) }
        voice.setLanguage(code)
    }

    fun onPermissionChanged(granted: Boolean) {
        if (_ui.value.locationGranted == granted) return
        _ui.update { it.copy(locationGranted = granted) }
        if (granted) startTracking() else stopTracking()
    }

    private fun startTracking() {
        if (trackingJob?.isActive == true) return
        trackingJob = viewModelScope.launch {
            try {
                container.locationSource.fixes(1000L).collect { onFix(it) }
            } catch (e: SecurityException) {
                _ui.update { it.copy(locationGranted = false) }
            }
        }
    }

    private fun stopTracking() {
        trackingJob?.cancel()
        trackingJob = null
        lastFix = null
        filter.reset()
        speedEstimator.reset()
        _ui.update {
            it.copy(fix = null, quality = null, speedMps = null, speedLimit = SpeedLimitStatus.Unknown, warning = null, overSpeed = false)
        }
    }

    private fun onFix(raw: LocationFix) {
        if (!filter.accept(raw)) return
        val fix = raw
        lastFix = fix
        val est = speedEstimator.update(fix)
        if (est != null) lastSpeedAtMs = fix.elapsedRealtimeMs
        val st = _ui.value
        val speedMps = est?.mps
        val bearing = fix.bearingDeg
        val heading: Float? = if (bearing != null && (speedMps ?: 0.0) > 1.5) bearing else st.heading

        refreshSpeedLimits(fix)
        val limit = SpeedLimitMatcher.match(fix.position, heading?.toDouble(), fix.accuracyM, limitWays)
        val speedKmh = speedMps?.let { it * 3.6 }
        val over = st.settings.speedWarning && est != null && speedKmh != null &&
            SpeedWarning.isOver(speedKmh, est.reliable, limit, st.settings.toleranceKmh)
        if (over && !wasOverSpeed) beep()
        wasOverSpeed = over

        var nav = st.nav
        var progress: NavigationSession.Progress? = null
        val s = session
        if (s != null && nav != null) {
            val p = s.update(fix)
            progress = p
            nav = nav.copy(progress = p)
            if (p.arrived) {
                finishNavigation()
                speak(InstructionFormatter.text(Maneuver(4, "", emptyList(), 0.0, 0.0, 0, null), lang))
                nav = null
                progress = null
            } else {
                announce(s, p, speedMps ?: 0.0)
                val now = SystemClock.elapsedRealtime()
                if (p.offRoute && !nav.rerouting && now - lastRerouteAt > REROUTE_INTERVAL_MS) reroute(fix, heading)
            }
        }

        val warning = computeWarning(fix, progress, heading, speedMps ?: 0.0, st)
        if (warning != null && warning.featureId != lastWarningId) speakWarning(warning)
        lastWarningId = warning?.featureId

        _ui.update {
            it.copy(
                fix = fix,
                quality = LocationQuality.classify(fix, SystemClock.elapsedRealtime()),
                speedMps = speedMps,
                speedReliable = est?.reliable ?: false,
                heading = heading,
                speedLimit = limit,
                overSpeed = over,
                warning = warning,
                nav = if (it.nav == null) null else nav ?: it.nav,
            )
        }
    }

    private fun refreshSpeedLimits(fix: LocationFix) {
        val now = SystemClock.elapsedRealtime()
        val last = limitQueryPos
        val moved = last == null || GeoMath.distanceM(last, fix.position) > 80.0
        if (!_ui.value.online || limitJob?.isActive == true) return
        if (!moved && now - limitQueryAt < 60_000L) return
        if (now - limitQueryAt < 8_000L) return
        limitQueryPos = fix.position
        limitQueryAt = now
        limitJob = viewModelScope.launch {
            try {
                limitWays = container.speedLimits.waysAround(fix.position)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                limitWays = emptyList() // unknown rather than stale
            }
        }
    }

    private fun computeWarning(
        fix: LocationFix,
        progress: NavigationSession.Progress?,
        heading: Float?,
        speedMps: Double,
        st: UiState,
    ): ProximityWarning? {
        if (!st.settings.cameraWarning || st.safety.isEmpty() || fix.accuracyM > 50f) return null
        val s = session
        val candidates: List<Pair<SafetyFeature, Double>> = if (s != null && progress != null) {
            s.featuresAhead(progress.alongM, st.safety, 500.0)
        } else if (heading != null && speedMps > 2.0) {
            st.safety.mapNotNull { f ->
                val d = GeoMath.distanceM(fix.position, f.position)
                val diff = GeoMath.angleDiffDeg(GeoMath.bearingDeg(fix.position, f.position), heading.toDouble())
                if (d <= 300.0 && diff <= 35.0) f to d else null
            }.sortedBy { it.second }
        } else emptyList()
        val hit = candidates.firstOrNull { (f, d) -> d <= if (f.kind == SafetyKind.SPEED_CAMERA) 500.0 else 150.0 }
            ?: return null
        return ProximityWarning(hit.first.kind, hit.second, hit.first.id)
    }

    private fun speakWarning(w: ProximityWarning) {
        val text = when (w.kind) {
            SafetyKind.SPEED_CAMERA -> when (lang) {
                "ar" -> "كاميرا سرعة مسجلة أمامك، حالتها غير معروفة"
                "ckb" -> "کامێرای خێرایی تۆمارکراو لە پێشەوەیە، دۆخەکەی نەزانراوە"
                else -> "Mapped speed camera ahead, status unknown"
            }
            SafetyKind.SPEED_BUMP -> when (lang) {
                "ar" -> "مطب أمامك"
                "ckb" -> "بەرزایی ڕێگا لە پێشەوەیە"
                else -> "Speed bump ahead"
            }
        }
        speak(text)
    }

    private fun beep() {
        if (!_ui.value.settings.voice) return
        val t = tone ?: runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80) }.getOrNull().also { tone = it }
        t?.startTone(ToneGenerator.TONE_PROP_BEEP, 300)
    }

    private fun speak(text: String) {
        if (_ui.value.settings.voice) voice.speak(text)
    }

    // ---- search ----------------------------------------------------------------------------------------

    fun openSearch() = _ui.update { it.copy(searchOpen = true, selection = null, reportSheetOpen = false) }

    fun closeSearch() {
        searchJob?.cancel()
        _ui.update { it.copy(searchOpen = false, query = "", search = SearchState.Idle) }
    }

    fun onQueryChange(q: String) {
        searchJob?.cancel()
        _ui.update { it.copy(query = q, search = SearchState.Idle) }
    }

    fun submitSearch() {
        val q = _ui.value.query.trim()
        if (q.isEmpty()) return
        searchJob?.cancel()
        CoordinateParser.parse(q)?.let { p ->
            val place = Place("coord:${p.lat},${p.lon}", null, null, p, null, "Coordinates")
            _ui.update { it.copy(search = SearchState.Results(listOf(place))) }
            return
        }
        if (!_ui.value.online) {
            _ui.update { it.copy(search = SearchState.Error) }
            return
        }
        _ui.update { it.copy(search = SearchState.Loading) }
        searchJob = viewModelScope.launch {
            try {
                val here = lastFix?.position
                var results = container.geocoder.search(q, lang)
                if (here != null) results = results.sortedBy { GeoMath.distanceM(here, it.position) }
                _ui.update {
                    it.copy(search = if (results.isEmpty()) SearchState.Empty else SearchState.Results(results))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(search = SearchState.Error) }
            }
        }
    }

    fun choosePlace(place: Place) {
        searchJob?.cancel()
        _ui.update {
            it.copy(
                searchOpen = false, query = "", search = SearchState.Idle,
                selection = Selection.PlaceSel(place), route = RouteState.Idle, follow = false,
            )
        }
        _camera.tryEmit(CameraRequest.FlyTo(place.position, 16.0))
        viewModelScope.launch { container.store.addRecent(place.toStored("recent")) }
    }

    fun chooseStored(p: StoredPlace) =
        choosePlace(Place("stored:${p.lat},${p.lon}", p.name, p.address, LatLon(p.lat, p.lon), null, "Saved"))

    private fun selectPoint(p: LatLon) {
        val id = "pin:${p.lat},${p.lon}"
        _ui.update {
            it.copy(
                selection = Selection.PlaceSel(Place(id, null, null, p, null, "Map")),
                route = RouteState.Idle, reportSheetOpen = false,
            )
        }
        reverseJob?.cancel()
        if (!_ui.value.online) return
        reverseJob = viewModelScope.launch {
            try {
                val r = container.geocoder.reverse(p, lang) ?: return@launch
                _ui.update { st ->
                    val cur = st.selection as? Selection.PlaceSel
                    if (cur?.place?.id == id) st.copy(selection = Selection.PlaceSel(r.copy(id = id, position = p))) else st
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // address stays unknown; coordinates remain valid
            }
        }
    }

    fun dismissSelection() = _ui.update { it.copy(selection = null) }

    // ---- places ----------------------------------------------------------------------------------------

    private fun Place.toStored(kind: String) = StoredPlace(
        name = name ?: CoordinateFormat.decimal(position, 5),
        lat = position.lat, lon = position.lon, address = address, kind = kind,
    )

    fun savePlace(place: Place, kind: String) {
        viewModelScope.launch { container.store.savePlace(place.toStored(kind)) }
        notice(R.string.place_saved)
    }

    fun removeStored(p: StoredPlace) { viewModelScope.launch { container.store.removePlace(p) } }
    fun clearRecents() { viewModelScope.launch { container.store.clearRecents() } }
    fun clearSaved() { viewModelScope.launch { container.store.clearSavedPlaces() } }
    fun clearLocalReports() {
        viewModelScope.launch {
            container.store.clearLocalReports()
            if (!container.reports.shared) _ui.update { it.copy(reports = emptyList()) }
        }
    }

    // ---- routing and navigation -------------------------------------------------------------------------

    fun requestRoute(dest: Place) {
        val fix = lastFix
        if (fix == null) {
            _ui.update { it.copy(route = RouteState.Failed(RouteError.NEEDS_GPS, null)) }
            return
        }
        if (!_ui.value.online) {
            _ui.update { it.copy(route = RouteState.Failed(RouteError.NETWORK, null)) }
            return
        }
        routeJob?.cancel()
        _ui.update { it.copy(route = RouteState.Loading) }
        routeJob = viewModelScope.launch {
            try {
                val routes = container.router.route(fix.position, dest.position, _ui.value.heading?.toDouble())
                _ui.update { it.copy(route = RouteState.Ready(routes, 0, dest), selection = null, follow = false) }
                _camera.tryEmit(CameraRequest.Fit(routes.flatMap { r -> r.points.filterIndexed { i, _ -> i % 10 == 0 } }))
                container.store.addRecent(dest.toStored("recent"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: RoutingException) {
                _ui.update { it.copy(route = RouteState.Failed(RouteError.SERVICE, e.message)) }
            } catch (e: IOException) {
                _ui.update { it.copy(route = RouteState.Failed(RouteError.NETWORK, null)) }
            } catch (e: Exception) {
                _ui.update { it.copy(route = RouteState.Failed(RouteError.SERVICE, e.message)) }
            }
        }
    }

    fun selectRoute(index: Int) {
        _ui.update { st ->
            val r = st.route as? RouteState.Ready ?: return@update st
            if (index in r.routes.indices) st.copy(route = r.copy(selected = index)) else st
        }
    }

    fun cancelRoute() {
        routeJob?.cancel()
        _ui.update { it.copy(route = RouteState.Idle) }
    }

    fun startNavigation() {
        val r = _ui.value.route as? RouteState.Ready ?: return
        val route = r.routes[r.selected]
        session = NavigationSession(route)
        announcer.reset()
        lastRerouteAt = SystemClock.elapsedRealtime()
        _ui.update {
            it.copy(
                route = RouteState.Idle,
                nav = NavState(route, r.destination, null, rerouting = false, rerouteFailed = false),
                follow = true, selection = null,
                voiceMissing = it.settings.voice && !voice.languageAvailable,
            )
        }
        route.maneuvers.firstOrNull()?.let { speak(InstructionFormatter.text(it, lang)) }
    }

    fun stopNavigation() {
        finishNavigation()
        _ui.update { it.copy(nav = null, follow = true) }
    }

    private fun finishNavigation() {
        session = null
        announcer.reset()
        routeJob?.cancel()
        voice.stop()
        _ui.update { it.copy(nav = null, follow = true) }
    }

    private fun announce(s: NavigationSession, p: NavigationSession.Progress, speedMps: Double) {
        val idx = p.nextIndex ?: return
        val stage = announcer.next(idx, p.distToNextM, speedMps) ?: return
        val maneuver = s.route.maneuvers[idx]
        val dist = if (stage == VoiceAnnouncer.Stage.NOW) null else p.distToNextM
        speak(InstructionFormatter.spoken(dist, maneuver, lang))
    }

    private fun reroute(fix: LocationFix, heading: Float?) {
        val nav = _ui.value.nav ?: return
        lastRerouteAt = SystemClock.elapsedRealtime()
        _ui.update { it.copy(nav = it.nav?.copy(rerouting = true, rerouteFailed = false)) }
        routeJob?.cancel()
        routeJob = viewModelScope.launch {
            try {
                val route = container.router.route(fix.position, nav.destination.position, heading?.toDouble()).first()
                session = NavigationSession(route)
                announcer.reset()
                _ui.update { it.copy(nav = it.nav?.copy(route = route, progress = null, rerouting = false, rerouteFailed = false)) }
                route.maneuvers.firstOrNull()?.let { speak(InstructionFormatter.text(it, lang)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Never pretend a reroute worked: surface the failure and retry on the next interval.
                _ui.update { it.copy(nav = it.nav?.copy(rerouting = false, rerouteFailed = true)) }
            }
        }
    }

    // ---- map mode / camera -------------------------------------------------------------------------------

    fun toggleMapMode() {
        val next = if (_ui.value.mapMode == MapMode.TWO_D) MapMode.THREE_D else MapMode.TWO_D
        _ui.update { it.copy(mapMode = next) }
        if (next == MapMode.THREE_D && _ui.value.hasBuildings == false) notice(R.string.no_3d_buildings)
    }

    fun recenter() = _ui.update { it.copy(follow = true) }

    // ---- reports ----------------------------------------------------------------------------------------

    fun openReportSheet() = _ui.update { it.copy(reportSheetOpen = true, selection = null) }
    fun closeReportSheet() = _ui.update { it.copy(reportSheetOpen = false) }

    fun submitReport(type: ReportType, comment: String?) {
        val fix = lastFix
        if (fix == null) { notice(R.string.report_needs_gps); return }
        val cleaned = when (val v = ReportPolicy.validate(fix.position, comment)) {
            is ReportValidation.Ok -> v.cleanedComment
            is ReportValidation.Invalid -> { notice(R.string.report_error_invalid); return }
        }
        val now = System.currentTimeMillis()
        val draft = ReportDraft(type, fix.position, cleaned)
        if (ReportPolicy.isDuplicate(draft, _ui.value.reports, now)) { notice(R.string.report_duplicate); return }
        if (!reportLimiter.tryAcquire(now)) { notice(R.string.report_rate_limited); return }
        _ui.update { it.copy(reportSending = true) }
        viewModelScope.launch {
            try {
                val r = container.reports.submit(draft, now)
                _ui.update { it.copy(reports = it.reports + r, reportSending = false, reportSheetOpen = false) }
                notice(if (container.reports.shared) R.string.report_sent_shared else R.string.report_sent_local)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ReportSubmitException) {
                _ui.update { it.copy(reportSending = false) }
                notice(R.string.report_error, e.message)
            } catch (e: Exception) {
                _ui.update { it.copy(reportSending = false) }
                notice(R.string.report_error, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    // ---- settings ---------------------------------------------------------------------------------------

    fun openSettings() = _ui.update { it.copy(settingsOpen = true, selection = null) }
    fun closeSettings() = _ui.update { it.copy(settingsOpen = false) }
    fun setUnits(u: SpeedUnit) { viewModelScope.launch { container.store.setUnits(u) } }
    fun setSpeedWarning(on: Boolean) { viewModelScope.launch { container.store.setSpeedWarning(on) } }
    fun setTolerance(v: Int) { viewModelScope.launch { container.store.setTolerance(v) } }
    fun setCameraWarning(on: Boolean) { viewModelScope.launch { container.store.setCameraWarning(on) } }
    fun setVoice(on: Boolean) { viewModelScope.launch { container.store.setVoice(on) } }

    // ---- notices ----------------------------------------------------------------------------------------

    private fun notice(@StringRes res: Int, arg: String? = null) {
        _ui.update { it.copy(notice = Notice(res, arg, ++noticeSeq)) }
    }

    fun dismissNotice(id: Long) {
        _ui.update { if (it.notice?.id == id) it.copy(notice = null) else it }
    }

    override fun onCleared() {
        runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        voice.shutdown()
        tone?.release()
        super.onCleared()
    }

    companion object {
        private const val REROUTE_INTERVAL_MS = 8_000L
    }
}
