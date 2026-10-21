package com.wain.app

/**
 * Central runtime configuration for Wain.
 *
 * Keep public endpoint defaults in one place so the map, routing, search and
 * reporting layers use a consistent configuration. Values supplied through
 * BuildConfig take precedence over the safe public defaults below.
 *
 * Note: changing a style URL does not itself guarantee that every road or POI
 * will render; the style's sources, network access and tile responses must also
 * work. Map loading and error handling are managed by MapController.
 */
object AppConfig {

    /**
     * OpenFreeMap Liberty is the default day map: a balanced, detailed
     * OpenStreetMap-based style suitable for a navigation interface.
     */
    const val DEFAULT_MAP_STYLE =
        "https://tiles.openfreemap.org/styles/liberty"

    /**
     * Use a real dark basemap rather than reusing the light Liberty style.
     * This is a separate style URL so the map can switch with the app theme.
     */
    const val DEFAULT_MAP_STYLE_DARK =
        "https://tiles.openfreemap.org/styles/dark"

    /**
     * Public Valhalla demo endpoint. It is useful for development but should
     * not be treated as a guaranteed production SLA.
     */
    const val DEMO_ROUTING =
        "https://valhalla1.openstreetmap.de"

    const val DEFAULT_GEOCODER =
        "https://nominatim.openstreetmap.org"

    const val DEFAULT_OVERPASS =
        "https://overpass-api.de/api/interpreter"

    private fun configuredUrl(value: String, fallback: String): String =
        value.trim().ifBlank { fallback }

    val mapStyleUrl: String
        get() = configuredUrl(
            BuildConfig.MAP_STYLE_URL,
            DEFAULT_MAP_STYLE,
        )

    val mapStyleDarkUrl: String
        get() = configuredUrl(
            BuildConfig.MAP_STYLE_DARK_URL,
            DEFAULT_MAP_STYLE_DARK,
        )

    /**
     * Kept for compatibility with existing UI or diagnostics that may read it.
     * The default map style is a real public style, not a local demo placeholder.
     */
    val isDemoStyle: Boolean
        get() = false

    val routingUrl: String
        get() = configuredUrl(
            BuildConfig.ROUTING_URL,
            DEMO_ROUTING,
        ).trimEnd('/')

    val isDemoRouting: Boolean
        get() = BuildConfig.ROUTING_URL.isBlank()

    val routingApiKey: String
        get() = BuildConfig.ROUTING_API_KEY.trim()

    val geocoderUrl: String
        get() = configuredUrl(
            BuildConfig.GEOCODER_URL,
            DEFAULT_GEOCODER,
        ).trimEnd('/')

    val overpassUrl: String
        get() = configuredUrl(
            BuildConfig.OVERPASS_URL,
            DEFAULT_OVERPASS,
        )

    val supabaseUrl: String
        get() = BuildConfig.SUPABASE_URL.trim().trimEnd('/')

    val supabaseAnonKey: String
        get() = BuildConfig.SUPABASE_ANON_KEY.trim()

    val sharedReportsConfigured: Boolean
        get() = supabaseUrl.isNotBlank() &&
            supabaseAnonKey.isNotBlank()

    val userAgent: String
        get() = "WainNavigation/${BuildConfig.VERSION_NAME} (${BuildConfig.CONTACT})"
}
