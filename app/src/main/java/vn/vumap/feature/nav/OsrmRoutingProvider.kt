package vn.vumap.feature.nav

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import vn.vumap.core.model.GeoPoint
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Public OSRM routing provider (Project OSRM demo server).
 *
 * DEMO SERVER LIMITATIONS (project plan section 3, 9; requirement 2 & 3):
 * - Hosted at `https://router.project-osrm.org`.
 * - Strictly a demo server for development and testing; NO uptime or SLA guarantee.
 * - Subject to fair-use rate limiting (approx. 1 request/second). Heavy traffic will trigger HTTP 429
 *   or temporary IP ban.
 * - Calling this on every GPS tick is strictly prohibited. Client MUST debounce and use fallback.
 */
class OsrmRoutingProvider(
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val client: OkHttpClient = defaultClient(),
) : RoutingProvider {

    override val providerName: String = "OSRM (public)"

    override suspend fun route(
        from: GeoPoint,
        to: GeoPoint,
        options: CarOptions,
    ): RouteResult = withContext(Dispatchers.IO) {
        // OSRM expects coordinates in {lon},{lat} order
        val url = String.format(
            Locale.US,
            "%s/route/v1/driving/%.6f,%.6f;%.6f,%.6f?overview=full&geometries=polyline6&steps=true",
            baseUrl.trimEnd('/'),
            from.longitude,
            from.latitude,
            to.longitude,
            to.latitude,
        )

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "VuMap-Android/0.1.0 (vn.vumap)")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> {
                        val body = response.body?.string()
                            ?: return@withContext RouteResult.Failure(
                                RoutingError.SERVER_UNAVAILABLE,
                                "OSRM trả về nội dung rỗng",
                                providerName,
                                200,
                            )
                        try {
                            val route = OsrmResponseParser.parse(body)
                            RouteResult.Success(route = route, provider = providerName)
                        } catch (e: Exception) {
                            RouteResult.Failure(
                                RoutingError.NO_ROUTE_FOUND,
                                "Lỗi xử lý phản hồi OSRM: ${e.message}",
                                providerName,
                                200,
                            )
                        }
                    }
                    429 -> RouteResult.Failure(
                        RoutingError.RATE_LIMITED,
                        "OSRM quá tải (HTTP 429 Rate Limited)",
                        providerName,
                        429,
                    )
                    in 400..499 -> RouteResult.Failure(
                        RoutingError.NO_ROUTE_FOUND,
                        "OSRM không tìm thấy tuyến đường (HTTP ${response.code})",
                        providerName,
                        response.code,
                    )
                    in 500..599 -> RouteResult.Failure(
                        RoutingError.SERVER_UNAVAILABLE,
                        "OSRM máy chủ lỗi (HTTP ${response.code})",
                        providerName,
                        response.code,
                    )
                    else -> RouteResult.Failure(
                        RoutingError.SERVER_UNAVAILABLE,
                        "OSRM trả về mã phản hồi không xác định: ${response.code}",
                        providerName,
                        response.code,
                    )
                }
            }
        } catch (e: SocketTimeoutException) {
            RouteResult.Failure(
                RoutingError.TIMEOUT,
                "Kết nối tới OSRM quá thời gian chờ (Timeout)",
                providerName,
            )
        } catch (e: IOException) {
            RouteResult.Failure(
                RoutingError.NETWORK_OFFLINE,
                "Không có kết nối mạng tới OSRM: ${e.message}",
                providerName,
            )
        } catch (e: Exception) {
            RouteResult.Failure(
                RoutingError.SERVER_UNAVAILABLE,
                "Lỗi không xác định khi gọi OSRM: ${e.message}",
                providerName,
            )
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://router.project-osrm.org"

        fun defaultClient(timeoutSeconds: Long = 10): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .build()
    }
}
