package vn.vumap.feature.search

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import vn.vumap.core.model.GeoPoint
import java.util.concurrent.TimeUnit

/**
 * Photon provider unit tests with MockWebServer (project plan section 11):
 * HTTP 200/401/429/500, timeout, offline, per-host cooldown with backoff,
 * reverse geocode, and strict no-key behaviour (no Authorization header ever).
 */
class PhotonSearchProviderTest {

    private lateinit var server: MockWebServer

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

    private fun provider(
        now: Long = 0L,
        advance: ((Long) -> Long)? = null,
    ): PhotonSearchProvider {
        var clock = now
        return PhotonSearchProvider(
            baseUrl = server.url("/").toString(),
            timeProvider = { advance?.invoke(clock) ?: clock },
        )
    }

    @Test
    fun success200ParsesResultsWithHonestVerification() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(readFixture("photon_hcm.json")))

        val outcome = provider().search("Nguyễn Thị Nghĩa")

        assertTrue(outcome is SearchOutcome.Success)
        val results = (outcome as SearchOutcome.Success).results
        assertEquals(3, results.size)
        // House-number hit is verified.
        assertEquals(VerificationLevel.VERIFIED, results[0].verification)
        assertTrue(results[0].name.contains("51/8"))
        // Bare street geometry without house evidence stays street-level.
        assertEquals(VerificationLevel.STREET_ONLY, results[1].verification)
        // Named POI with amenity key is verified.
        assertEquals("Chợ Bến Thành", results[2].name)
        assertEquals(VerificationLevel.VERIFIED, results[2].verification)

        // Photon public needs no key: never send Authorization.
        val request = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(request)
        assertNull(request!!.getHeader("Authorization"))
        assertTrue(request.path!!.contains("q="))
        assertTrue(request.getHeader("User-Agent")!!.startsWith("VuMap-Android"))
    }

    @Test
    fun emptyFeaturesIsNoResults() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"features": [], "type": "FeatureCollection"}"""),
        )
        val outcome = provider().search("Nơi Không Tồn Tại 12345")
        assertTrue(outcome is SearchOutcome.Failure)
        assertEquals(SearchError.NO_RESULTS, (outcome as SearchOutcome.Failure).error)
    }

    @Test
    fun unauthorized401WithoutCooldown() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("Denied"))
        val p = provider()
        val outcome = p.search("Chợ Bến Thành")
        assertTrue(outcome is SearchOutcome.Failure)
        val failure = outcome as SearchOutcome.Failure
        assertEquals(SearchError.UNAUTHORIZED, failure.error)
        assertEquals(401, failure.httpStatusCode)
        assertFalse(p.isCoolingDown())
    }

    @Test
    fun rateLimited429TriggersCooldownAndSkipsNetwork() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setBody("Rate limited"))
        val p = provider()
        val first = p.search("Chợ Bến Thành")
        assertEquals(SearchError.RATE_LIMITED, (first as SearchOutcome.Failure).error)
        assertTrue(p.isCoolingDown())

        // Second search is parked locally: no second HTTP request.
        val second = p.search("Chợ Bến Thành")
        assertEquals(SearchError.COOLDOWN_ACTIVE, (second as SearchOutcome.Failure).error)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun backoffDoublesOnConsecutiveFailures() = runBlocking {
        var now = 0L
        val p = PhotonSearchProvider(
            baseUrl = server.url("/").toString(),
            timeProvider = { now },
        )
        server.enqueue(MockResponse().setResponseCode(429))
        p.search("a")
        assertEquals(30L, p.cooldownRemainingSec())

        now = 31_000L // past the 30s park
        server.enqueue(MockResponse().setResponseCode(429))
        p.search("a")
        assertEquals(60L, p.cooldownRemainingSec())

        p.resetCooldown()
        assertFalse(p.isCoolingDown())
    }

    @Test
    fun timeoutMapsToTimeoutErrorWithCooldown() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val fastClient = OkHttpClient.Builder()
            .connectTimeout(300, TimeUnit.MILLISECONDS)
            .readTimeout(300, TimeUnit.MILLISECONDS)
            .build()
        val p = PhotonSearchProvider(
            baseUrl = server.url("/").toString(),
            client = fastClient,
        )
        val outcome = p.search("Chợ Bến Thành")
        assertEquals(SearchError.TIMEOUT, (outcome as SearchOutcome.Failure).error)
        assertTrue(p.isCoolingDown())
    }

    @Test
    fun offlineMapsToNetworkOfflineWithoutCooldown() = runBlocking {
        val deadUrl = server.url("/").toString()
        server.shutdown()
        val p = PhotonSearchProvider(baseUrl = deadUrl)
        val outcome = p.search("Chợ Bến Thành")
        assertEquals(SearchError.NETWORK_OFFLINE, (outcome as SearchOutcome.Failure).error)
        assertFalse(p.isCoolingDown())
    }

    @Test
    fun reverse200ReturnsNearestPlace() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(readFixture("photon_reverse.json")))
        val p = provider()
        val outcome = p.reverse(GeoPoint(10.7725, 106.6980))
        assertTrue(outcome is SearchOutcome.Success)
        val results = (outcome as SearchOutcome.Success).results
        assertEquals(1, results.size)
        assertEquals("Chợ Bến Thành", results[0].name)
        val request = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(request)
        assertTrue(request!!.path!!.contains("/reverse"))
    }

    @Test
    fun defaultSearchOmitsLangTheServerWouldReject() = runBlocking {
        // Live evidence 2026-10-17: photon.komoot.io answers HTTP 400 to
        // `lang=vi` ("Language is not supported. Supported are: default, de,
        // en, fr"), and that 400 lands in the generic failure branch without a
        // cooldown, so every live search would fail permanently. The default
        // request must therefore carry no lang at all.
        server.enqueue(MockResponse().setResponseCode(200).setBody(readFixture("photon_hcm.json")))

        val outcome = provider().search("Nguyễn Thị Nghĩa")

        assertTrue(outcome is SearchOutcome.Success)
        val request = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(request)
        assertNull(request!!.requestUrl?.queryParameter("lang"))
    }

    @Test
    fun supportedLangIsSentButUnsupportedLangIsDropped() = runBlocking {
        // A code from Photon's supported set may be requested and must be
        // passed through; anything else (e.g. "vi") is dropped, never sent.
        server.enqueue(MockResponse().setResponseCode(200).setBody(readFixture("photon_hcm.json")))
        assertTrue(provider().search("Chợ Bến Thành", lang = "en") is SearchOutcome.Success)
        val en = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(en)
        assertEquals("en", en!!.requestUrl?.queryParameter("lang"))

        server.enqueue(MockResponse().setResponseCode(200).setBody(readFixture("photon_hcm.json")))
        assertTrue(provider().search("Chợ Bến Thành", lang = "vi") is SearchOutcome.Success)
        val vi = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(vi)
        assertNull(vi!!.requestUrl?.queryParameter("lang"))
        assertFalse("unsupported lang must not appear on the wire", vi.path!!.contains("lang="))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun countryCodeFilterIsSentOnlyWhenValid() = runBlocking {
        // V1 search is Vietnam-only: Photon supports an ISO 3166-1 alpha-2
        // `countrycode` filter, so the worldwide demo index cannot return a UK
        // pub for a HCMC landmark query before the app even sees it.
        server.enqueue(MockResponse().setResponseCode(200).setBody(readFixture("photon_hcm.json")))
        assertTrue(provider().search("Landmark 81", countryCode = "vn") is SearchOutcome.Success)
        val scoped = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(scoped)
        assertEquals("VN", scoped!!.requestUrl?.queryParameter("countrycode"))

        // Invalid/ambiguous codes are dropped, never widened or guessed.
        server.enqueue(MockResponse().setResponseCode(200).setBody(readFixture("photon_hcm.json")))
        assertTrue(provider().search("Landmark 81", countryCode = "VNM") is SearchOutcome.Success)
        val invalid = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(invalid)
        assertNull(invalid!!.requestUrl?.queryParameter("countrycode"))
    }

    @Test
    fun badRequest400FailsOnceWithoutRetryHammer() = runBlocking {
        // Representative live 400 (captured body, sanitized): one typed
        // failure per user search — the provider never retries inside a
        // search and never hammers the server — and a later search recovers
        // once the request shape is correct.
        server.enqueue(
            MockResponse().setResponseCode(400).setBody(readFixture("photon_bad_request_400.json")),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody(readFixture("photon_hcm.json")))

        val p = provider()
        val failed = p.search("Chợ Bến Thành, Quận 1")

        assertTrue(failed is SearchOutcome.Failure)
        val failure = failed as SearchOutcome.Failure
        assertEquals(SearchError.SERVER_UNAVAILABLE, failure.error)
        assertEquals(400, failure.httpStatusCode)
        assertEquals("no automatic retry on 400", 1, server.requestCount)

        val recovered = p.search("Chợ Bến Thành, Quận 1")
        assertTrue(recovered is SearchOutcome.Success)
        assertEquals("one request per user search", 2, server.requestCount)
    }
}
