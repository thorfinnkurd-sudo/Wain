package com.wain.app

import android.app.Application
import android.content.Context
import com.wain.app.core.location.LocationSource
import com.wain.app.core.location.LocationSourceFactory
import com.wain.app.data.net.buildHttpClient
import com.wain.app.data.overpass.OverpassClient
import com.wain.app.data.poi.PoiRepository
import com.wain.app.data.prefs.WainStore
import com.wain.app.data.reports.ReportsRepository
import com.wain.app.data.reports.ReportsRepositoryFactory
import com.wain.app.data.routing.Router
import com.wain.app.data.routing.ValhallaRouter
import com.wain.app.data.safety.SafetyRepository
import com.wain.app.data.safety.SpeedLimitRepository
import com.wain.app.data.search.Geocoder
import com.wain.app.data.search.NominatimGeocoder
import java.util.Locale

/** Hand-wired dependency container; every network service is replaceable via its interface. */
class AppContainer(context: Context) {
    val http = buildHttpClient()
    val store = WainStore(context)
    private val overpass = OverpassClient(http, AppConfig.overpassUrl)
    val geocoder: Geocoder = NominatimGeocoder(http, AppConfig.geocoderUrl)
    val router: Router = ValhallaRouter(http, AppConfig.routingUrl, AppConfig.routingApiKey)
    val pois = PoiRepository(overpass)
    val safety = SafetyRepository(overpass)
    val speedLimits = SpeedLimitRepository(overpass)
    val reports: ReportsRepository = ReportsRepositoryFactory.create(http, store)
    val locationSource: LocationSource = LocationSourceFactory.create(context)
}

class WainApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

object LocaleHelper {
    private const val PREFS = "wain_prefs"
    private const val KEY = "lang"
    val supported = listOf("ar", "en", "ckb")

    fun language(context: Context): String {
        val v = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "ar") ?: "ar"
        return if (v in supported) v else "ar"
    }

    fun setLanguage(context: Context, lang: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, lang).apply()
    }

    /** Arabic is the default; the chosen language and its layout direction are applied to the activity context. */
    fun wrap(base: Context): Context {
        val locale = Locale.forLanguageTag(language(base))
        Locale.setDefault(locale)
        val config = android.content.res.Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }
}
