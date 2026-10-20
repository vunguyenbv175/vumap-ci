package vn.vumap.feature.nav

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import vn.vumap.core.model.GeoPoint
import java.util.concurrent.TimeUnit

/**
 * End-to-end provider and chain unit tests with MockWebServer (project plan section 9; requirement 2 & 5).
 *
 * Verifies:
 * - HTTP 200, 401, 429, 500, socket timeout, and offline behavior.
 * - Per-host cooldown activation upon 429 and timeout.
 * - Skipping optional ORS when key is missing, without triggering network call.
 * - Strict key privacy (secret is never leaked in errors, logs, or string output).
 * - "Khong nhan OFFLINE ROUTING khi chi cache": cached routes are explicitly marked `isFromCache = true`.
 */
class MockWebServerRoutingTest {

    private lateinit var server: MockWebServer

    private val fromPoint = GeoPoint(10.772500, 106.698000) // Bến Thành
    private val toPoint = GeoPoint(10.776800, 106.703200)   // Nhà hát TP

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun readFixture(name: String): String {
        val stream = javaClass.classLoader?.getResourceAsStream("fixtures/$name")
            ?: throw IllegalStateException("Fixture fixtures/$name not found on classpath")
        return stream.bufferedReader().use { it.readText() }
    }

    @Test
    fun testOsrmSuccess200() = runBlocking {
        val fixture = readFixture("osrm_hcm.json")
        server.enqueue(MockResponse().setResponseCode(200).setBody(fixture))

        val provider = OsrmRoutingProvider(baseUrl = server.url("/").toString())
        val result = provider.route(fromPoint, toPoint)

        assertTrue(result is RouteResult.Success)
        val success = result as RouteResult.Success
        assertEquals("OSRM (public)", success.provider)
        assertFalse(success.isFromCache)
        assertEquals(800.0, success.route.distanceMeters, 1.0)
    }

    @Test
    fun testOsrmRateLimited429() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setBody("Rate limited"))

        val provider = OsrmRoutingProvider(baseUrl = server.url("/").toString())
        val result = provider.route(fromPoint, toPoint)

        assertTrue(result is RouteResult.Failure)
        val failure = result as RouteResult.Failure
        assertEquals(RoutingError.RATE_LIMITED, failure.error)
        assertEquals(429, failure.httpStatusCode)
    }

    @Test
    fun testOsrmTimeout() = runBlocking {
        server.enqueue(
            MockResponse()
                .setSocketPolicy(SocketPolicy.NO_RESPONSE),
        )

        val testClient = OkHttpClient.Builder()
            .connectTimeout(500, TimeUnit.MILLISECONDS)
            .readTimeout(500, TimeUnit.MILLISECONDS)
            .build()

        val provider = OsrmRoutingProvider(
            baseUrl = server.url("/").toString(),
            client = testClient,
        )
        val result = provider.route(fromPoint, toPoint)

        assertTrue(result is RouteResult.Failure)
        val failure = result as RouteResult.Failure
        assertEquals(RoutingError.TIMEOUT, failure.error)
    }

    @Test
    fun testOsrmOffline() = runBlocking {
        // Pointing to a dead port immediately after shutdown simulates offline
        val deadUrl = server.url("/").toString()
        server.shutdown()

        val provider = OsrmRoutingProvider(baseUrl = deadUrl)
        val result = provider.route(fromPoint, toPoint)

        assertTrue(result is RouteResult.Failure)
        val failure = result as RouteResult.Failure
        assertEquals(RoutingError.NETWORK_OFFLINE, failure.error)
    }

    @Test
    fun testValhallaSuccess200() = runBlocking {
        val fixture = readFixture("valhalla_hcm.json")
        server.enqueue(MockResponse().setResponseCode(200).setBody(fixture))

        val provider = ValhallaRoutingProvider(baseUrl = server.url("/").toString())
        val result = provider.route(fromPoint, toPoint)

        assertTrue(result is RouteResult.Success)
        val success = result as RouteResult.Success
        assertEquals("Valhalla (format=osrm)", success.provider)
        assertEquals(810.0, success.route.distanceMeters, 1.0)

        // Verify request payload used format: osrm
        val recorded = server.takeRequest()
        assertEquals("/route", recorded.path)
        val bodyStr = recorded.body.readUtf8()
        assertTrue(bodyStr.contains("\"format\":\"osrm\""))
        assertTrue(bodyStr.contains("\"shape_format\":\"polyline6\""))
    }

    @Test
    fun testValhallaRateLimited429() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setBody("Too many requests"))

        val provider = ValhallaRoutingProvider(baseUrl = server.url("/").toString())
        val result = provider.route(fromPoint, toPoint)

        assertTrue(result is RouteResult.Failure)
        assertEquals(RoutingError.RATE_LIMITED, (result as RouteResult.Failure).error)
    }

    @Test
    fun testOrsSkipsWhenKeyMissing() = runBlocking {
        var key: String? = null
        val provider = OrsRoutingProvider(
            apiKeyProvider = { key },
            baseUrl = server.url("/").toString(),
        )

        // Empty / null key
        val result = provider.route(fromPoint, toPoint)
        assertTrue(result is RouteResult.Failure)
        assertEquals(RoutingError.KEY_MISSING, (result as RouteResult.Failure).error)

        // Zero network requests made
        assertEquals(0, server.requestCount)

        // Blank key
        key = "   "
        val resultBlank = provider.route(fromPoint, toPoint)
        assertTrue(resultBlank is RouteResult.Failure)
        assertEquals(RoutingError.KEY_MISSING, (resultBlank as RouteResult.Failure).error)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun testOrsSuccessWithKey() = runBlocking {
        val fixture = readFixture("ors_hcm.json")
        server.enqueue(MockResponse().setResponseCode(200).setBody(fixture))

        val testKey = "5b3ce3597851110001cf6248personaltestkey12345"
        val provider = OrsRoutingProvider(
            apiKeyProvider = { testKey },
            baseUrl = server.url("/").toString(),
        )

        val result = provider.route(fromPoint, toPoint)
        assertTrue(result is RouteResult.Success)
        val success = result as RouteResult.Success
        assertEquals("OpenRouteService", success.provider)
        assertEquals(800.0, success.route.distanceMeters, 1.0)

        val request = server.takeRequest()
        assertEquals(testKey, request.getHeader("Authorization"))
    }

    @Test
    fun testOrsUnauthorized401DoesNotLeakKey() = runBlocking {
        val testSecretKey = "super-secret-personal-ors-key-998877"
        server.enqueue(MockResponse().setResponseCode(401).setBody("{\"error\": \"Unauthorized\"}"))

        val provider = OrsRoutingProvider(
            apiKeyProvider = { testSecretKey },
            baseUrl = server.url("/").toString(),
        )

        val result = provider.route(fromPoint, toPoint)
        assertTrue(result is RouteResult.Failure)
        val failure = result as RouteResult.Failure
        assertEquals(RoutingError.UNAUTHORIZED, failure.error)

        // Verify strictly that secret key does not appear anywhere in failure message or provider toString
        assertFalse("Message must NOT leak secret key", failure.message.contains(testSecretKey))
        assertFalse("toString must NOT leak secret key", provider.toString().contains(testSecretKey))
    }

    @Test
    fun testRoutingChainFallbackAndCooldown() = runBlocking {
        // Setup: ORS has no key (clean skip) -> OSRM returns 429 -> Valhalla succeeds 200
        server.enqueue(MockResponse().setResponseCode(429).setBody("OSRM 429"))
        val valhallaFixture = readFixture("valhalla_hcm.json")
        server.enqueue(MockResponse().setResponseCode(200).setBody(valhallaFixture))

        val ors = OrsRoutingProvider(apiKeyProvider = { null }, baseUrl = server.url("/ors").toString())
        val osrm = OsrmRoutingProvider(baseUrl = server.url("/osrm").toString())
        val valhalla = ValhallaRoutingProvider(baseUrl = server.url("/valhalla").toString())

        var mockCurrentTime = 100_000L
        val chain = RoutingChain(
            providers = listOf(ors, osrm, valhalla),
            cooldownDurationMs = 30_000L,
            timeProvider = { mockCurrentTime },
        )

        val firstResult = chain.route(fromPoint, toPoint)
        assertTrue("Valhalla fallback should succeed", firstResult is RouteResult.Success)
        val success = firstResult as RouteResult.Success
        assertEquals("Valhalla (format=osrm)", success.provider)

        // Verify OSRM is now in cooldown
        assertTrue(chain.isProviderCoolingDown("OSRM (public)"))

        // Second call: 5 seconds later (within 30s cooldown)
        mockCurrentTime += 5_000L
        server.enqueue(MockResponse().setResponseCode(200).setBody(valhallaFixture))

        val secondResult = chain.route(fromPoint, toPoint)
        assertTrue(secondResult is RouteResult.Success)
        // OSRM was skipped directly, Valhalla was invoked
        assertEquals("Valhalla (format=osrm)", (secondResult as RouteResult.Success).provider)

        // After cooldown expires (35s total elapsed): OSRM is tried again
        mockCurrentTime += 30_000L
        assertFalse(chain.isProviderCoolingDown("OSRM (public)"))
    }

    @Test
    fun testRoutingChainOfflineHonestCache() = runBlocking {
        // First request: OSRM succeeds and gets cached
        val fixture = readFixture("osrm_hcm.json")
        server.enqueue(MockResponse().setResponseCode(200).setBody(fixture))

        val osrm = OsrmRoutingProvider(baseUrl = server.url("/osrm").toString())
        val chain = RoutingChain(providers = listOf(osrm))

        val initialResult = chain.route(fromPoint, toPoint)
        assertTrue(initialResult is RouteResult.Success)
        assertFalse((initialResult as RouteResult.Success).isFromCache)

        // Now network is offline (server shutdown)
        server.shutdown()

        // Same destination: fallback returns cached route with explicit isFromCache flag
        val offlineResult = chain.route(fromPoint, toPoint)
        assertTrue(offlineResult is RouteResult.Success)
        val cached = offlineResult as RouteResult.Success
        assertTrue("Must be flagged as from cache (offline-honest rule)", cached.isFromCache)
        assertEquals("Tuyến đã lưu tạm (Cache)", cached.provider)

        // Different destination with no cache: returns failure, NOT false offline routing
        val differentDest = GeoPoint(21.0285, 105.8542)
        val failResult = chain.route(fromPoint, differentDest)
        assertTrue(failResult is RouteResult.Failure)
        assertEquals(RoutingError.NETWORK_OFFLINE, (failResult as RouteResult.Failure).error)
    }

    @Test
    fun testRouteCoordinatorDebounceAndTickFilter() = runBlocking {
        val fixture = readFixture("osrm_hcm.json")
        server.enqueue(MockResponse().setResponseCode(200).setBody(fixture))

        val osrm = OsrmRoutingProvider(baseUrl = server.url("/osrm").toString())
        var mockTime = 10_000L
        val chain = RoutingChain(listOf(osrm), timeProvider = { mockTime })
        val coordinator = RouteCoordinator(
            routingChain = chain,
            debounceIntervalMs = 3_000L,
            timeProvider = { mockTime },
        )

        // Request 1: Succeeds
        val res1 = coordinator.requestRoute(fromPoint, toPoint)
        assertTrue(res1 is RouteResult.Success)
        assertTrue(coordinator.state.value is RouteCoordinator.NavigationState.Navigating)

        // Request 2: Called 1 second later -> Debounced!
        mockTime += 1_000L
        val res2 = coordinator.requestRoute(fromPoint, toPoint)
        assertTrue(res2 is RouteResult.Failure)
        assertEquals(RoutingError.RATE_LIMITED, (res2 as RouteResult.Failure).error)

        // GPS tick 1: Driver is right on route (Bến Thành)
        mockTime += 1_000L
        val reRouted1 = coordinator.onGpsTick(fromPoint)
        assertFalse("On-route GPS tick should NOT trigger route request", reRouted1)

        // GPS tick 2: Driver goes off-route (>40m) after debounce interval (3.5s later)
        mockTime += 3_500L
        server.enqueue(MockResponse().setResponseCode(200).setBody(fixture))
        val farPoint = GeoPoint(10.8000, 106.6500) // 5+ km away
        val reRouted2 = coordinator.onGpsTick(farPoint)
        assertTrue("Off-route GPS tick should trigger re-route", reRouted2)

        // The launched single flight settles asynchronously in the stable
        // owner scope: result published, navigation never wedged.
        coordinator.awaitRouteIdle()
        assertTrue(coordinator.state.value is RouteCoordinator.NavigationState.Navigating)
        coordinator.close()
    }
}
