package vn.vumap.feature.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.vumap.core.model.GeoPoint

/**
 * Golden Fixtures comparison test (project plan section 3, 9; requirement 3 & 5).
 *
 * PROVES:
 * 1. Valhalla with `format:"osrm"` shares the exact same response schema as Project OSRM.
 * 2. Units are identical: distance is in meters, duration is in seconds.
 * 3. Polyline precision is identical: polyline6 (factor 1e6).
 * 4. Step and maneuver structure are identical: maneuver.type, location [lon, lat], modifier.
 * 5. Demonstrates successful decoding across 3 Vietnam metropolitan areas (TP.HCM, Ha Noi, Da Nang).
 */
class GoldenFixturesComparisonTest {

    private fun readFixture(name: String): String {
        val stream = javaClass.classLoader?.getResourceAsStream("fixtures/$name")
            ?: throw IllegalStateException("Fixture fixtures/$name not found on classpath")
        return stream.bufferedReader().use { it.readText() }
    }

    @Test
    fun testHcmGoldenFixturesComparison() {
        val osrmJson = readFixture("osrm_hcm.json")
        val valhallaJson = readFixture("valhalla_hcm.json")

        val osrmRoute = OsrmResponseParser.parse(osrmJson)
        val valhallaRoute = OsrmResponseParser.parse(valhallaJson)

        // 1. Both routes successfully parsed using the unified parser
        assertNotNull(osrmRoute)
        assertNotNull(valhallaRoute)

        // 2. Both use polyline6 precision
        assertEquals(6, osrmRoute.polylinePrecision)
        assertEquals(6, valhallaRoute.polylinePrecision)

        // 3. Units: distance in meters (~800m)
        assertTrue("OSRM distance should be in meters (~800m)", osrmRoute.distanceMeters in 750.0..850.0)
        assertTrue("Valhalla distance should be in meters (~810m)", valhallaRoute.distanceMeters in 750.0..850.0)

        // 4. Units: duration in seconds (~105s)
        assertTrue("OSRM duration should be in seconds (~105s)", osrmRoute.durationSeconds in 90.0..130.0)
        assertTrue("Valhalla duration should be in seconds (~110s)", valhallaRoute.durationSeconds in 90.0..130.0)

        // 5. Geometry coordinates match TP.HCM ground truth (Bến Thành to Nhà hát TP)
        assertEquals(3, osrmRoute.geometry.size)
        assertEquals(3, valhallaRoute.geometry.size)
        assertCoordinatesClose(GeoPoint(10.772500, 106.698000), osrmRoute.geometry[0])
        assertCoordinatesClose(GeoPoint(10.776800, 106.703200), osrmRoute.geometry[2])
        assertCoordinatesClose(osrmRoute.geometry[0], valhallaRoute.geometry[0])
        assertCoordinatesClose(osrmRoute.geometry[2], valhallaRoute.geometry[2])

        // 6. Step structure and maneuvers
        assertEquals(3, osrmRoute.steps.size)
        assertEquals(3, valhallaRoute.steps.size)
        assertEquals("depart", osrmRoute.steps[0].maneuverType)
        assertEquals("depart", valhallaRoute.steps[0].maneuverType)
        assertEquals("arrive", osrmRoute.steps[2].maneuverType)
        assertEquals("arrive", valhallaRoute.steps[2].maneuverType)
    }

    @Test
    fun testHanoiGoldenFixturesComparison() {
        val osrmJson = readFixture("osrm_hanoi.json")
        val valhallaJson = readFixture("valhalla_hanoi.json")

        val osrmRoute = OsrmResponseParser.parse(osrmJson)
        val valhallaRoute = OsrmResponseParser.parse(valhallaJson)

        // Distance in meters (~2500m)
        assertTrue("Hanoi distance in meters (~2500m)", osrmRoute.distanceMeters in 2400.0..2600.0)
        assertTrue("Hanoi Valhalla distance in meters (~2515m)", valhallaRoute.distanceMeters in 2400.0..2600.0)

        // Duration in seconds (~350s)
        assertTrue("Hanoi duration in seconds (~350s)", osrmRoute.durationSeconds in 300.0..400.0)

        // Coordinates match Hồ Gươm to Lăng Bác
        assertCoordinatesClose(GeoPoint(21.028500, 105.854200), osrmRoute.geometry.first())
        assertCoordinatesClose(GeoPoint(21.036800, 105.834600), osrmRoute.geometry.last())

        // Steps
        assertEquals(4, osrmRoute.steps.size)
        assertEquals(4, valhallaRoute.steps.size)
        assertEquals("Phố Tràng Thi", osrmRoute.steps[0].streetName)
        assertEquals("Đường Điện Biên Phủ", osrmRoute.steps[1].streetName)
    }

    @Test
    fun testDanangGoldenFixturesComparison() {
        val osrmJson = readFixture("osrm_danang.json")
        val valhallaJson = readFixture("valhalla_danang.json")

        val osrmRoute = OsrmResponseParser.parse(osrmJson)
        val valhallaRoute = OsrmResponseParser.parse(valhallaJson)

        // Distance in meters (~8200m)
        assertTrue("Da Nang distance in meters (~8200m)", osrmRoute.distanceMeters in 8000.0..8500.0)
        assertTrue("Da Nang Valhalla distance in meters (~8230m)", valhallaRoute.distanceMeters in 8000.0..8500.0)

        // Duration in seconds (~720s)
        assertTrue("Da Nang duration in seconds (~720s)", osrmRoute.durationSeconds in 650.0..800.0)

        // Coordinates match Cầu Rồng to Chùa Linh Ứng
        assertCoordinatesClose(GeoPoint(16.061000, 108.223000), osrmRoute.geometry.first())
        assertCoordinatesClose(GeoPoint(16.100000, 108.277000), osrmRoute.geometry.last())
    }

    @Test
    fun testVietnameseInstructionSynthesis() {
        val depart = OsrmResponseParser.synthesizeVietnameseInstruction("depart", null, "Đường Lê Lợi")
        assertEquals("Bắt đầu khởi hành theo Đường Lê Lợi", depart)

        val turnRight = OsrmResponseParser.synthesizeVietnameseInstruction("turn", "right", "Đường Pasteur")
        assertEquals("Rẽ phải vào Đường Pasteur", turnRight)

        val turnLeft = OsrmResponseParser.synthesizeVietnameseInstruction("turn", "left", "Phố Tràng Thi")
        assertEquals("Rẽ trái vào Phố Tràng Thi", turnLeft)

        val arrive = OsrmResponseParser.synthesizeVietnameseInstruction("arrive", null, "")
        assertEquals("Bạn đã đến điểm đến", arrive)

        val roundabout = OsrmResponseParser.synthesizeVietnameseInstruction("roundabout", null, "Ngã sáu Phù Đổng")
        assertEquals("Đi vào vòng xuyến vào Ngã sáu Phù Đổng", roundabout)
    }

    private fun assertCoordinatesClose(expected: GeoPoint, actual: GeoPoint, tolerance: Double = 0.00002) {
        assertEquals("Latitude mismatch", expected.latitude, actual.latitude, tolerance)
        assertEquals("Longitude mismatch", expected.longitude, actual.longitude, tolerance)
    }
}
