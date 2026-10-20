package com.wain.app.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.wain.app.core.speed.SpeedUnit
import com.wain.app.data.net.WainJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.util.UUID

private val Context.dataStore by preferencesDataStore(name = "wain")

data class AppSettings(
    val units: SpeedUnit = SpeedUnit.KMH,
    val speedWarning: Boolean = true,
    val toleranceKmh: Int = 5,
    val cameraWarning: Boolean = true,
    val voice: Boolean = true,
)

@Serializable
data class StoredPlace(
    val name: String,
    val lat: Double,
    val lon: Double,
    val address: String? = null,
    /** "home", "work", "fav" or "recent". */
    val kind: String,
)

class WainStore(private val context: Context) {
    private val ds get() = context.dataStore

    private val kUnits = stringPreferencesKey("units")
    private val kSpeedWarn = booleanPreferencesKey("warn_speed")
    private val kTolerance = intPreferencesKey("warn_tolerance")
    private val kCameraWarn = booleanPreferencesKey("warn_camera")
    private val kVoice = booleanPreferencesKey("voice")
    private val kPlaces = stringPreferencesKey("places")
    private val kLocalReports = stringPreferencesKey("local_reports")
    private val kDeviceId = stringPreferencesKey("device_id")

    val settings: Flow<AppSettings> = ds.data.map { p ->
        AppSettings(
            units = SpeedUnit.fromId(p[kUnits]),
            speedWarning = p[kSpeedWarn] ?: true,
            toleranceKmh = p[kTolerance] ?: 5,
            cameraWarning = p[kCameraWarn] ?: true,
            voice = p[kVoice] ?: true,
        )
    }

    suspend fun setUnits(u: SpeedUnit) = ds.edit { it[kUnits] = u.id }
    suspend fun setSpeedWarning(on: Boolean) = ds.edit { it[kSpeedWarn] = on }
    suspend fun setTolerance(kmh: Int) = ds.edit { it[kTolerance] = kmh.coerceIn(0, 30) }
    suspend fun setCameraWarning(on: Boolean) = ds.edit { it[kCameraWarn] = on }
    suspend fun setVoice(on: Boolean) = ds.edit { it[kVoice] = on }

    // ---- saved places and recents -------------------------------------------------------------

    val places: Flow<List<StoredPlace>> = ds.data.map { decodePlaces(it) }

    private fun decodePlaces(p: Preferences): List<StoredPlace> =
        p[kPlaces]?.let { runCatching { WainJson.decodeFromString(ListSerializer(StoredPlace.serializer()), it) }.getOrNull() }
            ?: emptyList()

    private suspend fun editPlaces(block: (List<StoredPlace>) -> List<StoredPlace>) {
        ds.edit { p ->
            val next = block(decodePlaces(p))
            p[kPlaces] = WainJson.encodeToString(ListSerializer(StoredPlace.serializer()), next)
        }
    }

    suspend fun savePlace(place: StoredPlace) = editPlaces { list ->
        val without = when (place.kind) {
            "home", "work" -> list.filterNot { it.kind == place.kind }
            else -> list.filterNot { it.kind == place.kind && it.lat == place.lat && it.lon == place.lon }
        }
        without + place
    }

    suspend fun addRecent(place: StoredPlace) = editPlaces { list ->
        val recents = list.filter { it.kind == "recent" && !(it.lat == place.lat && it.lon == place.lon) }
        val others = list.filter { it.kind != "recent" }
        others + (listOf(place.copy(kind = "recent")) + recents).take(MAX_RECENTS)
    }

    suspend fun removePlace(place: StoredPlace) = editPlaces { it.filterNot { p -> p == place } }
    suspend fun clearRecents() = editPlaces { it.filterNot { p -> p.kind == "recent" } }
    suspend fun clearSavedPlaces() = editPlaces { it.filter { p -> p.kind == "recent" } }

    // ---- local-only reports (development mode, never shared) -----------------------------------

    suspend fun localReportsJson(): String? = ds.data.first()[kLocalReports]
    suspend fun setLocalReportsJson(json: String) = ds.edit { it[kLocalReports] = json }
    suspend fun clearLocalReports() = ds.edit { it.remove(kLocalReports) }

    // ---- pseudonymous device id used only for report rate limiting -----------------------------

    suspend fun deviceId(): String {
        ds.data.first()[kDeviceId]?.let { return it }
        val id = UUID.randomUUID().toString()
        ds.edit { if (it[kDeviceId] == null) it[kDeviceId] = id }
        return ds.data.first()[kDeviceId] ?: id
    }

    companion object {
        const val MAX_RECENTS = 10
    }
}
