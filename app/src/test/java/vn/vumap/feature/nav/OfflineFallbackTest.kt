package vn.vumap.feature.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.vumap.core.model.GeoPoint

/**
 * Offline `geo:` fallback URI tests (project plan section 3: honest offline behavior —
 * hand the destination to an installed map app, never claim on-device routing).
 */
class OfflineFallbackTest {

    @Test
    fun `geo uri without label uses lat lon query`() {
        val uri = OfflineFallback.geoUri(GeoPoint(10.7768, 106.7032))
        assertEquals("geo:10.776800,106.703200?q=10.776800,106.703200", uri)
    }

    @Test
    fun `geo uri with label appends encoded label`() {
        val uri = OfflineFallback.geoUri(GeoPoint(21.0285, 105.8542), "Ho Guom")
        assertEquals("geo:21.028500,105.854200?q=21.028500,105.854200(Ho%20Guom)", uri)
    }

    @Test
    fun `blank label behaves like no label`() {
        val withBlank = OfflineFallback.geoUri(GeoPoint(16.061, 108.223), "   ")
        val without = OfflineFallback.geoUri(GeoPoint(16.061, 108.223))
        assertEquals(without, withBlank)
    }

    @Test
    fun `coordinates always use dot decimal separator with six decimals`() {
        val uri = OfflineFallback.geoUri(GeoPoint(10.5, 106.0))
        assertTrue(uri.startsWith("geo:10.500000,106.000000"))
    }
}
