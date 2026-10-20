package vn.vumap.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class GeoPointTest {

    @Test
    fun `valid coordinates are kept as-is`() {
        // Ho Chi Minh City, District 1.
        val p = GeoPoint(10.7769, 106.7009)
        assertEquals(10.7769, p.latitude, 0.0)
        assertEquals(106.7009, p.longitude, 0.0)
    }

    @Test
    fun `boundary coordinates are accepted`() {
        GeoPoint(-90.0, -180.0)
        GeoPoint(90.0, 180.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `latitude above 90 is rejected`() {
        GeoPoint(90.0001, 106.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `latitude below -90 is rejected`() {
        GeoPoint(-90.0001, 106.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `longitude above 180 is rejected`() {
        GeoPoint(10.0, 180.0001)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `longitude below -180 is rejected`() {
        GeoPoint(10.0, -180.0001)
    }
}
