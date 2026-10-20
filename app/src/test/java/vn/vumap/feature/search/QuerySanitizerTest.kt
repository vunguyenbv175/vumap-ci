package vn.vumap.feature.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Query sanitizer tests.
 */
class QuerySanitizerTest {

    @Test
    fun trimsAndCollapsesWhitespace() {
        assertEquals("Hẻm 51 Nguyễn Thị Nghĩa", QuerySanitizer.sanitize("  Hẻm   51  Nguyễn\tThị Nghĩa  "))
    }

    @Test
    fun keepsAddressLinkAndCodeCharacters() {
        assertEquals("123/45 Cách Mạng Tháng Tám", QuerySanitizer.sanitize("123/45 Cách Mạng Tháng Tám"))
        assertEquals("10.7725, 106.6980", QuerySanitizer.sanitize("10.7725, 106.6980"))
        assertEquals("geo:10.7725,106.6980", QuerySanitizer.sanitize("geo:10.7725,106.6980"))
    }

    @Test
    fun keepsGoogleMapsPlaceLinkCharacters() {
        // The data parameter `!3d..!4d..` is where a shared Maps link carries
        // the destination; stripping "!" would drop the exact place point.
        val url = "https://www.google.com/maps/place/Cho+Ben+Thanh/@10.7,106.6,17z/" +
            "data=!3d10.7725!4d106.6980"
        assertEquals(url, QuerySanitizer.sanitize(url))
    }

    @Test
    fun blankAndSymbolOnlyInputIsEmpty() {
        assertNull(QuerySanitizer.sanitize(null))
        assertNull(QuerySanitizer.sanitize("   "))
        assertNull(QuerySanitizer.sanitize("<>{}***"))
    }

    @Test
    fun capsOversizedQueries() {
        val long = "a".repeat(500)
        val out = QuerySanitizer.sanitize(long)
        assertEquals(QuerySanitizer.MAX_QUERY_LENGTH, out!!.length)
    }

    @Test
    fun forPhotonTrimsTrailingSeparators() {
        assertEquals("Chợ Bến Thành", QuerySanitizer.forPhoton("Chợ Bến Thành,,,"))
        assertNull(QuerySanitizer.forPhoton("  "))
    }

    @Test
    fun locationParsingKeepsOversizedUrlsIntact() {
        // The text cap exists for outbound Photon queries only: parsing must
        // see the whole URL, otherwise a cut inside !4d106.6980 pins a wrong,
        // kilometers-away coordinate as VERIFIED (native review finding).
        val url = SearchTestUrls.longMapsUrl()
        assertTrue(url.length > QuerySanitizer.MAX_QUERY_LENGTH)

        val capped = QuerySanitizer.forPhoton(url)!!
        assertEquals(QuerySanitizer.MAX_QUERY_LENGTH, capped.length)
        assertTrue("text cap must land inside the longitude field", capped.endsWith("106.69"))

        val intact = QuerySanitizer.forLocationInput(url)
        assertEquals(url, intact)
        assertTrue(intact!!.contains("!4d106.6980"))
    }
}
