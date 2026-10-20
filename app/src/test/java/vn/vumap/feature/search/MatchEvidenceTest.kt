package vn.vumap.feature.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.vumap.core.model.GeoPoint

/**
 * Match-evidence tests: an exact-but-unrelated pin must not qualify as the
 * verified requested address, while genuine matches and pure-locality queries
 * keep their badges (live evidence 2026-10-17).
 */
class MatchEvidenceTest {

    private fun result(name: String, detail: String) = SearchResult(
        id = "photon:test/${name.hashCode()}",
        name = name,
        detail = detail,
        point = GeoPoint(10.7725, 106.6980),
        source = SearchSource.PHOTON,
        verification = VerificationLevel.VERIFIED,
    )

    private val market = result("Chợ Bến Thành", "Lê Lợi, Quận 1, Thành phố Hồ Chí Minh, Việt Nam")
    private val church = result("Nhà thờ Huyện Sỹ", "Tôn Thất Tùng, Quận 1, Thành phố Hồ Chí Minh, Việt Nam")
    private val academy = result("1 Lê Thánh Tôn", "Lê Thánh Tôn, Nha Trang, Khánh Hòa, Việt Nam")

    @Test
    fun unrelatedHouseNumberPoiHasNoMatchEvidence() {
        // Live evidence: church/zoo/Nha Trang results came back VERIFIED for
        // unrelated HCM queries — structural precision, not an answer.
        assertFalse(MatchEvidence.matchesQuery("Chợ Bến Thành, Quận 1", church))
        assertFalse(MatchEvidence.matchesQuery("Chợ Bến Thành, Quận 1", academy))
        assertFalse(MatchEvidence.matchesQuery("hem 51 nguyen thi nghia quan 1", academy))
        assertFalse(
            MatchEvidence.matchesQuery(
                "Thủ Thiêm, Quận 2, Sài Gòn",
                result("Thảo Cầm Viên Sài Gòn", "2 Nguyễn Bỉnh Khiêm, Quận 1, Thành phố Hồ Chí Minh, Việt Nam"),
            ),
        )
    }

    @Test
    fun resultSharingContentKeepsMatchEvidence() {
        assertTrue(MatchEvidence.matchesQuery("Chợ Bến Thành, Quận 1", market))
        assertTrue(
            MatchEvidence.matchesQuery(
                "Sài Gòn Centre, Lê Lợi",
                result("Toà Nhà Saigon Centre", "65 Lê Lợi, Quận 1, Thành phố Hồ Chí Minh, Việt Nam"),
            ),
        )
        assertTrue(
            MatchEvidence.matchesQuery(
                "Hẻm 51 Nguyễn Thị Nghĩa, Quận 1",
                result("51/8 Nguyễn Thị Nghĩa", "Nguyễn Thị Nghĩa, Quận 1, Thành phố Hồ Chí Minh, Việt Nam"),
            ),
        )
    }

    @Test
    fun sharedLocalityWordsAloneDoNotCount() {
        // Same district/city as the query proves nothing about the pin: only
        // administrative words are shared with an unrelated result.
        val unrelatedInSameDistrict = result(
            "Khách sạn Grand",
            "8 Nguyễn Huệ, Quận 1, Thành phố Hồ Chí Minh, Việt Nam",
        )
        assertFalse(MatchEvidence.matchesQuery("Chợ Bến Thành, Quận 1", unrelatedInSameDistrict))
        assertFalse(MatchEvidence.matchesQuery("Chợ Bến Thành, Quận 1", church))
    }

    @Test
    fun substringInsideAWordIsNotAnExactPhraseMatch() {
        // Review finding: String.contains("cafe") matched "Cafeteria" and gave
        // it the exact-phrase score (and, via matchesQuery, kept VERIFIED).
        val cafeteria = result(
            "Cafeteria",
            "Lê Lợi, Quận 1, Thành phố Hồ Chí Minh, Việt Nam",
        )
        assertEquals(0, MatchEvidence.matchScore("cafe", cafeteria))
        assertFalse(MatchEvidence.matchesQuery("cafe", cafeteria))
        // The complete token still matches normally.
        assertTrue(MatchEvidence.matchesQuery("cafeteria", cafeteria))
        assertTrue(MatchEvidence.matchScore("cafeteria", cafeteria) > 0)
    }

    @Test
    fun contiguousWholeTokensStillEarnThePhraseScore() {
        val market = result("Chợ Bến Thành", "Lê Lợi, Quận 1, Thành phố Hồ Chí Minh, Việt Nam")
        assertTrue(MatchEvidence.matchesQuery("cho ben thanh", market))
        assertTrue(
            MatchEvidence.matchScore("cho ben thanh", market) >
                MatchEvidence.matchScore("ben thanh", market),
        )
    }

    @Test
    fun pureLocalityQueryIsNotDemandedContentEvidence() {
        // "Sài Gòn" / "TP. Hồ Chí Minh" carry no content tokens after the
        // locality vocabulary is stripped: a locality answer is the answer.
        assertTrue(MatchEvidence.matchesQuery("Sài Gòn", church))
        assertTrue(MatchEvidence.matchesQuery("TP. Hồ Chí Minh", church))
        assertTrue(MatchEvidence.matchesQuery("sai gon quan 1", church))
    }
}
