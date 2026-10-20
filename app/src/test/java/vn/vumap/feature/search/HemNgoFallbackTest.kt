package vn.vumap.feature.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.vumap.core.model.GeoPoint

/**
 * Hem/ngo/so-nha fallback tests (honest street-level pins only).
 */
class HemNgoFallbackTest {

    @Test
    fun detectsDetailedNumberQueries() {
        assertTrue(HemNgoFallback.hasDetailedNumber("Hẻm 51 Nguyễn Thị Nghĩa, Quận 1"))
        assertTrue(HemNgoFallback.hasDetailedNumber("hem 51 nguyen thi nghia"))
        assertTrue(HemNgoFallback.hasDetailedNumber("Ngõ 15 Trung Kính"))
        assertTrue(HemNgoFallback.hasDetailedNumber("123/45 Cách Mạng Tháng Tám"))
        assertTrue(HemNgoFallback.hasDetailedNumber("số nhà 8 Phạm Ngũ Lão"))
        assertTrue(HemNgoFallback.hasDetailedNumber("Đường số 5, Bình Tân"))
        assertTrue(HemNgoFallback.hasDetailedNumber("Phường 3, Gò Vấp"))
        assertFalse(HemNgoFallback.hasDetailedNumber("Chợ Bến Thành, Quận 1"))
        assertFalse(HemNgoFallback.hasDetailedNumber("Cầu Rồng, Đà Nẵng"))
    }

    @Test
    fun stripsLeadingAlleyAndSlashNumbers() {
        assertEquals(
            "Nguyễn Thị Nghĩa, Quận 1",
            HemNgoFallback.streetFallbackQuery("Hẻm 51 Nguyễn Thị Nghĩa, Quận 1"),
        )
        assertEquals(
            "Trung Kính, Cầu Giấy",
            HemNgoFallback.streetFallbackQuery("Ngõ 15 Trung Kính, Cầu Giấy"),
        )
        assertEquals(
            "Cách Mạng Tháng Tám, Quận 3",
            HemNgoFallback.streetFallbackQuery("123/45 Cách Mạng Tháng Tám, Quận 3"),
        )
    }

    @Test
    fun returnsNullWhenNothingRemains() {
        assertNull(HemNgoFallback.streetFallbackQuery("Hẻm 51"))
        assertNull(HemNgoFallback.streetFallbackQuery("12/3"))
    }

    @Test
    fun markStreetOnlyDowngradesEveryResult() {
        val verified = SearchResult(
            id = "x",
            name = "51/8 Nguyễn Thị Nghĩa",
            detail = "Quận 1",
            point = GeoPoint(10.77, 106.69),
            source = SearchSource.PHOTON,
            verification = VerificationLevel.VERIFIED,
        )
        val downgraded = HemNgoFallback.markStreetOnly(listOf(verified))
        assertEquals(VerificationLevel.STREET_ONLY, downgraded.single().verification)
        // The honest notice constant is non-empty and Vietnamese.
        assertTrue(STREET_ONLY_MESSAGE.contains("chưa xác minh"))
    }
}
