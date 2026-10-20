package vn.vumap.feature.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import vn.vumap.core.model.GeoPoint
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * Public Photon geocoder provider (https://github.com/komoot/photon).
 *
 * Endpoints: `GET /api?q=...&limit=N` and `GET /reverse?lat=&lon=`.
 * No `lang` is sent unless a [SUPPORTED_LANGS] code is requested: the public
 * server rejects any other value with HTTP 400 (live evidence 2026-10-17).
 *
 * RULES (project plan sections 0.3, 0.4, 5; initial task queue item 3):
 * - Public demo service: no API key is sent on any request (nothing to leak).
 * - Per-host cooldown with exponential backoff: 429 / timeout / 5xx park the
 *   host (30s, doubling per consecutive failure, capped at 5 minutes).
 * - Search is user-initiated only (autocomplete with debounce). This class
 *   never polls and must never be called per GPS tick.
 */
class PhotonSearchProvider(
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val client: OkHttpClient = defaultClient(),
    private val baseCooldownMs: Long = BASE_COOLDOWN_MS,
    private val maxCooldownMs: Long = MAX_COOLDOWN_MS,
    private val timeProvider: () -> Long = { System.currentTimeMillis() },
) {

    private val cooldownUntil = ConcurrentHashMap<String, Long>()
    private val consecutiveFailures = ConcurrentHashMap<String, Int>()

    private fun hostKey(): String = baseUrl.trimEnd('/').lowercase()

    /** True while this host is parked after 429/timeout/5xx. */
    fun isCoolingDown(): Boolean {
        val until = cooldownUntil[hostKey()] ?: return false
        return timeProvider() < until
    }

    /** Remaining cooldown in whole seconds (0 when not cooling down). */
    fun cooldownRemainingSec(): Long {
        val until = cooldownUntil[hostKey()] ?: return 0L
        return ((until - timeProvider()) / 1000).coerceAtLeast(0L)
    }

    fun resetCooldown() {
        cooldownUntil.remove(hostKey())
        consecutiveFailures.remove(hostKey())
    }

    private fun recordFailureCooling() {
        val key = hostKey()
        val n = (consecutiveFailures[key] ?: 0) + 1
        consecutiveFailures[key] = n
        val backoff = baseCooldownMs * (1L shl min(n - 1, 4))
        cooldownUntil[key] = timeProvider() + min(backoff, maxCooldownMs)
    }

    private fun recordSuccess() {
        consecutiveFailures.remove(hostKey())
    }

    private fun coolingFailure(): SearchOutcome.Failure = SearchOutcome.Failure(
        error = SearchError.COOLDOWN_ACTIVE,
        message = "Máy chủ tìm kiếm đang nghỉ (còn ${cooldownRemainingSec()}s), thử lại sau.",
    )

    /**
     * Forward geocode [query]. Returns at most [limit] candidates.
     * An optional [bias] (user GPS fix) is sent as Photon `lat`/`lon` bias —
     * it only re-ranks candidates, never fabricates positions.
     * [lang] is appended only when it is in [SUPPORTED_LANGS]; unsupported
     * codes are dropped instead of being sent (the server answers HTTP 400 to
     * an unsupported `lang`, which would fail the search).
     * [countryCode] is an optional ISO 3166-1 alpha-2 scope filter (Photon
     * `countrycode`); invalid codes are dropped rather than guessed.
     */
    suspend fun search(
        query: String,
        limit: Int = DEFAULT_LIMIT,
        lang: String? = null,
        bias: GeoPoint? = null,
        countryCode: String? = null,
    ): SearchOutcome = withContext(Dispatchers.IO) {
        if (isCoolingDown()) return@withContext coolingFailure()

        val url = buildSearchUrl(query, limit, lang, bias, countryCode)
            ?: return@withContext SearchOutcome.Failure(
                SearchError.SERVER_UNAVAILABLE,
                "Địa chỉ máy chủ tìm kiếm không hợp lệ.",
            )

        // Photon public needs no key: deliberately NO Authorization header.
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> {
                        val body = response.body?.string()
                            ?: return@withContext SearchOutcome.Failure(
                                SearchError.SERVER_UNAVAILABLE,
                                "Máy chủ tìm kiếm trả về nội dung rỗng.",
                                200,
                            )
                        try {
                            val results = PhotonResponseParser.parse(body)
                            recordSuccess()
                            if (results.isEmpty()) {
                                SearchOutcome.Failure(
                                    SearchError.NO_RESULTS,
                                    "Không tìm thấy địa điểm cho \"$query\".",
                                    200,
                                )
                            } else {
                                SearchOutcome.Success(results = results)
                            }
                        } catch (e: Exception) {
                            SearchOutcome.Failure(
                                SearchError.SERVER_UNAVAILABLE,
                                "Lỗi xử lý phản hồi tìm kiếm: ${e.message}",
                                200,
                            )
                        }
                    }
                    401, 403 -> SearchOutcome.Failure(
                        SearchError.UNAUTHORIZED,
                        "Máy chủ tìm kiếm từ chối truy cập (HTTP ${response.code}).",
                        response.code,
                    )
                    429 -> {
                        recordFailureCooling()
                        SearchOutcome.Failure(
                            SearchError.RATE_LIMITED,
                            "Tìm kiếm quá nhiều (HTTP 429). Máy chủ nghỉ ${cooldownRemainingSec()}s.",
                            429,
                        )
                    }
                    in 500..599 -> {
                        recordFailureCooling()
                        SearchOutcome.Failure(
                            SearchError.SERVER_UNAVAILABLE,
                            "Máy chủ tìm kiếm lỗi (HTTP ${response.code}), thử lại sau.",
                            response.code,
                        )
                    }
                    else -> SearchOutcome.Failure(
                        SearchError.SERVER_UNAVAILABLE,
                        "Tìm kiếm trả về mã phản hồi không xác định: ${response.code}.",
                        response.code,
                    )
                }
            }
        } catch (e: SocketTimeoutException) {
            recordFailureCooling()
            SearchOutcome.Failure(SearchError.TIMEOUT, "Kết nối tìm kiếm quá thời gian chờ (Timeout).")
        } catch (e: IOException) {
            SearchOutcome.Failure(SearchError.NETWORK_OFFLINE, "Không có kết nối mạng tới máy chủ tìm kiếm.")
        } catch (e: Exception) {
            SearchOutcome.Failure(SearchError.SERVER_UNAVAILABLE, "Lỗi không xác định khi tìm kiếm: ${e.message}")
        }
    }

    /**
     * Reverse geocode a point to the nearest named place.
     */
    suspend fun reverse(point: GeoPoint, limit: Int = 1): SearchOutcome =
        withContext(Dispatchers.IO) {
            if (isCoolingDown()) return@withContext coolingFailure()
            val base = baseUrl.trimEnd('/') + "/reverse?lat=${point.latitude}&lon=${point.longitude}&limit=$limit"
            val url = base.toHttpUrlOrNull()
                ?: return@withContext SearchOutcome.Failure(
                    SearchError.SERVER_UNAVAILABLE,
                    "Địa chỉ máy chủ tìm kiếm không hợp lệ.",
                )
            val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
            try {
                client.newCall(request).execute().use { response ->
                    when (response.code) {
                        200 -> {
                            val body = response.body?.string()
                                ?: return@withContext SearchOutcome.Failure(
                                    SearchError.SERVER_UNAVAILABLE,
                                    "Máy chủ tìm kiếm trả về nội dung rỗng.",
                                    200,
                                )
                            try {
                                val results = PhotonResponseParser.parse(body)
                                recordSuccess()
                                if (results.isEmpty()) {
                                    SearchOutcome.Failure(
                                        SearchError.NO_RESULTS,
                                        "Không tìm thấy địa danh gần vị trí này.",
                                        200,
                                    )
                                } else {
                                    SearchOutcome.Success(results = results)
                                }
                            } catch (e: Exception) {
                                SearchOutcome.Failure(
                                    SearchError.SERVER_UNAVAILABLE,
                                    "Lỗi xử lý phản hồi tìm kiếm: ${e.message}",
                                    200,
                                )
                            }
                        }
                        401, 403 -> SearchOutcome.Failure(
                            SearchError.UNAUTHORIZED,
                            "Máy chủ tìm kiếm từ chối truy cập (HTTP ${response.code}).",
                            response.code,
                        )
                        429 -> {
                            recordFailureCooling()
                            SearchOutcome.Failure(
                                SearchError.RATE_LIMITED,
                                "Tìm kiếm quá nhiều (HTTP 429). Máy chủ nghỉ ${cooldownRemainingSec()}s.",
                                429,
                            )
                        }
                        in 500..599 -> {
                            recordFailureCooling()
                            SearchOutcome.Failure(
                                SearchError.SERVER_UNAVAILABLE,
                                "Máy chủ tìm kiếm lỗi (HTTP ${response.code}), thử lại sau.",
                                response.code,
                            )
                        }
                        else -> SearchOutcome.Failure(
                            SearchError.SERVER_UNAVAILABLE,
                            "Tìm kiếm trả về mã phản hồi không xác định: ${response.code}.",
                            response.code,
                        )
                    }
                }
            } catch (e: SocketTimeoutException) {
                recordFailureCooling()
                SearchOutcome.Failure(SearchError.TIMEOUT, "Kết nối tìm kiếm quá thời gian chờ (Timeout).")
            } catch (e: IOException) {
                SearchOutcome.Failure(SearchError.NETWORK_OFFLINE, "Không có kết nối mạng tới máy chủ tìm kiếm.")
            } catch (e: Exception) {
                SearchOutcome.Failure(SearchError.SERVER_UNAVAILABLE, "Lỗi không xác định khi tìm kiếm: ${e.message}")
            }
        }

    private fun buildSearchUrl(
        query: String,
        limit: Int,
        lang: String?,
        bias: GeoPoint?,
        countryCode: String?,
    ): okhttp3.HttpUrl? {
        val base = (baseUrl.trimEnd('/') + "/api").toHttpUrlOrNull() ?: return null
        val builder = base.newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("limit", limit.coerceIn(1, 20).toString())
        // Only server-supported codes are ever sent; the public endpoint
        // answers HTTP 400 to anything else, including "vi".
        lang?.lowercase()?.takeIf { it in SUPPORTED_LANGS }
            ?.let { builder.addQueryParameter("lang", it) }
        // Photon filters by ISO 3166-1 alpha-2 code. V1 search is Vietnam-only;
        // an invalid code is dropped, never widened or guessed.
        countryCode
            ?.trim()
            ?.uppercase()
            ?.takeIf { it.length == 2 && it.all { c -> c in 'A'..'Z' } }
            ?.let { builder.addQueryParameter("countrycode", it) }
        if (bias != null) {
            builder.addQueryParameter("lat", bias.latitude.toString())
            builder.addQueryParameter("lon", bias.longitude.toString())
        }
        return builder.build()
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://photon.komoot.io"
        const val DEFAULT_LIMIT = 8

        /**
         * Language codes photon.komoot.io accepts. Live evidence 2026-10-17:
         * `lang=vi` returns HTTP 400 ("Language is not supported. Supported
         * are: default, de, en, fr"), so no lang is sent by default and only
         * these codes may be appended.
         */
        val SUPPORTED_LANGS = setOf("de", "en", "fr")
        const val BASE_COOLDOWN_MS = 30_000L
        const val MAX_COOLDOWN_MS = 300_000L
        const val USER_AGENT = "VuMap-Android/0.1.0 (vn.vumap)"

        fun defaultClient(timeoutSeconds: Long = 10): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .build()
    }
}

/**
 * Parses Photon GeoJSON (`FeatureCollection`) into [SearchResult] list.
 *
 * Verification rule (honest pin precision only):
 * - VERIFIED when the feature carries a `housenumber`, a named precise POI
 *   (`osm_key` amenity/shop/tourism/leisure/building…), or a named
 *   house-level `place`.
 * - STREET_ONLY otherwise (street/road geometry without house evidence).
 *
 * A named coarse `place` (city/town/village/state/country) is NOT a precise
 * pin: a city centroid must never read like an exact address.
 */
object PhotonResponseParser {

    private val precisePoiKeys = setOf(
        "amenity", "shop", "tourism", "leisure", "building", "office", "craft",
        "healthcare", "sport", "religion", "historic",
    )

    /**
     * `place` values that describe an area, not a specific pin. A named
     * city/village result may still match the query, but its pin is not exact
     * address evidence.
     */
    private val coarsePlaceValues = setOf(
        "city", "town", "village", "locality", "hamlet", "state", "country",
        "county", "district", "region", "continent", "municipality", "borough",
        "suburb", "quarter", "neighbourhood", "island", "islet",
    )

    fun parse(jsonString: String): List<SearchResult> {
        val root = JSONObject(jsonString)
        val features = root.optJSONArray("features") ?: return emptyList()
        val out = ArrayList<SearchResult>(features.length())
        for (i in 0 until features.length()) {
            val feature = features.optJSONObject(i) ?: continue
            val geometry = feature.optJSONObject("geometry") ?: continue
            val coords = geometry.optJSONArray("coordinates") ?: continue
            if (coords.length() < 2) continue
            val lat: Double
            val lng: Double
            try {
                lng = coords.getDouble(0)
                lat = coords.getDouble(1)
            } catch (e: Exception) {
                continue
            }
            if (lat !in -90.0..90.0 || lng !in -180.0..180.0) continue

            val props = feature.optJSONObject("properties") ?: JSONObject()
            val name = props.optString("name", "").trim()
            val street = props.optString("street", "").trim()
            val houseNumber = props.optString("housenumber", "").trim()
            val district = props.optString("district", "").trim()
            val city = props.optString("city", "").trim().ifEmpty {
                props.optString("state", "").trim()
            }
            val country = props.optString("country", "").trim()
            val countryCode = props.optString("countrycode", "").trim()
                .lowercase()
                .takeIf { it.length == 2 && it.all { c -> c.isLetter() } }
            val osmKey = props.optString("osm_key", "").trim()
            val osmValue = props.optString("osm_value", "").trim()
            val osmId = props.optLong("osm_id", -1L)

            val title = when {
                name.isNotEmpty() -> name
                street.isNotEmpty() && houseNumber.isNotEmpty() -> "$houseNumber $street"
                street.isNotEmpty() -> street
                city.isNotEmpty() -> city
                else -> continue
            }
            val detailParts = listOf(
                if (houseNumber.isNotEmpty() && !title.contains(houseNumber)) {
                    listOf(street, houseNumber).filter { it.isNotEmpty() }.joinToString(" ").trim()
                } else {
                    street.ifEmpty { null }
                },
                district.ifEmpty { null },
                city.ifEmpty { null },
                country.ifEmpty { null },
            ).filterNotNull().filter { it.isNotEmpty() }

            val namedPrecisePoi = name.isNotEmpty() && osmKey.lowercase() in precisePoiKeys
            val namedPrecisePlace = name.isNotEmpty() &&
                osmKey.equals("place", ignoreCase = true) &&
                osmValue.lowercase() !in coarsePlaceValues
            val verified = houseNumber.isNotEmpty() || namedPrecisePoi || namedPrecisePlace
            val localityKey = city.ifEmpty { null }?.let { VietnameseNormalizer.matchingForm(it) }
            val id = "photon:$osmKey/$osmValue/$osmId/$lat/$lng"

            out.add(
                SearchResult(
                    id = id,
                    name = title,
                    detail = detailParts.joinToString(", "),
                    point = GeoPoint(lat, lng),
                    source = SearchSource.PHOTON,
                    verification = if (verified) VerificationLevel.VERIFIED else VerificationLevel.STREET_ONLY,
                    localityKey = localityKey,
                    countryCode = countryCode,
                    countryName = country.ifEmpty { null },
                ),
            )
        }
        return out
    }
}
