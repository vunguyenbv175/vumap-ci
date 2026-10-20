package vn.vumap.feature.search

import com.google.openlocationcode.OpenLocationCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.vumap.core.model.GeoPoint

/**
 * Shared-location parser tests: `geo:`, lat/lon, Google Maps links,
 * Plus Codes via the explicit `openlocationcode` library.
 */
class GeoLinkParserTest {

    @Test
    fun parsesGeoIntent() {
        val parsed = GeoLinkParser.parse("geo:10.7725,106.6980?q=Chợ Bến Thành")
        // The q value is a place name, so this is a text search for the place —
        // never a pin at the (identical, in this case) base coordinates.
        assertTrue(parsed is GeoLinkParser.ParsedLocation.PlaceQuery)
        assertEquals("Chợ Bến Thành", (parsed as GeoLinkParser.ParsedLocation.PlaceQuery).query)
        assertNull(GeoLinkParser.toResult(parsed))
    }

    @Test
    fun geoMatchingBaseAndMissingQueryUseBaseCoordinates() {
        // No/out-of-range q: base pair still works when it is a real position.
        val parsed = GeoLinkParser.parse("geo:10.7725,106.6980")
        assertTrue(parsed is GeoLinkParser.ParsedLocation.Coordinates)
        assertEquals(10.7725, (parsed as GeoLinkParser.ParsedLocation.Coordinates).point.latitude, 1e-6)
    }

    @Test
    fun geoPlaceholderBaseUsesQueryCoordinatesAsDestination() {
        // Standard shared-location form: base is a 0,0 placeholder, q carries
        // the real destination. Must NOT return a VERIFIED pin at 0,0.
        val parsed = GeoLinkParser.parse("geo:0,0?q=10.7725,106.6980(Ben%20Thanh)")
        assertTrue(parsed is GeoLinkParser.ParsedLocation.Coordinates)
        val coords = parsed as GeoLinkParser.ParsedLocation.Coordinates
        assertEquals(10.7725, coords.point.latitude, 1e-6)
        assertEquals(106.6980, coords.point.longitude, 1e-6)
        assertEquals("Ben Thanh", coords.label)
        val result = GeoLinkParser.toResult(coords)
        assertNotNull(result)
        assertEquals("Ben Thanh", result!!.name)
    }

    @Test
    fun geoQueryPlaceNameBecomesTextSearchNeverPlaceholderPin() {
        // q without coordinates is a place search: no pin at the base pair.
        val parsed = GeoLinkParser.parse("geo:0,0?q=Ben%20Thanh")
        assertTrue(parsed is GeoLinkParser.ParsedLocation.PlaceQuery)
        assertEquals("Ben Thanh", (parsed as GeoLinkParser.ParsedLocation.PlaceQuery).query)
        assertNull(GeoLinkParser.toResult(parsed))
    }

    @Test
    fun parsesPlainCoordinatePair() {
        val parsed = GeoLinkParser.parse("10.7725, 106.6980")
        assertTrue(parsed is GeoLinkParser.ParsedLocation.Coordinates)
    }

    @Test
    fun rejectsOutOfRangeCoordinates() {
        assertNull(GeoLinkParser.parse("200.0, 106.6980"))
        assertNull(GeoLinkParser.parse("Chợ Bến Thành"))
        assertNull(GeoLinkParser.parse(""))
    }

    @Test
    fun mapsCameraCoordsAreNotADestination() {
        // "@lat,lng,zoom" is the map camera / viewport, not a shared
        // destination: it must never become a routable pin.
        val parsed = GeoLinkParser.parse("https://www.google.com/maps/@10.7725,106.6980,15z")
        assertTrue(parsed is GeoLinkParser.ParsedLocation.ViewportOnly)
        val viewport = parsed as GeoLinkParser.ParsedLocation.ViewportOnly
        assertNull(GeoLinkParser.toResult(viewport))
        assertTrue(viewport.message.contains("khung nhìn"))
    }

    @Test
    fun mapsPlaceDataCoordsWinOverCameraCoords() {
        // A shared place link carries the destination in the data parameter
        // (!3dLAT!4dLON) while "@" is only the camera. The place point must
        // win: the camera pair is a decoy, not the target.
        val parsed = GeoLinkParser.parse(
            "https://www.google.com/maps/place/Cho+Ben+Thanh/@10.7,106.6,17z/" +
                "data=!3m1!4b1!4m6!3m5!1s0x3100!8m2!3d10.7725!4d106.6980",
        )
        assertTrue(parsed is GeoLinkParser.ParsedLocation.Coordinates)
        val coords = parsed as GeoLinkParser.ParsedLocation.Coordinates
        assertEquals(10.7725, coords.point.latitude, 1e-6)
        assertEquals(106.6980, coords.point.longitude, 1e-6)
        assertEquals("Cho Ben Thanh", coords.label)
        assertNotEquals(10.7, coords.point.latitude, 1e-6)
    }

    @Test
    fun dataParamRequiresACompleteDecimalCoordinate() {
        // Defense behind parse-intact input: a numeric field without decimals
        // (e.g. a truncated "!4d106") is not a complete coordinate and must
        // never become a pin.
        val cut = "https://www.google.com/maps/place/Xyz/@10.7,106.6,17z/data=!3d10.7725!4d106"
        val parsed = GeoLinkParser.parse(cut)
        assertFalse("truncated data param must not pin", parsed is GeoLinkParser.ParsedLocation.Coordinates)
        assertTrue(parsed is GeoLinkParser.ParsedLocation.PlaceQuery)
    }

    @Test
    fun parsesGoogleMapsQueryCoords() {
        val parsed = GeoLinkParser.parse("https://maps.google.com/maps?q=10.7725,106.6980")
        assertTrue(parsed is GeoLinkParser.ParsedLocation.Coordinates)
    }

    @Test
    fun placeUrlBecomesTextQueryNeverGuessedPin() {
        val parsed = GeoLinkParser.parse("https://www.google.com/maps/place/Cho+Ben+Thanh")
        assertTrue(parsed is GeoLinkParser.ParsedLocation.PlaceQuery)
        assertEquals("Cho Ben Thanh", (parsed as GeoLinkParser.ParsedLocation.PlaceQuery).query)
        assertNull(GeoLinkParser.toResult(parsed))
    }

    @Test
    fun shortLinkIsHonestlyUnresolvable() {
        val parsed = GeoLinkParser.parse("https://maps.app.goo.gl/abcXYZ123")
        assertTrue(parsed is GeoLinkParser.ParsedLocation.UnresolvableShortLink)
        val link = parsed as GeoLinkParser.ParsedLocation.UnresolvableShortLink
        assertTrue(link.message.contains("rút gọn"))
    }

    @Test
    fun fullPlusCodeDecodesViaExplicitLibrary() {
        val code = OpenLocationCode(10.7725, 106.6980).code
        assertTrue(OpenLocationCode.isFullCode(code))
        val parsed = GeoLinkParser.parse("Mã plus $code ở đây")
        assertTrue(parsed is GeoLinkParser.ParsedLocation.PlusCode)
        val plus = parsed as GeoLinkParser.ParsedLocation.PlusCode
        assertEquals(10.7725, plus.point.latitude, 0.01)
        assertEquals(106.6980, plus.point.longitude, 0.01)
        // Exact user-supplied pin, marked as Plus-Code sourced.
        val result = GeoLinkParser.toResult(plus)
        assertNotNull(result)
        assertEquals(SearchSource.PLUS_CODE, result!!.source)
        assertEquals(VerificationLevel.VERIFIED, result.verification)
    }

    @Test
    fun shortPlusCodeNeedsReferenceOtherwiseTextSearch() {
        val center = GeoPoint(10.7725, 106.6980)
        val full = OpenLocationCode(center.latitude, center.longitude)
        // Realistic sharing: receiver decodes with their own nearby fix (~2 km away).
        val nearRef = GeoPoint(10.7900, 106.7100)
        val short = full.shorten(nearRef.latitude, nearRef.longitude).code
        assertTrue(OpenLocationCode.isShortCode(short))

        val withRef = GeoLinkParser.parse(short, nearRef)
        assertTrue(withRef is GeoLinkParser.ParsedLocation.PlusCode)
        val plus = withRef as GeoLinkParser.ParsedLocation.PlusCode
        assertEquals(center.latitude, plus.point.latitude, 0.01)
        assertEquals(center.longitude, plus.point.longitude, 0.01)

        // Without a GPS reference there is no honest decode: plain text query.
        assertNull(GeoLinkParser.parse(short, null))
    }
}
