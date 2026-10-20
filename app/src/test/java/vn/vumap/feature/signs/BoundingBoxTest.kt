package vn.vumap.feature.signs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.vumap.core.model.BoundingBox
import vn.vumap.core.model.GeoPoint

class BoundingBoxTest {

    @Test
    fun containsPointInside() {
        val bbox = BoundingBox(minLat = 10.0, minLon = 106.0, maxLat = 11.0, maxLon = 107.0)
        assertTrue(bbox.contains(GeoPoint(10.5, 106.5)))
        assertTrue(bbox.contains(GeoPoint(10.0, 106.0)))
        assertTrue(bbox.contains(GeoPoint(11.0, 107.0)))
        assertFalse(bbox.contains(GeoPoint(9.9, 106.5)))
        assertFalse(bbox.contains(GeoPoint(10.5, 107.1)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvertedLat() {
        BoundingBox(minLat = 12.0, minLon = 106.0, maxLat = 10.0, maxLon = 107.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvertedLon() {
        BoundingBox(minLat = 10.0, minLon = 108.0, maxLat = 11.0, maxLon = 106.0)
    }

    @Test
    fun fromCenterCreatesValidBbox() {
        val center = GeoPoint(10.8231, 106.6297)
        val bbox = BoundingBox.fromCenter(center, 500.0) // 500 meters

        assertTrue(bbox.contains(center))
        assertTrue(bbox.minLat < center.latitude)
        assertTrue(bbox.maxLat > center.latitude)
        assertTrue(bbox.minLon < center.longitude)
        assertTrue(bbox.maxLon > center.longitude)
    }

    @Test(expected = IllegalArgumentException::class)
    fun fromCenterRejectsNegativeRadius() {
        BoundingBox.fromCenter(GeoPoint(10.0, 106.0), -1.0)
    }
}
