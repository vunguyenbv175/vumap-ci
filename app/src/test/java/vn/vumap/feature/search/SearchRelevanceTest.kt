package vn.vumap.feature.search

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import vn.vumap.core.model.GeoPoint

/**
 * Focused regressions for the real-device "Landmark 81" report (2026-10-21):
 * worldwide Photon hits rendered beside the HCMC tower with no distance,
 * misleading verified badges on foreign hits, duplicate "United States" group
 * headers, and an unhelpful search scope.
 *
 * Everything runs offline against captured/synthetic fixtures; no live geocoder
 * queries are made.
 */
class SearchRelevanceTest {

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
        private val store = SearchStore().apply { items.forEach { addFavorite(it) } }
        override suspend fun findMatches(query: String, limit: Int): List<SearchResult> =
            store.findMatches(query, limit)
        override suspend fun recordHistory(result: SearchResult) = Unit
    }

    private fun coordinator(
        recall: LocalRecall? = null,
        minIntervalMs: Long = 0,
    ): SearchCoordinator = SearchCoordinator(
        provider = PhotonSearchProvider(baseUrl = server.url("/").toString()),
        favorites = recall,
        minIntervalMs = minIntervalMs,
        timeProvider = { 0L },
        delay = { },
    )

    @Test
    fun landmark81RanksVietnamFirstAndForeignResultsAreLabelledNotVerified() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(readFixture("photon_landmark81.json")),
        )

        val outcome = coordinator().search("Landmark 81")

        assertTrue(outcome is SearchOutcome.Success)
        val results = (outcome as SearchOutcome.Success).results
        assertEquals(5, results.size)

        // The two HCMC results (exact phrase "Landmark 81") rank before every
        // foreign hit, even though the provider listed the UK pub and the US
        // driveways first.
        assertEquals(
            listOf(ResultScope.VIETNAM, ResultScope.VIETNAM),
            results.take(2).map { it.scope },
        )
        assertTrue(results[0].name.contains("Landmark 81"))
        assertTrue(results[1].name.contains("Landmark 81"))
        assertTrue(results.drop(2).all { it.scope == ResultScope.FOREIGN })

        // V1 does not verify foreign pins: they stay visible but never carry
        // the precise-V1-pin badge.
        val foreign = results.filter { it.scope == ResultScope.FOREIGN }
        assertEquals(3, foreign.size)
        assertTrue(foreign.all { it.verification == VerificationLevel.UNVERIFIED })
        assertTrue(foreign.none { it.name == "Vinhomes Central Park - Tòa nhà Landmark 81" })

        // The local tower remains street-level (its OSM feature has no
        // house-number/precise-POI evidence) and is not promoted just because
        // the user typed its name.
        val tower = results.first { it.name.contains("Vinhomes") }
        assertEquals(VerificationLevel.STREET_ONLY, tower.verification)
        val shop = results.first { it.name.contains("Innsfree") }
        assertEquals(VerificationLevel.VERIFIED, shop.verification)

        // One clearly labelled foreign section, not two "United States" groups.
        val sections = SearchGrouping.build(results)
        assertTrue(sections.localityGroups.isEmpty())
        assertEquals(2, sections.otherResults.size)
        assertEquals(3, sections.foreignResults.size)
        // All three countries share one section key; no per-country header can
        // repeat "United States" twice.
        assertEquals(
            "header:foreign",
            SearchGrouping.FOREIGN_HEADER_KEY,
        )
        assertTrue(sections.foreignResults.distinctBy { it.id }.size == 3)
        assertFalse(sections.localityGroups.any { it.displayName.contains("United States") })
        assertTrue(SearchGrouping.FOREIGN_SECTION_TITLE.contains("Ngoài Việt Nam"))

        // Request scope: V1 asks Photon for Vietnam only.
        val request = server.takeRequest()
        assertEquals("VN", request.requestUrl?.queryParameter("countrycode"))
    }

    @Test
    fun substringOnlyMatchDoesNotKeepThePrecisePinBadge() = runBlocking {
        // Review regression: a named POI "Cafeteria" must not retain VERIFIED
        // for the query "cafe" just because the substring appears in its name.
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {"features":[{
                  "geometry":{"coordinates":[106.6992,10.7731],"type":"Point"},
                  "type":"Feature",
                  "properties":{
                    "osm_id":8501,"osm_type":"N","osm_key":"amenity",
                    "osm_value":"cafe","name":"Cafeteria",
                    "street":"Lê Lợi","district":"Quận 1",
                    "city":"Thành phố Hồ Chí Minh",
                    "country":"Việt Nam","countrycode":"VN"
                  }
                }],"type":"FeatureCollection"}
                """.trimIndent(),
            ),
        )

        val outcome = coordinator().search("cafe")
        assertTrue(outcome is SearchOutcome.Success)
        val result = (outcome as SearchOutcome.Success).results.single()
        assertEquals(VerificationLevel.UNVERIFIED, result.verification)
        assertEquals(0, MatchEvidence.matchScore("cafe", result))
    }

    @Test
    fun ambiguousVietnameseCitiesKeepStableUniqueGroupKeysAndCityLabels() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(readFixture("photon_ambiguous.json")),
        )

        val outcome = coordinator().search("Trần Hưng Đạo")
        assertTrue(outcome is SearchOutcome.Success)
        val results = (outcome as SearchOutcome.Success).results

        val sections = SearchGrouping.build(results)
        assertEquals(2, sections.localityGroups.size)
        assertEquals(
            listOf("Thành phố Hồ Chí Minh", "Hà Nội"),
            sections.localityGroups.map { it.displayName },
        )
        val keys = sections.localityGroups.map { SearchGrouping.localityHeaderKey(it.localityKey) }
        assertEquals("header keys must be unique", keys.size, keys.toSet().size)
        assertEquals(keys, SearchGrouping.build(results).localityGroups.map {
            SearchGrouping.localityHeaderKey(it.localityKey)
        })
        assertTrue(keys.all { it.startsWith("header:vn:") })
        assertTrue(keys.none { it.contains("viet nam") })
        assertEquals(SearchGrouping.FOREIGN_HEADER_KEY, "header:foreign")
    }

    @Test
    fun streetFallbackRetryAlsoCarriesTheVietnamScope() = runBlocking {
        // Precise hem query finds nothing; the street-level retry must keep the
        // same V1 country scope (never query the worldwide index un-scoped).
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"features":[]}"""))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(readFixture("photon_hcm.json")),
        )

        val outcome = coordinator().search("Hẻm 51 Nguyễn Thị Nghĩa, Quận 1")
        assertTrue(outcome is SearchOutcome.Success)
        assertEquals(2, server.requestCount)
        repeat(2) {
            val request = server.takeRequest()
            assertEquals("VN", request.requestUrl?.queryParameter("countrycode"))
        }
    }

    @Test
    fun missingCountryTagsStayVisibleAndAreNotReclassifiedAsForeign() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(readFixture("photon_country_missing.json")),
        )

        val outcome = coordinator().search("Chợ")
        assertTrue(outcome is SearchOutcome.Success)
        val results = (outcome as SearchOutcome.Success).results
        assertEquals(4, results.size)

        val sections = SearchGrouping.build(results)
        assertEquals(1, sections.foreignResults.size)
        assertEquals("81 Landmark Drive", sections.foreignResults.single().name)
        // The market with no country tags and the untagged street remain in the
        // normal results, not the foreign bucket.
        assertTrue(sections.otherResults.any { it.name == "Chợ Bến Thành" })
        assertTrue(sections.otherResults.any { it.name.contains("Đường Không Rõ") })
        assertTrue(sections.foreignResults.none { it.name == "Chợ Bến Thành" })
    }

    @Test
    fun labelsDescribePinPrecisionNotQueryCorrectness() {
        assertEquals("Pin chính xác", VerificationLabels.forLevel(VerificationLevel.VERIFIED))
        assertEquals("Chỉ cấp đường", VerificationLabels.forLevel(VerificationLevel.STREET_ONLY))
        assertEquals("Chưa xác minh", VerificationLabels.forLevel(VerificationLevel.UNVERIFIED))
        assertFalse(VerificationLabels.forLevel(VerificationLevel.VERIFIED).contains("Đã xác minh"))
        assertTrue(VerificationLabels.LEGEND.contains("không xác nhận"))
        assertTrue(VerificationLabels.SEARCH_HINT.contains("Việt Nam"))
    }

    @Test
    fun vietnamScopeOutranksEvenAStrongerForeignMatch() {
        val domestic = SearchResult(
            id = "vn-weak",
            name = "Chợ Bến Thành",
            detail = "Lê Lợi, Quận 1, Thành phố Hồ Chí Minh, Việt Nam",
            point = GeoPoint(10.7725, 106.6980),
            source = SearchSource.PHOTON,
            verification = VerificationLevel.VERIFIED,
            countryCode = "vn",
            countryName = "Việt Nam",
        )
        val foreignExact = SearchResult(
            id = "foreign-exact",
            name = "Tokyo",
            detail = "Tokyo, Japan",
            point = GeoPoint(35.6762, 139.6503),
            source = SearchSource.PHOTON,
            verification = VerificationLevel.UNVERIFIED,
            countryCode = "jp",
            countryName = "Japan",
        )

        // V1 is Vietnam-first even if a provider ignores the request filter and
        // returns a stronger foreign text match; the UI still labels it.
        assertEquals(
            listOf("vn-weak", "foreign-exact"),
            SearchResultRanker.rank(listOf(foreignExact, domestic), "Tokyo", null)
                .map { it.id },
        )
    }

    @Test
    fun distanceIsShownOnlyFromARealOriginAndBreaksTies() {
        val near = SearchResult(
            id = "vn-near",
            name = "Chợ Bến Thành",
            detail = "Lê Lợi, Quận 1, Thành phố Hồ Chí Minh, Việt Nam",
            point = GeoPoint(10.7725, 106.6980),
            source = SearchSource.PHOTON,
            verification = VerificationLevel.VERIFIED,
            countryCode = "vn",
            countryName = "Việt Nam",
        )
        val far = near.copy(id = "vn-far", point = GeoPoint(21.0333, 105.8500))

        val origin = GeoPoint(10.7730, 106.6990)
        assertEquals(listOf("vn-near", "vn-far"), SearchResultRanker.rank(
            listOf(far, near), "Chợ Bến Thành", origin,
        ).map { it.id })
        // No origin: no fabricated distance and the provider order is kept.
        assertEquals(listOf("vn-far", "vn-near"), SearchResultRanker.rank(
            listOf(far, near), "Chợ Bến Thành", null,
        ).map { it.id })

        assertEquals("cách bạn 0 m", SearchDistance.label(0.0))
        assertEquals("cách bạn 850 m", SearchDistance.label(850.0))
        assertEquals("cách bạn 1,2 km", SearchDistance.label(1_200.0))
        assertEquals("cách bạn 15 km", SearchDistance.label(15_000.0))
    }

    @Test
    fun coordinateShareLinkAndLocalFavoriteStillWorkOutsideTheCountryFilter() = runBlocking {
        // 1. Coordinate fast path: no network, exact pin, no country guess.
        val coordinate = coordinator().search("10.7725, 106.6980")
        assertTrue(coordinate is SearchOutcome.Success)
        val pin = (coordinate as SearchOutcome.Success).results.single()
        assertEquals(SearchSource.GEO_LINK, pin.source)
        assertEquals(VerificationLevel.VERIFIED, pin.verification)
        assertEquals(ResultScope.UNKNOWN, pin.scope)
        assertEquals(0, server.requestCount)

        // 2. Local favorite with no country tags still answers a text query
        // even when Photon has no result for it.
        val saved = SearchResult(
            id = "fav-1",
            name = "Chợ Bến Thành",
            detail = "Quận 1, TP. Hồ Chí Minh",
            point = GeoPoint(10.7725, 106.6980),
            source = SearchSource.FAVORITE,
            verification = VerificationLevel.UNVERIFIED,
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"features":[]}"""))
        val local = coordinator(FakeRecall(listOf(saved))).search("cho ben thanh")
        assertTrue(local is SearchOutcome.Success)
        val localResults = (local as SearchOutcome.Success).results
        assertTrue(localResults.any { it.id == "fav-1" })
        assertEquals(ResultScope.UNKNOWN, localResults.first { it.id == "fav-1" }.scope)
    }

    @Test
    fun noResultsMessageStatesTheVietnamOnlyScope() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"features":[]}"""))

        val outcome = coordinator().search("Tokyo")

        assertTrue(outcome is SearchOutcome.Failure)
        val failure = outcome as SearchOutcome.Failure
        assertEquals(SearchError.NO_RESULTS, failure.error)
        assertTrue(failure.message.contains("Việt Nam"))
        assertTrue(failure.message.contains("V1"))
        val request = server.takeRequest()
        assertEquals("VN", request.requestUrl?.queryParameter("countrycode"))
        assertNull(request.requestUrl?.queryParameter("lat"))
        assertNull("no fabricated origin", request.requestUrl?.queryParameter("lon"))
    }

    @Test
    fun oldSavedSnapshotWithoutCountryFieldsDecodesAsUnknownScope() {
        val oldJson = """[
            {"id":"fav-old","name":"Chợ Bến Thành","detail":"Quận 1, TP. Hồ Chí Minh",
             "lat":10.7725,"lon":106.6980,"source":"FAVORITE","verification":"UNVERIFIED"}
        ]"""
        val decoded = SearchStore.decode(oldJson).single()
        assertEquals("fav-old", decoded.id)
        assertEquals(GeoPoint(10.7725, 106.6980), decoded.point)
        assertNull(decoded.countryCode)
        assertNull(decoded.countryName)
        assertEquals(ResultScope.UNKNOWN, decoded.scope)

        // New snapshots round-trip the country tags too.
        val vn = decoded.copy(countryCode = "vn", countryName = "Việt Nam")
        val roundTripped = SearchStore.decode(SearchStore.encode(listOf(vn))).single()
        assertEquals("vn", roundTripped.countryCode)
        assertEquals("Việt Nam", roundTripped.countryName)
        assertEquals(ResultScope.VIETNAM, roundTripped.scope)
    }
}
