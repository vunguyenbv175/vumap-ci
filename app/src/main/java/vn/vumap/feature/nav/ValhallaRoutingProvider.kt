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
 * Public Valhalla routing provider using OpenStreetMap Germany demo server.
 *
 * DEMO SERVER LIMITATIONS (project plan section 3, 9; requirement 2 & 3):
 * - Hosted at `https://valhalla1.openstreetmap.de`.
 * - Maintained on a best-effort basis by the OpenStreetMap Germany community; NO SLA or uptime guarantee.
 * - Concurrency and request rates are bounded. Exceeding fair-use limits triggers HTTP 429.
 * - Configured with `format: "osrm"` and `shape_format: "polyline6"` to enable parser reuse
 *   with OSRM, verified by golden fixtures for identical units (meters, seconds) and precision.
 * - Client MUST debounce requests and never invoke per GPS tick.
 */
class ValhallaRoutingProvider(
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val client: OkHttpClient = defaultClient(),
) : RoutingProvider {

    override val providerName: String = "Valhalla (format=osrm)"

    override suspend fun route(
        from: GeoPoint,
        to: GeoPoint,
        options: CarOptions,
    ): RouteResult = withContext(Dispatchers.IO) {
        val url = "${baseUrl.trimEnd('/')}/route"

        val jsonBody = JSONObject().apply {
            val locations = JSONArray().apply {
                put(JSONObject().apply {
                    put("lat", from.latitude)
                    put("lon", from.longitude)
                })
                put(JSONObject().apply {
                    put("lat", to.latitude)
                    put("lon", to.longitude)
                })
            }
            put("locations", locations)
            put("costing", "auto")
            put("format", "osrm")
            put("shape_format", "polyline6")
        }.toString()

        val requestBody = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .header("User-Agent", "VuMap-Android/0.1.0 (vn.vumap)")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> {
                        val body = response.body?.string()
                            ?: return@withContext RouteResult.Failure(
                                RoutingError.SERVER_UNAVAILABLE,
                                "Valhalla trả về nội dung rỗng",
                                providerName,
                                200,
                            )
                        try {
                            // Reuses OsrmResponseParser thanks to format:"osrm"
                            val route = OsrmResponseParser.parse(body)
                            RouteResult.Success(route = route, provider = providerName)
                        } catch (e: Exception) {
                            RouteResult.Failure(
                                RoutingError.NO_ROUTE_FOUND,
                                "Lỗi xử lý phản hồi Valhalla: ${e.message}",
                                providerName,
                                200,
                            )
                        }
                    }
                    429 -> RouteResult.Failure(
                        RoutingError.RATE_LIMITED,
                        "Valhalla quá tải (HTTP 429 Rate Limited)",
                        providerName,
                        429,
                    )
                    in 400..499 -> RouteResult.Failure(
                        RoutingError.NO_ROUTE_FOUND,
                        "Valhalla không tìm thấy tuyến đường (HTTP ${response.code})",
                        providerName,
                        response.code,
                    )
                    in 500..599 -> RouteResult.Failure(
                        RoutingError.SERVER_UNAVAILABLE,
                        "Valhalla máy chủ lỗi (HTTP ${response.code})",
                        providerName,
                        response.code,
                    )
                    else -> RouteResult.Failure(
                        RoutingError.SERVER_UNAVAILABLE,
                        "Valhalla trả về mã phản hồi không xác định: ${response.code}",
                        providerName,
                        response.code,
                    )
                }
            }
        } catch (e: SocketTimeoutException) {
            RouteResult.Failure(
                RoutingError.TIMEOUT,
                "Kết nối tới Valhalla quá thời gian chờ (Timeout)",
                providerName,
            )
        } catch (e: IOException) {
            RouteResult.Failure(
                RoutingError.NETWORK_OFFLINE,
                "Không có kết nối mạng tới Valhalla: ${e.message}",
                providerName,
            )
        } catch (e: Exception) {
            RouteResult.Failure(
                RoutingError.SERVER_UNAVAILABLE,
                "Lỗi không xác định khi gọi Valhalla: ${e.message}",
                providerName,
            )
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://valhalla1.openstreetmap.de"

        fun defaultClient(timeoutSeconds: Long = 10): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .build()
    }
}
