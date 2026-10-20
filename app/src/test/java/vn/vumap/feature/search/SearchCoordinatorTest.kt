package vn.vumap.feature.search

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import vn.vumap.core.model.GeoPoint

/**
 * Search coordinator tests: sanitizer gate, geo fast-path without network,
 * debounce, hem/ngo street fallback with honest notice, ambiguity grouping,
 * and offline local recall when the network fails.
 */
class SearchCoordinatorTest {

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

    private class FakeRecall(items: List<SearchResult> = emptyList()) : LocalRecall {
        private val store = SearchStore().apply {
            items.forEach { addFavorite(it) }
        }
        val recorded = mutableListOf<SearchResult>()
        override suspend fun findMatches(query: String, limit: Int): List<SearchResult> =
            store.findMatches(query, limit)
        override suspend fun recordHistory(result: SearchResult) {
            recorded.add(result)
        }
    }

    private fun coordinator(
        recall: LocalRecall? = null,
        minIntervalMs: Long = SearchCoordinator.DEFAULT_MIN_INTERVAL_MS,
        clock: LongArray = longArrayOf(0L),
    ): SearchCoordinator {
        // Shared fake clock: tests advance clock[0] to simulate user timing;
        // the injected delay advances it too, so no real waiting happens.
        return SearchCoordinator(
            provider = PhotonSearchProvider(baseUrl = server.url("/").toString()),
            favorites = recall,
            minIntervalMs = minIntervalMs,
            timeProvider = { clock[0] },
            delay = { ms -> clock[0] += ms },
        )
    }

    private fun emptyFeatures() = """{"features": [], "type": "FeatureCollection"}"""

    @Test
    fun emptyQueryIsRejectedWithoutNetwork() = runBlocking {
        val outcome = coordinator().search("   ")
        assertTrue(outcome is SearchOutcome.Failure)
        assertEquals(SearchError.EMPTY_QUERY, (outcome as SearchOutcome.Failure).error)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun coordinateQueryUsesFastPathWithoutNetwork() = runBlocking {
        val recall = FakeRecall()
        val outcome = coordinator(recall).search("10.7725, 106.6980")
        assertTrue(outcome is SearchOutcome.Success)
        val results = (outcome as SearchOutcome.Success).results
        assertEquals(1, results.size)
        assertEquals(SearchSource.GEO_LINK, results[0].source)
        assertEquals(0, server.requestCount)
        assertEquals(1, recall.recorded.size)
    }

    @Test
    fun rapidSearchesAreSpacedNotDropped() = runBlocking {
        // Hammer protection must not eat keystrokes: two queries inside one
        // interval both reach the network, spaced minIntervalMs apart.
        server.enqueue(MockResponse().setResponseCode(200).setBody(readFixture("photon_hcm.json")))
        server.enqueue(MockResponse().setResponseCode(200).setBody(readFixture("photon_hcm.json")))
        val clock = longArrayOf(10_000L)
        val c = coordinator(clock = clock)
        val first = c.search("Nguyễn Thị Nghĩa")
        assertTrue(first is SearchOutcome.Success)
        val second = c.search("Nguyễn Thị Nghĩa, Quận 1")
        assertTrue(second is SearchOutcome.Success)
        assertEquals(2, server.requestCount)
        assertTrue(clock[0] >= 10_000L + SearchCoordinator.DEFAULT_MIN_INTERVAL_MS)
    }

    @Test
    fun latestQueryIsEventuallySearchedAfterEditing() = runBlocking {
        // The reported scenario: first query runs, the user edits inside the
        // interval, then stops typing. The FINAL query must still be searched
        // (trailing edge), not rejected with COOLDOWN_ACTIVE and forgotten.
        server.enqueue(MockResponse().setResponseCode(200).setBody(readFixture("photon_hcm.json")))
        server.enqueue(MockResponse().setResponseCode(200).setBody(readFixture("photon_ambiguous.json")))
        val clock = longArrayOf(50_000L)
        val c = coordinator(clock = clock)
        assertTrue(c.search("Tran Hung Dao") is SearchOutcome.Success)
        clock[0] += 100L // user edits 100 ms later, inside the 1 s interval
        val latest = c.search("Trần Hưng Đạo")
        assertTrue(latest is SearchOutcome.Success)
        assertTrue((latest as SearchOutcome.Success).ambiguity != null)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun localRecallAnswersEvenInsideTheNetworkInterval() = runBlocking {
        // Local favorites merge into the outcome while the network interval
        // elapses — recall is never gated behind the throttle.
        val saved = SearchResult(
            id = "fav-1",
            name = "Chợ Bến Thành",
            detail = "Quận 1",
            point = GeoPoint(10.7725, 106.6980),
            source = SearchSource.FAVORITE,
            verification = VerificationLevel.UNVERIFIED,
        )
        val clock = longArrayOf(90_000L)
        val c = coordinator(FakeRecall(listOf(saved)), clock = clock)
        server.enqueue(MockResponse().setResponseCode(200).setBody(emptyFeatures()))
        assertTrue(c.search("cho ben thanh") is SearchOutcome.Success)
        clock[0] += 100L
        server.enqueue(MockResponse().setResponseCode(200).setBody(readFixture("photon_hcm.json")))
        val outcome = c.search("cho ben thanh quan 1")
        assertTrue(outcome is SearchOutcome.Success)
        assertTrue((outcome as SearchOutcome.Success).results.any { it.id == "fav-1" })
        assertEquals(2, server.requestCount)
    }

    @Test
    fun missingStreetWithExistingCityFallsBackHonestly() = runBlocking {
        // The reported case: "Hẻm 999 Đường Không Có, Sài Gòn" — the street
        // does not exist but the city does. Alias retries must keep the
        // street terms (no bare "TP. Hồ Chí Minh" variant that would hit the
        // city as a successful address), so the street-level fallback runs and
        // every result is STREET_ONLY with the honest notice — never a
        // VERIFIED city pin presented as the address.
        // Variants for this query: original + normalized-sai-gon rewrite
        // (2 requests, both empty), then the street-level fallback retry.
        server.enqueue(MockResponse().setResponseCode(200).setBody(emptyFeatures()))
        server.enqueue(MockResponse().setResponseCode(200).setBody(emptyFeatures()))
        server.enqueue(MockResponse().setResponseCode(200).setBody(readFixture("photon_hcm.json")))

        val outcome = coordinator(minIntervalMs = 0).search("Hẻm 999 Đường Không Có, Sài Gòn")

        assertTrue(outcome is SearchOutcome.Success)
        val success = outcome as SearchOutcome.Success
        assertEquals(3, server.requestCount)
        assertEquals(STREET_ONLY_MESSAGE, success.fallbackNotice)
        assertTrue(success.results.isNotEmpty())
        assertTrue(success.results.all { it.verification == VerificationLevel.STREET_ONLY })
        assertTrue(success.results.none { it.name == "TP. Hồ Chí Minh" })
    }

    @Test
    fun hemQueryFallsBackToStreetLevelWithHonestNotice() = runBlocking {
        // Precise hem query finds nothing; street retry finds the road.
        server.enqueue(MockResponse().setResponseCode(200).setBody(emptyFeatures()))
        server.enqueue(MockResponse().setResponseCode(200).setBody(readFixture("photon_hcm.json")))

        val outcome = coordinator(minIntervalMs = 0).search("Hẻm 51 Nguyễn Thị Nghĩa, Quận 1")

        assertTrue(outcome is SearchOutcome.Success)
        val success = outcome as SearchOutcome.Success
        assertEquals(2, server.requestCount)
        assertEquals(STREET_ONLY_MESSAGE, success.fallbackNotice)
        assertTrue(success.results.isNotEmpty())
        assertTrue(success.results.all { it.verification == VerificationLevel.STREET_ONLY })
    }

    @Test
    fun hemQueryWithNoStreetEvidenceReturnsHonestNoResults() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(emptyFeatures()))
        server.enqueue(MockResponse().setResponseCode(200).setBody(emptyFeatures()))

        val outcome = coordinator(minIntervalMs = 0).search("Hẻm 999 Đường Không Có, Quận 1")

        assertTrue(outcome is SearchOutcome.Failure)
        val failure = outcome as SearchOutcome.Failure
        assertEquals(SearchError.NO_RESULTS, failure.error)
        assertTrue(failure.message.contains("chưa xác minh"))
    }

    @Test
    fun multiCityCandidatesProduceAmbiguityGroups() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(readFixture("photon_ambiguous.json")))

        val outcome = coordinator(minIntervalMs = 0).search("Trần Hưng Đạo")

        assertTrue(outcome is SearchOutcome.Success)
        val success = outcome as SearchOutcome.Success
        assertNotNull(success.ambiguity)
        val ambiguity = success.ambiguity!!
        assertEquals(2, ambiguity.groups.size)
        // Group headers must name the locality (city), never the country Photon
        // appends last: both groups ending in "Việt Nam" would otherwise read
        // "Khu vực: Việt Nam" and the picker would be useless.
        val names = ambiguity.groups.map { it.displayName }
        assertEquals(listOf("Thành phố Hồ Chí Minh", "Hà Nội"), names)
        assertTrue(names.none { it == "Việt Nam" })
    }

    @Test
    fun singleCityCandidatesHaveNoAmbiguity() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(readFixture("photon_hcm.json")))

        val outcome = coordinator(minIntervalMs = 0).search("Nguyễn Thị Nghĩa")

        assertTrue(outcome is SearchOutcome.Success)
        assertNull((outcome as SearchOutcome.Success).ambiguity)
    }

    @Test
    fun exactButUnrelatedPoiIsNotBadgedVerified() = runBlocking {
        // Live evidence 2026-10-17: exact housenumber/POI results (a church in
        // the same city, an academy in Nha Trang) came back for this HCM query.
        // Structural precision must not masquerade as the verified requested
        // address: results stay visible, the badge drops to UNVERIFIED.
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(readFixture("photon_unrelated_poi.json")),
        )

        val outcome = coordinator(minIntervalMs = 0).search("Chợ Bến Thành, Quận 1")

        assertTrue(outcome is SearchOutcome.Success)
        val results = (outcome as SearchOutcome.Success).results
        assertEquals(2, results.size)
        assertTrue(results.all { it.verification == VerificationLevel.UNVERIFIED })
    }

    @Test
    fun resultMatchingTheQueryKeepsItsVerifiedBadge() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(readFixture("photon_hcm.json")))

        val outcome = coordinator(minIntervalMs = 0).search("Chợ Bến Thành, Quận 1")

        assertTrue(outcome is SearchOutcome.Success)
        val results = (outcome as SearchOutcome.Success).results
        assertEquals(3, results.size)
        // The market this query names keeps VERIFIED...
        val market = results.first { it.name.contains("Bến Thành") }
        assertEquals(VerificationLevel.VERIFIED, market.verification)
        // ...while the exact house number on an unrelated street in the same
        // city loses it (shown, never hidden), and street-level stays as is.
        val house = results.first { it.verification == VerificationLevel.UNVERIFIED }
        assertTrue(house.name.contains("Nguyễn Thị Nghĩa"))
        assertEquals(
            VerificationLevel.STREET_ONLY,
            results.first { it.name == "Nguyễn Thị Nghĩa" }.verification,
        )
    }

    @Test
    fun offlineRecallStillServesSavedPlacesOnServerError() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("Boom"))
        val saved = SearchResult(
            id = "fav-1",
            name = "Chợ Bến Thành",
            detail = "Quận 1",
            point = GeoPoint(10.7725, 106.6980),
            source = SearchSource.FAVORITE,
            verification = VerificationLevel.UNVERIFIED,
        )
        val outcome = coordinator(FakeRecall(listOf(saved)), minIntervalMs = 0).search("cho ben thanh")

        assertTrue(outcome is SearchOutcome.Success)
        val results = (outcome as SearchOutcome.Success).results
        assertTrue(results.any { it.id == "fav-1" })
    }

    @Test
    fun unresolvableShortLinkFailsHonestlyWithoutNetwork() = runBlocking {
        val outcome = coordinator().search("https://maps.app.goo.gl/abcXYZ123")
        assertTrue(outcome is SearchOutcome.Failure)
        assertEquals(SearchError.UNRESOLVABLE_LINK, (outcome as SearchOutcome.Failure).error)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun mapsViewportLinkFailsHonestlyWithoutNetwork() = runBlocking {
        // Camera-only Maps link: there is no destination, so no pin is made
        // and no network search runs.
        val outcome = coordinator().search("https://www.google.com/maps/@10.7725,106.6980,15z")
        assertTrue(outcome is SearchOutcome.Failure)
        val failure = outcome as SearchOutcome.Failure
        assertEquals(SearchError.UNRESOLVABLE_LINK, failure.error)
        assertTrue(failure.message.contains("khung nhìn"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun mapsPlaceDataLinkUsesDestinationNotCameraWithoutNetwork() = runBlocking {
        // Shared place link: the data parameter (!3d!4d) is the destination;
        // "@" is only the camera and must never be the routed point.
        val outcome = coordinator().search(
            "https://www.google.com/maps/place/Cho+Ben+Thanh/@10.7,106.6,17z/" +
                "data=!3m1!4b1!4m6!3m5!1s0x3100!8m2!3d10.7725!4d106.6980",
        )
        assertTrue(outcome is SearchOutcome.Success)
        val results = (outcome as SearchOutcome.Success).results
        assertEquals(1, results.size)
        assertEquals(10.7725, results[0].point.latitude, 1e-6)
        assertEquals(106.6980, results[0].point.longitude, 1e-6)
        assertEquals(SearchSource.GEO_LINK, results[0].source)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun oversizedMapsUrlIsParsedIntactNeverTruncatedAtTheTextCap() = runBlocking {
        // Native review finding: with the text cap applied before parsing,
        // this URL was cut inside !4d106.6980 and the shortened longitude
        // became a VERIFIED pin kilometers away. Location parsing must see
        // intact input: the full destination (never a shortened pin).
        val url = SearchTestUrls.longMapsUrl()
        assertTrue(url.length > QuerySanitizer.MAX_QUERY_LENGTH)
        val cut = QuerySanitizer.forPhoton(url)!!
        assertEquals(QuerySanitizer.MAX_QUERY_LENGTH, cut.length)
        assertTrue("cap lands inside the longitude field", cut.endsWith("106.69"))

        val outcome = coordinator(minIntervalMs = 0).search(url)

        assertTrue("outcome was: $outcome", outcome is SearchOutcome.Success)
        val results = (outcome as SearchOutcome.Success).results
        assertEquals(1, results.size)
        assertEquals(10.7725, results[0].point.latitude, 0.0)
        assertEquals(106.6980, results[0].point.longitude, 0.0)
        assertEquals(0, server.requestCount)
    }
}
