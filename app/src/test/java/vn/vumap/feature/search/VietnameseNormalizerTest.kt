package vn.vumap.feature.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Diacritic normalization tests (project plan section 5: co dau/khong dau).
 */
class VietnameseNormalizerTest {

    @Test
    fun stripsTonesButKeepsMeaning() {
        assertEquals("hem 51 nguyen thi nghia", VietnameseNormalizer.normalized("Hẻm 51 Nguyễn Thị Nghĩa"))
        assertEquals("ngo 15 trung kinh", VietnameseNormalizer.normalized("Ngõ 15 Trung Kính"))
        assertEquals("cho ben thanh", VietnameseNormalizer.normalized("Chợ Bến Thành"))
    }

    @Test
    fun strokeDMapsToPlainD() {
        assertEquals("di cho", VietnameseNormalizer.normalized("Đi chợ"))
        assertEquals("di cho", VietnameseNormalizer.normalized("Di cho"))
    }

    @Test
    fun matchesIgnoresDiacriticsAndCase() {
        assertTrue(VietnameseNormalizer.matches("hem 51", "Hẻm 51 Nguyễn Thị Nghĩa"))
        assertTrue(VietnameseNormalizer.matches("NGO 15", "Ngõ 15 Trung Kính"))
        assertTrue(VietnameseNormalizer.matches("ben thanh", "Chợ Bến Thành, Quận 1"))
        assertFalse(VietnameseNormalizer.matches("cho lon", "Chợ Bến Thành"))
        assertFalse(VietnameseNormalizer.matches("", "Chợ Bến Thành"))
    }

    @Test
    fun matchesAllTokensAllowsReordering() {
        assertTrue(VietnameseNormalizer.matchesAllTokens("quan 1 nguyen thi nghia", "Hẻm 51 Nguyễn Thị Nghĩa, Quận 1"))
        assertFalse(VietnameseNormalizer.matchesAllTokens("quan 2 nguyen thi nghia", "Hẻm 51 Nguyễn Thị Nghĩa, Quận 1"))
    }
}
