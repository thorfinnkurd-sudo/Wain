package com.wain.app.data.reports

import com.wain.app.AppConfig
import com.wain.app.core.geo.BoundingBox
import com.wain.app.core.geo.GeoMath
import com.wain.app.core.geo.LatLon
import com.wain.app.data.net.HttpFailure
import com.wain.app.data.net.WainJson
import com.wain.app.data.net.fetch
import com.wain.app.data.prefs.WainStore
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Locale
import java.util.UUID

enum class ReportType(val id: String, val ttlMinutes: Int, val colorHex: String, val emoji: String) {
    TRAFFIC("traffic", 60, "#E53935", "\uD83D\uDEA6"),
    ACCIDENT("accident", 120, "#B71C1C", "\uD83D\uDCA5"),
    CLOSURE("closure", 24 * 60, "#6A1B9A", "\u26D4"),
    ROADWORKS("roadworks", 7 * 24 * 60, "#F57C00", "\uD83D\uDEA7"),
    POTHOLE("pothole", 14 * 24 * 60, "#795548", "\uD83D\uDD73\uFE0F"),
    DEBRIS("debris", 180, "#8D6E63", "\uD83E\uDEA8"),
    BROKEN_DOWN("broken_down", 120, "#546E7A", "\uD83D\uDE97"),
    FLOOD("flood", 360, "#0277BD", "\uD83C\uDF0A"),
    DANGEROUS("dangerous", 360, "#D81B60", "\u26A0\uFE0F"),
    OBSTRUCTION("obstruction", 240, "#EF6C00", "\uD83D\uDED1"),
    OTHER("other", 120, "#607D8B", "\u2753");

    companion object {
        fun fromId(id: String?): ReportType? = entries.firstOrNull { it.id == id }
    }
}

enum class ReportOrigin { LOCAL_ONLY, SHARED }

/** Community reports are never "confirmed" by this client; [confirmations] is whatever a backend supplies. */
data class Report(
    val id: String,
    val type: ReportType,
    val position: LatLon,
    val comment: String?,
    val createdAtMs: Long,
    val expiresAtMs: Long,
    val origin: ReportOrigin,
    val confirmations: Int = 0,
)

data class ReportDraft(val type: ReportType, val position: LatLon, val comment: String?)

sealed interface ReportValidation {
    data class Ok(val cleanedComment: String?) : ReportValidation
    data class Invalid(val reason: Reason) : ReportValidation
    enum class Reason { BAD_POSITION, COMMENT_TOO_LONG }
}

object ReportPolicy {
    const val MAX_COMMENT = 200
    const val DUPLICATE_RADIUS_M = 100.0
    const val DUPLICATE_WINDOW_MS = 10 * 60_000L

    fun validate(position: LatLon, comment: String?): ReportValidation {
        if (!position.isValid()) return ReportValidation.Invalid(ReportValidation.Reason.BAD_POSITION)
        val cleaned = comment?.filter { !it.isISOControl() || it == ' ' }?.trim()?.ifBlank { null }
        if (cleaned != null && cleaned.length > MAX_COMMENT) {
            return ReportValidation.Invalid(ReportValidation.Reason.COMMENT_TOO_LONG)
        }
        return ReportValidation.Ok(cleaned)
    }

    fun isActive(r: Report, nowMs: Long): Boolean = nowMs < r.expiresAtMs

    fun expiresAt(type: ReportType, createdAtMs: Long): Long = createdAtMs + type.ttlMinutes * 60_000L

    fun isDuplicate(draft: ReportDraft, existing: List<Report>, nowMs: Long): Boolean = existing.any {
        it.type == draft.type &&
            nowMs - it.createdAtMs <= DUPLICATE_WINDOW_MS &&
            GeoMath.distanceM(it.position, draft.position) <= DUPLICATE_RADIUS_M
    }
}

/** Sliding-window limiter: at most [max] submissions per [windowMs]. Clock is injected for testing. */
class RateLimiter(private val max: Int = 10, private val windowMs: Long = 60 * 60_000L) {
    private val stamps = ArrayDeque<Long>()

    @Synchronized
    fun tryAcquire(nowMs: Long): Boolean {
        while (stamps.isNotEmpty() && nowMs - stamps.first() >= windowMs) stamps.removeFirst()
        if (stamps.size >= max) return false
        stamps.addLast(nowMs)
        return true
    }
}

interface ReportsRepository {
    /** True when reports are stored on a shared backend visible to other drivers. */
    val shared: Boolean
    suspend fun load(bounds: BoundingBox, nowMs: Long): List<Report>
    suspend fun submit(draft: ReportDraft, nowMs: Long): Report
}

@Serializable
private data class LocalReportDto(
    val id: String,
    val type: String,
    val lat: Double,
    val lon: Double,
    val comment: String? = null,
    val createdAtMs: Long,
    val expiresAtMs: Long,
)

/** Development mode: reports live only on this phone and are never visible to anyone else. */
class LocalReportsRepository(private val store: WainStore) : ReportsRepository {
    override val shared = false
    private val serializer = ListSerializer(LocalReportDto.serializer())

    private suspend fun readAll(nowMs: Long): List<Report> {
        val json = store.localReportsJson() ?: return emptyList()
        val dtos = runCatching { WainJson.decodeFromString(serializer, json) }.getOrDefault(emptyList())
        return dtos.mapNotNull { d ->
            val t = ReportType.fromId(d.type) ?: return@mapNotNull null
            Report(d.id, t, LatLon(d.lat, d.lon), d.comment, d.createdAtMs, d.expiresAtMs, ReportOrigin.LOCAL_ONLY)
        }.filter { ReportPolicy.isActive(it, nowMs) }
    }

    override suspend fun load(bounds: BoundingBox, nowMs: Long): List<Report> =
        readAll(nowMs).filter { bounds.contains(it.position) }

    override suspend fun submit(draft: ReportDraft, nowMs: Long): Report {
        val report = Report(
            id = UUID.randomUUID().toString(),
            type = draft.type,
            position = draft.position,
            comment = draft.comment,
            createdAtMs = nowMs,
            expiresAtMs = ReportPolicy.expiresAt(draft.type, nowMs),
            origin = ReportOrigin.LOCAL_ONLY,
        )
        val all = (readAll(nowMs) + report).takeLast(100)
        val dtos = all.map {
            LocalReportDto(it.id, it.type.id, it.position.lat, it.position.lon, it.comment, it.createdAtMs, it.expiresAtMs)
        }
        store.setLocalReportsJson(WainJson.encodeToString(serializer, dtos))
        return report
    }
}

@Serializable
private data class RemoteReportDto(
    val id: String,
    val type: String,
    val lat: Double,
    val lon: Double,
    val comment: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("expires_at") val expiresAt: String,
    val confirmations: Int = 0,
)

class ReportSubmitException(message: String) : Exception(message)

/**
 * Supabase (PostgREST) backend. See supabase/schema.sql for the table, row-level-security policies,
 * server-side expiry, rate limiting and duplicate handling. Only the public anon key is used here.
 */
class SupabaseReportsRepository(
    private val http: OkHttpClient,
    private val baseUrl: String,
    private val anonKey: String,
    private val deviceId: suspend () -> String,
) : ReportsRepository {
    override val shared = true
    private val json = "application/json".toMediaType()

    private fun Request.Builder.auth(): Request.Builder =
        header("apikey", anonKey).header("Authorization", "Bearer $anonKey")

    override suspend fun load(bounds: BoundingBox, nowMs: Long): List<Report> {
        val url = "$baseUrl/rest/v1/reports".toHttpUrl().newBuilder()
            .addQueryParameter("select", "id,type,lat,lon,comment,created_at,expires_at,confirmations")
            .addQueryParameter("expires_at", "gt.${Instant.ofEpochMilli(nowMs)}")
            .addQueryParameter("lat", "gte.${fmt(bounds.south)}")
            .addQueryParameter("lat", "lte.${fmt(bounds.north)}")
            .addQueryParameter("lon", "gte.${fmt(bounds.west)}")
            .addQueryParameter("lon", "lte.${fmt(bounds.east)}")
            .addQueryParameter("limit", "200")
            .build()
        val body = http.fetch(Request.Builder().url(url).auth().build())
        return WainJson.decodeFromString(ListSerializer(RemoteReportDto.serializer()), body).mapNotNull { it.toReport() }
    }

    override suspend fun submit(draft: ReportDraft, nowMs: Long): Report {
        val payload = buildJsonObject {
            put("type", draft.type.id)
            put("lat", draft.position.lat)
            put("lon", draft.position.lon)
            if (draft.comment != null) put("comment", draft.comment)
            put("device_id", deviceId())
        }.toString()
        val request = Request.Builder()
            .url("$baseUrl/rest/v1/reports")
            .auth()
            .header("Prefer", "return=representation")
            .post(payload.toRequestBody(json))
            .build()
        val text = try {
            http.fetch(request)
        } catch (e: HttpFailure) {
            throw ReportSubmitException(serverMessage(e.body) ?: "HTTP ${e.code}")
        }
        val created = WainJson.decodeFromString(ListSerializer(RemoteReportDto.serializer()), text).firstOrNull()?.toReport()
        return created ?: throw ReportSubmitException("The server did not return the created report")
    }

    private fun serverMessage(body: String): String? = runCatching {
        WainJson.parseToJsonElement(body).let { el ->
            (el as? kotlinx.serialization.json.JsonObject)?.get("message")?.let {
                (it as? kotlinx.serialization.json.JsonPrimitive)?.content
            }
        }
    }.getOrNull()

    private fun fmt(d: Double) = String.format(Locale.US, "%.6f", d)

    private fun RemoteReportDto.toReport(): Report? {
        val t = ReportType.fromId(type) ?: return null
        val created = runCatching { OffsetDateTime.parse(createdAt).toInstant().toEpochMilli() }.getOrNull() ?: return null
        val expires = runCatching { OffsetDateTime.parse(expiresAt).toInstant().toEpochMilli() }.getOrNull() ?: return null
        return Report(id, t, LatLon(lat, lon), comment, created, expires, ReportOrigin.SHARED, confirmations)
    }
}

object ReportsRepositoryFactory {
    fun create(http: OkHttpClient, store: WainStore): ReportsRepository =
        if (AppConfig.sharedReportsConfigured) {
            SupabaseReportsRepository(http, AppConfig.supabaseUrl, AppConfig.supabaseAnonKey) { store.deviceId() }
        } else {
            LocalReportsRepository(store)
        }
}
