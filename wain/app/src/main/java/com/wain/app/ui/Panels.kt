package com.wain.app.ui

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wain.app.AppConfig
import com.wain.app.BuildConfig
import com.wain.app.LocaleHelper
import com.wain.app.R
import com.wain.app.core.geo.CoordinateFormat
import com.wain.app.core.speed.SpeedLimitStatus
import com.wain.app.core.speed.SpeedUnit
import com.wain.app.data.poi.PoiCategory
import com.wain.app.data.reports.ReportOrigin
import com.wain.app.data.reports.ReportType
import com.wain.app.data.safety.SafetyKind
import com.wain.app.data.search.Place
import com.wain.app.data.prefs.StoredPlace
import com.wain.app.nav.DistanceFormat
import com.wain.app.nav.DurationFormat
import com.wain.app.nav.InstructionFormatter
import com.wain.app.ui.theme.Gold
import com.wain.app.ui.theme.Lapis
import kotlin.math.roundToInt

// ---- helpers ---------------------------------------------------------------------------------------

@Composable
private fun stringByKey(prefix: String, id: String, fallback: Int): String {
    val ctx = LocalContext.current
    val res = ctx.resources.getIdentifier("$prefix$id", "string", ctx.packageName)
    return stringResource(if (res != 0) res else fallback)
}

@Composable
internal fun categoryLabel(c: PoiCategory) = stringByKey("cat_", c.id, R.string.cat_unknown)

@Composable
internal fun reportLabel(t: ReportType) = stringByKey("rt_", t.id, R.string.rt_other)

@Composable
private fun coordText(p: com.wain.app.core.geo.LatLon) = Text(
    CoordinateFormat.display(p),
    style = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
)

@Composable
private fun SheetSurface(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(24.dp), shadowElevation = 10.dp, tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() } }
}

// ---- top / status ------------------------------------------------------------------------------------

@Composable
internal fun NavBanner(ui: UiState, nav: NavState) {
    val p = nav.progress
    val m = nav.route.maneuvers.getOrNull(p?.nextIndex ?: 1) ?: nav.route.maneuvers.lastOrNull()
    Surface(shape = RoundedCornerShape(20.dp), color = Lapis, contentColor = Color.White, shadowElevation = 8.dp, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(Icons.Filled.ArrowUpward, null, Modifier.size(48.dp).rotate(m?.let { InstructionFormatter.arrowRotationDeg(it.type) } ?: 0f))
            Column(Modifier.weight(1f)) {
                if (p != null) Text(DistanceFormat.short(p.distToNextM, ui.language), fontSize = 28.sp, fontWeight = FontWeight.Bold)
                if (m != null) Text(InstructionFormatter.text(m, ui.language), fontSize = 17.sp)
                if (nav.rerouting) Text(stringResource(R.string.nav_rerouting), fontSize = 13.sp, color = Gold)
                else if (nav.rerouteFailed) Text(stringResource(R.string.nav_reroute_failed), fontSize = 13.sp, color = Gold)
            }
        }
    }
}

@Composable
internal fun PermissionCard(denied: Boolean, onGrant: () -> Unit, onSettings: () -> Unit) {
    SheetSurface {
        Text(stringResource(R.string.perm_title), fontWeight = FontWeight.Bold)
        Text(stringResource(if (denied) R.string.perm_denied else R.string.perm_body), fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onGrant) { Text(stringResource(R.string.perm_grant)) }
            if (denied) OutlinedButton(onClick = onSettings) { Text(stringResource(R.string.perm_open_settings)) }
        }
    }
}

@Composable
internal fun SpeedWidget(ui: UiState) {
    val unit = ui.settings.units
    val speed = ui.speedMps?.let { unit.fromMps(it).roundToInt() }
    val limit = ui.speedLimit
    val red = Color(0xFFD32F2F)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
        Surface(shape = CircleShape, shadowElevation = 6.dp, color = if (ui.overSpeed) red else MaterialTheme.colorScheme.surface,
            contentColor = if (ui.overSpeed) Color.White else MaterialTheme.colorScheme.onSurface) {
            Column(Modifier.size(78.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(speed?.toString() ?: "--", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text(stringResource(if (unit == SpeedUnit.KMH) R.string.unit_kmh else R.string.unit_mph), fontSize = 11.sp)
                if (speed != null && !ui.speedReliable) Text("±", fontSize = 11.sp)
            }
        }
        if (limit is SpeedLimitStatus.Verified) {
            val shown = if (unit == SpeedUnit.KMH) limit.kmh else (limit.kmh / SpeedUnit.KMH_PER_MPH).roundToInt()
            Surface(shape = CircleShape, shadowElevation = 6.dp, color = Color.White, border = BorderStroke(6.dp, red)) {
                Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                    Text(shown.toString(), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                }
            }
        } else {
            Surface(shape = RoundedCornerShape(12.dp), shadowElevation = 3.dp) {
                Text(stringResource(R.string.speed_limit_unknown), Modifier.padding(8.dp), fontSize = 12.sp)
            }
        }
    }
}

// ---- bottom ------------------------------------------------------------------------------------------

@Composable
internal fun BottomPanel(ui: UiState, vm: WainViewModel) {
    val nav = ui.nav
    val ctx = LocalContext.current
    when {
        nav != null -> SheetSurface {
            val p = nav.progress
            val secs = p?.remainingS ?: nav.route.durationS
            val meters = p?.remainingM ?: nav.route.distanceM
            val eta = android.text.format.DateFormat.getTimeFormat(ctx).format(java.util.Date(System.currentTimeMillis() + (secs * 1000).toLong()))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(eta, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("${DurationFormat.short(secs, ui.language)} · ${DistanceFormat.short(meters, ui.language)}", fontSize = 14.sp)
                }
                Button(onClick = vm::stopNavigation) { Text(stringResource(R.string.action_end)) }
            }
        }
        ui.route !is RouteState.Idle -> RoutePanel(ui, vm)
        ui.selection != null -> SelectionSheet(ui, vm)
    }
}

@Composable
private fun RoutePanel(ui: UiState, vm: WainViewModel) {
    SheetSurface {
        when (val r = ui.route) {
            RouteState.Loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                Text(stringResource(R.string.route_loading), Modifier.weight(1f))
                TextButton(onClick = vm::cancelRoute) { Text(stringResource(R.string.action_close)) }
            }
            is RouteState.Failed -> {
                Text(
                    when (r.kind) {
                        RouteError.NETWORK -> stringResource(R.string.route_error_network)
                        RouteError.NEEDS_GPS -> stringResource(R.string.gps_searching)
                        RouteError.SERVICE -> stringResource(R.string.route_error_service, r.detail ?: "")
                    }
                )
                TextButton(onClick = vm::cancelRoute) { Text(stringResource(R.string.action_close)) }
            }
            is RouteState.Ready -> {
                r.routes.forEachIndexed { i, route ->
                    val sel = i == r.selected
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (sel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { vm.selectRoute(i) },
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(DurationFormat.short(route.durationS, ui.language), fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                            Text(DistanceFormat.short(route.distanceM, ui.language))
                            if (i > 0) Text("  ${stringResource(R.string.route_alternative)}", fontSize = 12.sp)
                        }
                    }
                }
                if (AppConfig.isDemoRouting) Text(stringResource(R.string.route_demo_note), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = vm::startNavigation, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.action_start)) }
                    OutlinedButton(onClick = vm::cancelRoute) { Text(stringResource(R.string.action_close)) }
                }
            }
            RouteState.Idle -> Unit
        }
    }
}

@Composable
private fun SelectionSheet(ui: UiState, vm: WainViewModel) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    SheetSurface {
        when (val s = ui.selection) {
            is Selection.PlaceSel -> {
                val place = s.place
                val cat = PoiCategory.fromId(place.category)
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text(place.name ?: stringResource(R.string.place_unnamed), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        if (cat != null) Text(categoryLabel(cat), fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                        (place.address ?: s.poi?.address)?.let { Text(it, fontSize = 14.sp) }
                        s.poi?.phone?.let { Text("${stringResource(R.string.place_phone)}: $it", fontSize = 13.sp) }
                        s.poi?.openingHours?.let { Text("${stringResource(R.string.place_hours)}: $it", fontSize = 13.sp) }
                        coordText(place.position)
                        Text(stringResource(R.string.place_source, place.source), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = {
                        clipboard.setText(AnnotatedString(CoordinateFormat.decimal(place.position)))
                        Toast.makeText(ctx, R.string.coords_copied, Toast.LENGTH_SHORT).show()
                    }) { Icon(Icons.Filled.ContentCopy, stringResource(R.string.action_copy_coords)) }
                    IconButton(onClick = vm::dismissSelection) { Icon(Icons.Filled.Close, stringResource(R.string.action_close)) }
                }
                Button(onClick = { vm.requestRoute(place) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_directions)) }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { vm.savePlace(place, "fav") }) { Text(stringResource(R.string.save_favorite)) }
                    TextButton(onClick = { vm.savePlace(place, "home") }) { Text(stringResource(R.string.set_home)) }
                    TextButton(onClick = { vm.savePlace(place, "work") }) { Text(stringResource(R.string.set_work)) }
                }
            }
            is Selection.ReportSel -> {
                val r = s.report
                val minutes = ((System.currentTimeMillis() - r.createdAtMs) / 60_000L).coerceAtLeast(0)
                Row {
                    Column(Modifier.weight(1f)) {
                        Text("${r.type.emoji} ${reportLabel(r.type)}", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text(if (minutes < 60) stringResource(R.string.report_age_min, minutes.toInt()) else stringResource(R.string.report_age_hours, (minutes / 60).toInt()))
                        Text(stringResource(if (r.origin == ReportOrigin.LOCAL_ONLY) R.string.report_local_only_label else R.string.report_unverified), fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                        r.comment?.let { Text(it) }
                        coordText(r.position)
                    }
                    IconButton(onClick = vm::dismissSelection) { Icon(Icons.Filled.Close, stringResource(R.string.action_close)) }
                }
            }
            is Selection.SafetySel -> {
                val f = s.feature
                Row {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(if (f.kind == SafetyKind.SPEED_CAMERA) R.string.camera_title else R.string.bump_title), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        if (f.kind == SafetyKind.SPEED_CAMERA) Text(stringResource(R.string.camera_status_unknown_detail), fontSize = 14.sp)
                        f.maxspeedRaw?.let { Text(stringResource(R.string.safety_mapped_limit, it), fontSize = 13.sp) }
                        f.lastCheckedOsm?.let { Text(stringResource(R.string.safety_last_check, it), fontSize = 13.sp) }
                        Text(stringResource(R.string.place_source, f.source), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        coordText(f.position)
                    }
                    IconButton(onClick = vm::dismissSelection) { Icon(Icons.Filled.Close, stringResource(R.string.action_close)) }
                }
            }
            null -> Unit
        }
    }
}

// ---- search ------------------------------------------------------------------------------------------

@Composable
internal fun SearchOverlay(ui: UiState, vm: WainViewModel) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = vm::closeSearch) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_close)) }
                OutlinedTextField(
                    value = ui.query, onValueChange = vm::onQueryChange, singleLine = true,
                    placeholder = { Text(stringResource(R.string.search_hint)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { vm.submitSearch() }),
                    modifier = Modifier.weight(1f).focusRequester(focus),
                    trailingIcon = { if (ui.query.isNotEmpty()) IconButton(onClick = { vm.onQueryChange("") }) { Icon(Icons.Filled.Close, null) } },
                )
            }
            Spacer(Modifier.size(8.dp))
            when (val s = ui.search) {
                SearchState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                SearchState.Empty -> Text(stringResource(R.string.search_no_results))
                SearchState.Error -> Text(stringResource(if (ui.online) R.string.search_error else R.string.search_offline))
                is SearchState.Results -> LazyColumn {
                    items(s.places) { p -> PlaceRow(p) { vm.choosePlace(p) } }
                    item { Text(stringResource(R.string.search_uncertain), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp)) }
                }
                SearchState.Idle -> SavedAndRecents(ui, vm)
            }
        }
    }
}

@Composable
private fun PlaceRow(p: Place, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Filled.Place, null, tint = MaterialTheme.colorScheme.primary)
        Column {
            Text(p.name ?: p.address ?: CoordinateFormat.decimal(p.position, 5), fontWeight = FontWeight.Medium)
            if (p.name != null && p.address != null) Text(p.address, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
    }
}

@Composable
private fun SavedAndRecents(ui: UiState, vm: WainViewModel) {
    val saved = ui.places.filter { it.kind != "recent" }
    val recents = ui.places.filter { it.kind == "recent" }
    LazyColumn {
        item { Text(stringResource(R.string.search_coordinates_hint), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (saved.isNotEmpty()) item { Text(stringResource(R.string.saved_section), fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp)) }
        items(saved) { StoredRow(it, vm) }
        if (recents.isNotEmpty()) item {
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.recent_section), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = vm::clearRecents) { Text(stringResource(R.string.clear_recents)) }
            }
        }
        items(recents) { StoredRow(it, vm) }
    }
}

@Composable
private fun StoredRow(p: StoredPlace, vm: WainViewModel) {
    Row(Modifier.fillMaxWidth().clickable { vm.chooseStored(p) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(when (p.kind) { "home" -> Icons.Filled.Home; "work" -> Icons.Filled.Work; "fav" -> Icons.Filled.Star; else -> Icons.Filled.History }, null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f)) {
            Text(when (p.kind) { "home" -> stringResource(R.string.home); "work" -> stringResource(R.string.work); else -> p.name }, fontWeight = FontWeight.Medium)
            p.address?.let { Text(it, fontSize = 12.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (p.kind != "recent") IconButton(onClick = { vm.removeStored(p) }) { Icon(Icons.Filled.Close, stringResource(R.string.action_close)) }
    }
}

// ---- report ------------------------------------------------------------------------------------------

@Composable
internal fun ReportPanel(ui: UiState, vm: WainViewModel) {
    var selected by remember { mutableStateOf<ReportType?>(null) }
    var comment by remember { mutableStateOf("") }
    Box(Modifier.fillMaxSize().background(Color(0x66000000)).clickable(onClick = vm::closeReportSheet)) {
        Surface(
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().clickable(enabled = false) {},
        ) {
            Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.report_title), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                if (!ui.reportsShared) Text(stringResource(R.string.reports_not_shared), fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                ReportType.entries.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { t ->
                            val sel = selected == t
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = if (sel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                border = if (sel) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                                modifier = Modifier.weight(1f).heightIn(min = 84.dp).clip(RoundedCornerShape(16.dp)).clickable { selected = t },
                            ) {
                                Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                    Text(t.emoji, fontSize = 28.sp)
                                    Text(reportLabel(t), fontSize = 12.sp, maxLines = 2)
                                }
                            }
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                OutlinedTextField(
                    value = comment, onValueChange = { if (it.length <= 200) comment = it },
                    placeholder = { Text(stringResource(R.string.report_comment_hint)) }, modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { selected?.let { vm.submitReport(it, comment) } },
                    enabled = selected != null && !ui.reportSending && ui.fix != null,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.report_send)) }
            }
        }
    }
}

// ---- settings ----------------------------------------------------------------------------------------

@Composable
internal fun SettingsOverlay(ui: UiState, vm: WainViewModel, onLanguageChanged: () -> Unit) {
    val ctx = LocalContext.current
    val s = ui.settings
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = vm::closeSettings) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_close)) }
                Text(stringResource(R.string.settings_title), fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
            Text(stringResource(R.string.settings_language), fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("ar" to "العربية", "en" to "English", "ckb" to "کوردی").forEach { (code, label) ->
                    FilterChip(selected = ui.language == code, onClick = {
                        if (ui.language != code) { LocaleHelper.setLanguage(ctx, code); onLanguageChanged() }
                    }, label = { Text(label) })
                }
            }
            Text(stringResource(R.string.settings_units), fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = s.units == SpeedUnit.KMH, onClick = { vm.setUnits(SpeedUnit.KMH) }, label = { Text(stringResource(R.string.unit_kmh)) })
                FilterChip(selected = s.units == SpeedUnit.MPH, onClick = { vm.setUnits(SpeedUnit.MPH) }, label = { Text(stringResource(R.string.unit_mph)) })
            }
            SwitchRow(R.string.settings_speed_warning, s.speedWarning, vm::setSpeedWarning)
            Text(stringResource(R.string.settings_tolerance, s.toleranceKmh))
            Slider(value = s.toleranceKmh.toFloat(), onValueChange = { vm.setTolerance(it.roundToInt()) }, valueRange = 0f..20f, steps = 19, enabled = s.speedWarning)
            SwitchRow(R.string.settings_camera_warning, s.cameraWarning, vm::setCameraWarning)
            SwitchRow(R.string.settings_voice, s.voice, vm::setVoice)
            HorizontalDivider()
            Text(stringResource(R.string.settings_data), fontWeight = FontWeight.Bold)
            OutlinedButton(onClick = vm::clearRecents) { Text(stringResource(R.string.clear_recents)) }
            OutlinedButton(onClick = vm::clearSaved) { Text(stringResource(R.string.settings_clear_saved)) }
            OutlinedButton(onClick = vm::clearLocalReports) { Text(stringResource(R.string.settings_clear_reports)) }
            HorizontalDivider()
            Text(stringResource(R.string.settings_services), fontWeight = FontWeight.Bold)
            Text(stringResource(if (AppConfig.isDemoStyle) R.string.svc_map_demo else R.string.svc_map_configured), fontSize = 13.sp)
            Text(stringResource(if (AppConfig.isDemoRouting) R.string.svc_routing_demo else R.string.svc_routing_configured), fontSize = 13.sp)
            Text(stringResource(if (ui.reportsShared) R.string.svc_reports_shared else R.string.svc_reports_local), fontSize = 13.sp)
            Text(stringResource(R.string.svc_traffic_none), fontSize = 13.sp)
            HorizontalDivider()
            Text(stringResource(R.string.settings_attribution), fontSize = 13.sp)
            Text(stringResource(R.string.settings_privacy_note), fontSize = 13.sp)
            Text(stringResource(R.string.settings_about, BuildConfig.VERSION_NAME), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SwitchRow(label: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
