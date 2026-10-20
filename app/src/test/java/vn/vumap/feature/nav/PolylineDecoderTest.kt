package vn.vumap.feature.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.vumap.core.model.GeoPoint

class PolylineDecoderTest {

    @Test
    fun testDecodeEmpty() {
        val points = PolylineDecoder.decode("", PolylineDecoder.PRECISION_POLYLINE6)
        assertTrue(points.isEmpty())
    }

    @Test
    fun testEncodeDecodeRoundTripPolyline6() {
        val original = listOf(
            GeoPoint(10.772500, 106.698000),
            GeoPoint(10.773800, 106.700500),
            GeoPoint(10.776800, 106.703200),
        )

        val encoded = PolylineDecoder.encode(original, PolylineDecoder.PRECISION_POLYLINE6)
        val decoded = PolylineDecoder.decode(encoded, PolylineDecoder.PRECISION_POLYLINE6)

        assertEquals(original.size, decoded.size)
        for (i in original.indices) {
            assertEquals(original[i].latitude, decoded[i].latitude, 0.000002)
            assertEquals(original[i].longitude, decoded[i].longitude, 0.000002)
        }
    }

    @Test
    fun testEncodeDecodeRoundTripPolyline5() {
        val original = listOf(
            GeoPoint(21.02850, 105.85420),
            GeoPoint(21.03680, 105.83460),
        )

        val encoded = PolylineDecoder.encode(original, PolylineDecoder.PRECISION_POLYLINE5)
        val decoded = PolylineDecoder.decode(encoded, PolylineDecoder.PRECISION_POLYLINE5)

        assertEquals(original.size, decoded.size)
        for (i in original.indices) {
            assertEquals(original[i].latitude, decoded[i].latitude, 0.00002)
            assertEquals(original[i].longitude, decoded[i].longitude, 0.00002)
        }
    }

    @Test
    fun testPolyline6Precision() {
        // Known coordinate pair: (10.772500, 106.698000)
        val points = listOf(GeoPoint(10.772500, 106.698000))
        val encoded = PolylineDecoder.encode(points, PolylineDecoder.PRECISION_POLYLINE6)
        val decoded = PolylineDecoder.decode(encoded, PolylineDecoder.PRECISION_POLYLINE6)
        assertEquals(1, decoded.size)
        assertEquals(10.772500, decoded[0].latitude, 0.000001)
        assertEquals(106.698000, decoded[0].longitude, 0.000001)
    }
}
