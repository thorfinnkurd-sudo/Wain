package com.wain.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.viewinterop.AndroidView
import com.wain.app.AppConfig
import com.wain.app.LocaleHelper
import com.wain.app.R
import com.wain.app.ui.theme.Gold
import kotlinx.coroutines.delay
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapView
import kotlin.math.max

@Composable
fun WainApp(onLanguageChanged: () -> Unit, vm: WainViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val lang = remember { LocaleHelper.language(ctx) }
    LaunchedEffect(lang) { vm.setLanguage(lang) }

    var asked by rememberSaveable { mutableStateOf(false) }
    fun hasPermission() =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        asked = true
        vm.onPermissionChanged(hasPermission())
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) vm.onPermissionChanged(hasPermission()) }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    val view = LocalView.current
    val navigating = ui.nav != null
    DisposableEffect(navigating) {
        view.keepScreenOn = navigating
        onDispose { view.keepScreenOn = false }
    }

    Box(Modifier.fillMaxSize()) {
        MapLayer(ui, vm, Modifier.fillMaxSize())

        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 12.dp, vertical = 8.dp)) {
            Column(Modifier.align(Alignment.TopCenter).fillMaxWidth(), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                val nav = ui.nav
                if (nav != null) NavBanner(ui, nav) else SearchBar(onClick = vm::openSearch)
                if (!ui.locationGranted) {
                    PermissionCard(
                        denied = asked,
                        onGrant = { launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) },
                        onSettings = {
                            ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)))
                        },
                    )
                }
                StatusBanners(ui, vm)
            }

            Column(
                Modifier.align(Alignment.CenterEnd),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (!navigating) {
                    SmallFloatingActionButton(onClick = vm::openSettings) {
                        Icon(Icons.Filled.Settings, stringResource(R.string.cd_settings))
                    }
                }
                SmallFloatingActionButton(onClick = vm::toggleMapMode) {
                    Text(stringResource(if (ui.mapMode == MapMode.TWO_D) R.string.mode_3d else R.string.mode_2d))
                }
                if (!ui.follow && ui.fix != null) {
                    SmallFloatingActionButton(onClick = vm::recenter, containerColor = MaterialTheme.colorScheme.primary) {
                        Icon(Icons.Filled.MyLocation, stringResource(R.string.recenter), tint = MaterialTheme.colorScheme.onPrimary)
                    }
                }
                FloatingActionButton(onClick = vm::openReportSheet, containerColor = Gold) {
                    Icon(Icons.Filled.Warning, stringResource(R.string.report_title), tint = androidx.compose.ui.graphics.Color(0xFF241A00))
                }
            }

            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                if (ui.fix != null) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) { SpeedWidget(ui) }
                }
                BottomPanel(ui, vm)
            }
        }

        if (ui.searchOpen) SearchOverlay(ui, vm)
        if (ui.reportSheetOpen) ReportPanel(ui, vm)
        if (ui.settingsOpen) SettingsOverlay(ui, vm, onLanguageChanged)
    }
}

@Composable
private fun SearchBar(onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(28.dp), tonalElevation = 3.dp, shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(28.dp)).clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Search, stringResource(R.string.cd_search))
            Spacer(Modifier.size(12.dp))
            Text(stringResource(R.string.search_hint), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Image(painterResource(R.drawable.ic_logo), null, Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
        }
    }
}

@Composable
private fun MapLayer(ui: UiState, vm: WainViewModel, modifier: Modifier) {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val dark = isSystemInDarkTheme()
    val mapView = remember { MapLibre.getInstance(ctx); MapView(ctx) }
    val controller = remember { MapController(mapView, vm.mapCallbacks) }

    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_CREATE -> mapView.onCreate(null)
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    AndroidView(factory = { mapView }, modifier = modifier)

    val nav = ui.nav
    val routeState = ui.route
    val routes = when {
        nav != null -> listOf(nav.route)
        routeState is RouteState.Ready -> routeState.routes
        else -> emptyList()
    }
    val selectedRoute = (routeState as? RouteState.Ready)?.selected ?: 0
    val selectedPoint = ((ui.selection as? Selection.PlaceSel)?.place?.position)
        ?: (routeState as? RouteState.Ready)?.destination?.position
        ?: nav?.destination?.position

    LaunchedEffect(dark) { controller.setStyle(if (dark) AppConfig.mapStyleDarkUrl else AppConfig.mapStyleUrl) }
    LaunchedEffect(ui.pois, ui.language) { controller.setPois(ui.pois, ui.language) }
    LaunchedEffect(ui.safety) { controller.setSafety(ui.safety) }
    LaunchedEffect(ui.reports) { controller.setReports(ui.reports) }
    LaunchedEffect(routes, selectedRoute) { controller.setRoutes(routes, selectedRoute) }
    LaunchedEffect(selectedPoint) { controller.setSelected(selectedPoint) }
    LaunchedEffect(ui.mapMode) { controller.set3d(ui.mapMode == MapMode.THREE_D) }
    LaunchedEffect(nav != null) { controller.setNavigationPadding(nav != null) }
    LaunchedEffect(ui.fix, ui.heading) { controller.setPosition(ui.fix, ui.heading) }
    LaunchedEffect(ui.fix, ui.follow, nav != null, ui.mapMode) {
        val fix = ui.fix ?: return@LaunchedEffect
        if (!ui.follow) return@LaunchedEffect
        val navigating = nav != null
        val zoom = if (navigating) CameraPolicy.zoomForSpeedKmh(ui.speedMps?.times(3.6)) else max(ui.zoom, 15.0)
        controller.follow(
            fix.position, if (navigating) ui.heading else null, zoom,
            CameraPolicy.tiltFor(ui.mapMode == MapMode.THREE_D), if (navigating) 900 else 600,
        )
    }
    LaunchedEffect(Unit) { vm.cameraRequests.collect { controller.handle(it) } }
}

@Composable
private fun StatusBanners(ui: UiState, vm: WainViewModel) {
    val notice = ui.notice
    if (notice != null) {
        LaunchedEffect(notice.id) { delay(4000); vm.dismissNotice(notice.id) }
        Banner(if (notice.arg != null) stringResource(notice.res, notice.arg) else stringResource(notice.res))
    }
    if (!ui.online) Banner(stringResource(R.string.offline_banner))
    if (AppConfig.isDemoStyle) Banner(stringResource(R.string.devmap_banner))
    ui.mapError?.let { error ->
    Banner(
        text = stringResource(R.string.map_error) +
            if (error.isNotBlank()) " · $error" else ""
    )
}
    val fix = ui.fix
    if (ui.locationGranted) {
        when {
            fix == null -> Banner(stringResource(R.string.gps_searching))
            ui.quality == com.wain.app.core.location.FixQuality.STALE -> Banner(stringResource(R.string.gps_stale))
            ui.quality == com.wain.app.core.location.FixQuality.POOR -> Banner(stringResource(R.string.gps_poor, fix.accuracyM.toInt()))
        }
    }
    if (ui.voiceMissing) Banner(stringResource(R.string.nav_voice_missing))
    if (ui.pois.isEmpty() && ui.poiStatus == com.wain.app.data.poi.PoiStatus.HIDDEN_ZOOM && ui.zoom in 9.0..12.0) Banner(stringResource(R.string.poi_zoom_in))
    if (ui.poiStatus == com.wain.app.data.poi.PoiStatus.PARTIAL || ui.safetyStatus == com.wain.app.data.safety.SafetyStatus.PARTIAL) {
        Banner(stringResource(R.string.data_partial))
    }
    ui.warning?.let { w ->
        val text = when (w.kind) {
            com.wain.app.data.safety.SafetyKind.SPEED_CAMERA -> stringResource(R.string.camera_ahead)
            com.wain.app.data.safety.SafetyKind.SPEED_BUMP -> stringResource(R.string.bump_ahead)
        }
        Banner("$text · ${com.wain.app.nav.DistanceFormat.short(w.distanceM, ui.language)}", warning = true)
    }
}

@Composable
internal fun Banner(text: String, warning: Boolean = false) {
    Surface(
        shape = RoundedCornerShape(14.dp), shadowElevation = 3.dp,
        color = if (warning) Gold else MaterialTheme.colorScheme.surface,
        contentColor = if (warning) androidx.compose.ui.graphics.Color(0xFF241A00) else MaterialTheme.colorScheme.onSurface,
    ) { Text(text, Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp), style = MaterialTheme.typography.bodyMedium) }
}
