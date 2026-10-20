# Wain? — وين؟

Android navigation app for Iraq (Kotlin, Jetpack Compose, MapLibre Native). Arabic-first (RTL), with English and Kurdish Sorani (Sorani strings need native-speaker review).

> **Status: source complete for a first build, NOT yet compiled or run.** The authoring environment had no Android SDK, Gradle or internet, so no build, unit test, lint or device test has been executed. Expect to fix some compile errors on the first CI run. Nothing here has been validated on a device.

## Get the APK (cloud build)
1. Create a GitHub repository and push this folder (`git init && git add . && git commit -m init && git push`).
2. Open **Actions → "Build Wain debug APK"** (runs on push, or "Run workflow").
3. When it finishes, download the **wain-debug-apk** artifact (also **wain-unit-test-reports**, **wain-lint-report**).
   The APK is a debug build, signed with the debug key; install with `adb install app-debug.apk` or by sideloading.

Local build (Android Studio, JDK 17): open the folder; if no wrapper jar exists run `gradle wrapper --gradle-version 8.9` once; then `./gradlew :app:assembleDebug :app:testDebugUnitTest`.

## Configuration (all optional; see `.env.example`)
Set as GitHub Actions secrets, environment variables, or `local.properties`. They are compiled into the APK, so use client-side keys only.

| Need | Variable | Without it |
|---|---|---|
| Detailed vector map | `WAIN_MAP_STYLE_URL` (+`_DARK_URL`) | MapLibre demo tiles (world countries only, labelled "development") |
| Routing | `WAIN_ROUTING_URL` (+`WAIN_ROUTING_API_KEY`) — Valhalla API | public FOSSGIS Valhalla demo (development only, no guarantees) |
| Search | `WAIN_GEOCODER_URL` | public Nominatim (1 req/s, search-on-submit only) |
| POIs, cameras, bumps, speed limits | `WAIN_OVERPASS_URL` | public Overpass (fair use) |
| Shared community reports | `WAIN_SUPABASE_URL`, `WAIN_SUPABASE_ANON_KEY` + run `supabase/schema.sql` | reports stored on-device only, labelled "not shared" |
| Contact for User-Agent | `WAIN_CONTACT` | placeholder |
| Release signing | `WAIN_KEYSTORE_PATH/PASSWORD`, `WAIN_KEY_ALIAS/PASSWORD` | release APK unsigned |

For production, run/rent your own tile, routing, geocoding and Overpass services; the public ones above are not licensed for unlimited production use.

## Architecture (single `:app` module)
`core/` pure Kotlin logic (geo math, polyline, coordinates, fix filtering, speed estimation, speed-limit matching, location sources) ·
`data/` network + storage behind interfaces (`Router`, `Geocoder`, `ReportsRepository`, Overpass-backed POI/safety repos, tile cache, DataStore) ·
`nav/` route following, off-route detection, voice staging, localized instructions, TTS ·
`ui/` Compose screens, `MapController` (MapLibre), `WainViewModel`.
Deviations from the brief: DataStore (JSON) instead of Room and `OkHttp` without Retrofit, to minimise build risk in v0.

## Implemented in code (unverified until CI/device run)
- Map (MapLibre), light/dark style, 2D/3D toggle (building extrusions only if the style has a `building` vector layer, otherwise tilt only, and the app says so), attribution.
- Real GPS (Fused provider, platform fallback), accuracy circle, heading arrow only when a real bearing exists, stale/poor/lost indicators, jump filter, follow + recenter, permission flow.
- Search by name (Nominatim) and by coordinates (including Arabic digits), tap/long-press place inspection with copyable coordinates, saved home/work/favorites, recents, clear controls.
- Routing via Valhalla (alternatives, heading hint), turn-by-turn banner, Arabic/English/Sorani spoken instructions (Android TTS; warns if no voice installed), distance/ETA, off-route detection and rerouting (failures surfaced, never faked).
- Zoom-tiered POIs from OpenStreetMap via Overpass with tile cache, de-duplication and screen-cell decluttering.
- Speed: filtered speed, km/h or mph, limit shown **only** when the nearest mapped road has a parsable `maxspeed` (otherwise "Limit unknown"), configurable overspeed warning.
- Speed cameras and speed bumps from OSM; cameras are always shown as **status unknown**.
- Community reports (11 types) with validation, duplicate check, client rate limit, expiry, local mode and Supabase mode with RLS/trigger-based server rules.

## Not implemented / known limitations
- **Live traffic: none.** No traffic provider is integrated; the app says so in Settings and shows no traffic colours.
- No curated Iraqi speed-camera, checkpoint or speed-limit dataset: only what OSM contains (coverage in Iraq is incomplete and unverified).
- No offline maps/routing (memory-only tile cache for POIs). Navigation, search and POIs need internet.
- No terrain/elevation, no satellite layer. No background navigation / foreground service (screen must stay on; it is kept awake while navigating).
- No report voting/moderation UI, no user accounts; moderation is manual via service role.
- Report/POI emoji icons are not a consistent vector set; POI labels depend on glyph fonts in the chosen style.
- No instrumented/UI tests, no R8 shrinking (release `isMinifyEnabled=false`), no performance profiling or FPS measurements, no dependency vulnerability scan.
- Dependency versions (MapLibre 11.5.2, AGP 8.5.2, Kotlin 2.0.20, Compose BOM 2024.09.00) were chosen from memory and not resolved here.

## Tests
`app/src/test` has JVM unit tests for geo/coordinate parsing, polyline, fix filter/quality, speed estimation and limits, POI tiers/declutter/parsing, Valhalla parsing, navigation progress/off-route/arrival/announcements, instruction formatting, report policy/rate limiting and a `.env.example` secrets check. **None has been run yet.**

See `PRIVACY_POLICY_TEMPLATE.md` and `supabase/schema.sql`. Map data © OpenStreetMap contributors (ODbL).
