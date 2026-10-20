package vn.vumap.feature.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Admin alias tests (old/new names, nicknames; table [AdminAliases.TABLE_VERSION]).
 */
class AdminAliasesTest {

    @Test
    fun resolvesNicknamesDiacriticInsensitive() {
        assertEquals("TP. Hồ Chí Minh", AdminAliases.canonicalFor("Sài Gòn"))
        assertEquals("TP. Hồ Chí Minh", AdminAliases.canonicalFor("sai gon"))
        assertEquals("TP. Thủ Đức", AdminAliases.canonicalFor("Quận 2"))
        assertEquals("TP. Thủ Đức", AdminAliases.canonicalFor("quan 9"))
        assertEquals("Hà Nội", AdminAliases.canonicalFor("Hà Tây"))
        assertEquals("Đà Nẵng", AdminAliases.canonicalFor("Quảng Nam"))
        assertEquals("Lâm Đồng", AdminAliases.canonicalFor("Bình Thuận"))
        assertNull(AdminAliases.canonicalFor("Nơi Không Tồn Tại"))
    }

    @Test
    fun variantsKeepOriginalFirst() {
        val variants = AdminAliases.variants("Thủ Thiêm, Quận 2, Sài Gòn")
        assertEquals("Thủ Thiêm, Quận 2, Sài Gòn", variants.first())
        // Aliases expand in place, keeping the street/POI terms — no bare
        // canonical locality that could hit the city as an address.
        assertTrue(variants.any { it.contains("thu duc") })
        assertTrue(variants.any { it.contains("ho chi minh") })
        assertTrue(variants.none { it == "TP. Thủ Đức" || it == "TP. Hồ Chí Minh" })
    }

    @Test
    fun bareCanonicalOnlyWhenWholeQueryIsTheAlias() {
        // Whole-query alias still resolves to the city.
        assertEquals(
            listOf("Sài Gòn", "tp. ho chi minh", "TP. Hồ Chí Minh"),
            AdminAliases.variants("Sài Gòn"),
        )
        // Address queries keep their street/POI terms in every variant: no
        // bare "TP. Hồ Chí Minh" that could hit the city as an address.
        val variants = AdminAliases.variants("Hẻm 999 Đường Không Có, Sài Gòn")
        assertEquals("Hẻm 999 Đường Không Có, Sài Gòn", variants.first())
        assertTrue(variants.none { it == "TP. Hồ Chí Minh" })
        assertTrue(variants.all { it.contains("999") })
    }

    @Test
    fun unknownQueryHasSingleVariant() {
        assertEquals(listOf("Chợ Bến Thành"), AdminAliases.variants("Chợ Bến Thành"))
    }
}
