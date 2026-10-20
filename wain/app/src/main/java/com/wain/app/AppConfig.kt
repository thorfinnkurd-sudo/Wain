package com.wain.app

/**
 * Runtime configuration for Wain.
 * OpenFreeMap vector maps and navigation services.
 */
object AppConfig {

    const val DEFAULT_MAP_STYLE =
        "https://tiles.openfreemap.org/styles/bright"

    const val DEMO_ROUTING =
        "https://valhalla1.openstreetmap.de"

    const val DEFAULT_GEOCODER =
        "https://nominatim.openstreetmap.org"

    const val DEFAULT_OVERPASS =
        "https://overpass-api.de/api/interpreter"

    val mapStyleUrl: String
        get() = BuildConfig.MAP_STYLE_URL.ifBlank {
            DEFAULT_MAP_STYLE
        }

    val mapStyleDarkUrl: String
        get() = BuildConfig.MAP_STYLE_DARK_URL.ifBlank {
            mapStyleUrl
        }

    val isDemoStyle: Boolean
        get() = false

    val routingUrl: String
        get() = BuildConfig.ROUTING_URL
            .ifBlank { DEMO_ROUTING }
            .trimEnd('/')

    val isDemoRouting: Boolean
        get() = BuildConfig.ROUTING_URL.isBlank()

    val routingApiKey: String
        get() = BuildConfig.ROUTING_API_KEY

    val geocoderUrl: String
        get() = BuildConfig.GEOCODER_URL
            .ifBlank { DEFAULT_GEOCODER }
            .trimEnd('/')

    val overpassUrl: String
        get() = BuildConfig.OVERPASS_URL
            .ifBlank { DEFAULT_OVERPASS }

    val supabaseUrl: String
        get() = BuildConfig.SUPABASE_URL.trimEnd('/')

    val supabaseAnonKey: String
        get() = BuildConfig.SUPABASE_ANON_KEY

    val sharedReportsConfigured: Boolean
        get() = supabaseUrl.isNotBlank() &&
            supabaseAnonKey.isNotBlank()

    val userAgent: String
        get() = "WainNavigation/${BuildConfig.VERSION_NAME} (${BuildConfig.CONTACT})"
}
