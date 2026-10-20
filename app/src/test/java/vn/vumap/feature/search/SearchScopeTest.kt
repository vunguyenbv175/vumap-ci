package vn.vumap.feature.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Country/scope handling for V1 search (live evidence 2026-10-21).
 *
 * Photon is worldwide; V1 supports Vietnam. Country tags must be read without
 * guessing: missing tags stay UNKNOWN, never silently treated as Vietnamese
 * or foreign, and a named city is not a precise pin.
 */
class SearchScopeTest {

    private fun readFixture(name: String): String {
        val stream = javaClass.classLoader?.getResourceAsStream("fixtures/$name")
            ?: throw IllegalStateException("Fixture fixtures/$name not found on classpath")
        return stream.bufferedReader().use { it.readText() }
    }

    @Test
    fun classifiesVietnamByCodeOrNameAndForeignByTags() {
        assertEquals(ResultScope.VIETNAM, SearchScope.classify("VN", null))
        assertEquals(ResultScope.VIETNAM, SearchScope.classify("vn", "Việt Nam"))
        assertEquals(ResultScope.VIETNAM, SearchScope.classify(null, "Viet Nam"))
        assertEquals(ResultScope.VIETNAM, SearchScope.classify(null, "Vietnam"))
        assertEquals(ResultScope.FOREIGN, SearchScope.classify("GB", "United Kingdom"))
        assertEquals(ResultScope.FOREIGN, SearchScope.classify("US", null))
        assertEquals(ResultScope.FOREIGN, SearchScope.classify(null, "United States"))
        // Missing tags are unknown, not a guess.
        assertEquals(ResultScope.UNKNOWN, SearchScope.classify(null, null))
        assertEquals(ResultScope.UNKNOWN, SearchScope.classify("", "   "))
        assertEquals(ResultScope.UNKNOWN, SearchScope.classify("VNM", null))
    }

    @Test
    fun parserCarriesCountryTagsAndNeverGuessesMissingOnes() {
        val results = PhotonResponseParser.parse(readFixture("photon_country_missing.json"))
        assertEquals(4, results.size)

        val market = results.first { it.name == "Chợ Bến Thành" }
        assertNull("no countrycode tag", market.countryCode)
        assertNull("no country tag", market.countryName)
        assertEquals(ResultScope.UNKNOWN, market.scope)
        // Unknown is not foreign: it stays in the normal result list.
        assertEquals(VerificationLevel.VERIFIED, market.verification)

        val usOnlyCode = results.first { it.name == "81 Landmark Drive" }
        assertEquals("us", usOnlyCode.countryCode)
        assertNull(usOnlyCode.countryName)
        assertEquals(ResultScope.FOREIGN, usOnlyCode.scope)

        val vnOnlyName = results.first { it.name == "Chợ Hôm" }
        assertNull(vnOnlyName.countryCode)
        assertEquals("Việt Nam", vnOnlyName.countryName)
        assertEquals(ResultScope.VIETNAM, vnOnlyName.scope)

        val noTags = results.first { it.name.contains("Đường Không Rõ") }
        assertEquals(ResultScope.UNKNOWN, noTags.scope)
        assertNull(noTags.localityKey)
    }

    @Test
    fun namedCoarsePlaceIsNotAPrecisePin() {
        val results = PhotonResponseParser.parse(readFixture("photon_coarse_place.json"))
        val city = results.first { it.name == "Berlin" }
        assertEquals(ResultScope.FOREIGN, city.scope)
        assertEquals(
            "a city centroid must not read like an exact address",
            VerificationLevel.STREET_ONLY,
            city.verification,
        )
        val house = results.first { it.name.contains("10") }
        assertEquals(VerificationLevel.VERIFIED, house.verification)
        assertEquals(ResultScope.VIETNAM, house.scope)
    }

    @Test
    fun landmarkFixtureKeepsForeignAndVietnameseTags() {
        val results = PhotonResponseParser.parse(readFixture("photon_landmark81.json"))
        assertEquals(5, results.size)
        assertEquals(2, results.count { it.scope == ResultScope.VIETNAM })
        assertEquals(3, results.count { it.scope == ResultScope.FOREIGN })
        assertTrue(results.none { it.scope == ResultScope.UNKNOWN })
        assertTrue(results.filter { it.scope == ResultScope.VIETNAM }.all { it.countryCode == "vn" })
        assertFalse(results.filter { it.scope == ResultScope.FOREIGN }.any { it.countryCode == "vn" })
    }
}
