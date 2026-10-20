package vn.vumap.feature.nav

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import vn.vumap.core.model.GeoPoint
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * OpenRouteService (ORS) routing provider with optional personal API key.
 *
 * RULES (project plan section 0.4, 2, 9; requirement 2):
 * - Optional personal key. If key is missing or blank, SKIPS execution cleanly without network call.
 * - Strict key privacy: the key is NEVER logged, emitted in error messages, or exposed via toString().
 * - When free quota is exhausted (HTTP 429) or unauthorized (HTTP 401/403), reports the error cleanly
 *   so the routing chain falls back to public providers (OSRM, Valhalla).
 */
class OrsRoutingProvider(
    private val apiKeyProvider: () -> String?,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val client: OkHttpClient = defaultClient(),
) : RoutingProvider {

    override val providerName: String = "OpenRouteService"

    override suspend fun route(
        from: GeoPoint,
        to: GeoPoint,
        options: CarOptions,
    ): RouteResult = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider()?.trim()
        if (apiKey.isNullOrEmpty()) {
            return@withContext RouteResult.Failure(
                error = RoutingError.KEY_MISSING,
                message = "ORS API key chưa được cấu hình, bỏ qua provider",
                provider = providerName,
            )
        }

        val url = "${baseUrl.trimEnd('/')}/v2/directions/driving-car/geojson"

        val jsonBody = JSONObject().apply {
            val coords = JSONArray().apply {
                put(JSONArray().apply {
                    put(from.longitude)
                    put(from.latitude)
                })
                put(JSONArray().apply {
                    put(to.longitude)
                    put(to.latitude)
                })
            }
            put("coordinates", coords)
            put("language", "vi")
            put("units", "m")
        }.toString()

        val requestBody = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .header("Authorization", apiKey)
            .header("User-Agent", "VuMap-Android/0.1.0 (vn.vumap)")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> {
                        val body = response.body?.string()
                            ?: return@withContext RouteResult.Failure(
                                RoutingError.SERVER_UNAVAILABLE,
                                "ORS trả về nội dung rỗng",
                                providerName,
                                200,
                            )
                        try {
                            val route = parseOrsGeoJson(body)
                            RouteResult.Success(route = route, provider = providerName)
                        } catch (e: Exception) {
                            val safeMsg = SecretSanitizer.redact(e.message ?: "unknown", apiKey)
                            RouteResult.Failure(
                                RoutingError.NO_ROUTE_FOUND,
                                "Lỗi xử lý phản hồi ORS: $safeMsg",
                                providerName,
                                200,
                            )
                        }
                    }
                    401, 403 -> RouteResult.Failure(
                        RoutingError.UNAUTHORIZED,
                        "ORS API key không hợp lệ hoặc chưa được kích hoạt (HTTP ${response.code})",
                        providerName,
                        response.code,
                    )
                    429 -> RouteResult.Failure(
                        RoutingError.RATE_LIMITED,
                        "ORS vượt hạn mức yêu cầu (HTTP 429 Quota Exceeded)",
                        providerName,
                        429,
                    )
                    in 400..499 -> RouteResult.Failure(
                        RoutingError.NO_ROUTE_FOUND,
                        "ORS không tìm thấy tuyến đường (HTTP ${response.code})",
                        providerName,
                        response.code,
                    )
                    in 500..599 -> RouteResult.Failure(
                        RoutingError.SERVER_UNAVAILABLE,
                        "ORS máy chủ lỗi (HTTP ${response.code})",
                        providerName,
                        response.code,
                    )
                    else -> RouteResult.Failure(
                        RoutingError.SERVER_UNAVAILABLE,
                        "ORS trả về mã phản hồi không xác định: ${response.code}",
                        providerName,
                        response.code,
                    )
                }
            }
        } catch (e: SocketTimeoutException) {
            RouteResult.Failure(
                RoutingError.TIMEOUT,
                "Kết nối tới ORS quá thời gian chờ (Timeout)",
                providerName,
            )
        } catch (e: IOException) {
            val safeMsg = SecretSanitizer.redact(e.message ?: "network error", apiKey)
            RouteResult.Failure(
                RoutingError.NETWORK_OFFLINE,
                "Không có kết nối mạng tới ORS: $safeMsg",
                providerName,
            )
        } catch (e: Exception) {
            val safeMsg = SecretSanitizer.redact(e.message ?: "unknown", apiKey)
            RouteResult.Failure(
                RoutingError.SERVER_UNAVAILABLE,
                "Lỗi không xác định khi gọi ORS: $safeMsg",
                providerName,
            )
        }
    }

    private fun parseOrsGeoJson(jsonString: String): Route {
        val root = JSONObject(jsonString)
        val features = root.optJSONArray("features")
            ?: throw IllegalArgumentException("No features array in ORS response")
        if (features.length() == 0) throw IllegalArgumentException("Features array empty")

        val feature = features.getJSONObject(0)
        val geometryObj = feature.getJSONObject("geometry")
        val coordsArray = geometryObj.getJSONArray("coordinates")

        val geometry = mutableListOf<GeoPoint>()
        for (i in 0 until coordsArray.length()) {
            val pt = coordsArray.getJSONArray(i)
            geometry.add(GeoPoint(latitude = pt.getDouble(1), longitude = pt.getDouble(0)))
        }

        val properties = feature.optJSONObject("properties")
        val summaryObj = properties?.optJSONObject("summary")
        val totalDistance = summaryObj?.optDouble("distance", 0.0) ?: 0.0
        val totalDuration = summaryObj?.optDouble("duration", 0.0) ?: 0.0

        val steps = mutableListOf<RouteStep>()
        val segments = properties?.optJSONArray("segments")
        if (segments != null) {
            for (i in 0 until segments.length()) {
                val seg = segments.getJSONObject(i)
                val segSteps = seg.optJSONArray("steps") ?: continue
                for (j in 0 until segSteps.length()) {
                    val s = segSteps.getJSONObject(j)
                    val dist = s.optDouble("distance", 0.0)
                    val dur = s.optDouble("duration", 0.0)
                    val instruction = s.optString("instruction", "").trim()
                    val name = s.optString("name", "").trim()
                    val wayPoints = s.optJSONArray("way_points")
                    val stepPt = if (wayPoints != null && wayPoints.length() > 0) {
                        val idx = wayPoints.getInt(0)
                        if (idx in geometry.indices) geometry[idx] else geometry.firstOrNull() ?: GeoPoint(0.0, 0.0)
                    } else {
                        geometry.firstOrNull() ?: GeoPoint(0.0, 0.0)
                    }

                    steps.add(
                        RouteStep(
                            instruction = instruction.ifEmpty { "Đi tiếp $name".trim() },
                            distanceMeters = dist,
                            durationSeconds = dur,
                            location = stepPt,
                            maneuverType = "turn",
                            streetName = name,
                        ),
                    )
                }
            }
        }

        return Route(
            geometry = geometry,
            distanceMeters = totalDistance,
            durationSeconds = totalDuration,
            steps = steps,
            summary = "",
            polylinePrecision = 6,
        )
    }

    override fun toString(): String = "OrsRoutingProvider(baseUrl=$baseUrl, keySet=${!apiKeyProvider().isNullOrBlank()})"

    companion object {
        const val DEFAULT_BASE_URL = "https://api.openrouteservice.org"

        fun defaultClient(timeoutSeconds: Long = 10): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .build()
    }
}
