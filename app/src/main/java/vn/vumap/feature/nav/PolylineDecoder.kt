package vn.vumap.feature.nav

import vn.vumap.core.model.GeoPoint

/**
 * Polyline encoder / decoder supporting both polyline6 (factor 1e6, OSRM/Valhalla default)
 * and polyline5 (factor 1e5, standard Google encoded polyline).
 *
 * Algorithm follows Google's Encoded Polyline Algorithm Format:
 * https://developers.google.com/maps/documentation/utilities/polylinealgorithm
 */
object PolylineDecoder {

    const val PRECISION_POLYLINE6 = 1e6
    const val PRECISION_POLYLINE5 = 1e5

    /**
     * Decodes an encoded polyline string into a list of [GeoPoint]s.
     *
     * @param encoded The encoded polyline string.
     * @param precision Coordinate divisor: 1e6 for polyline6, 1e5 for polyline5.
     */
    fun decode(encoded: String, precision: Double = PRECISION_POLYLINE6): List<GeoPoint> {
        if (encoded.isEmpty()) return emptyList()

        val points = mutableListOf<GeoPoint>()
        var index = 0
        val len = encoded.length
        var lat = 0
        var lng = 0

        while (index < len) {
            var b: Int
            var shift = 0
            var result = 0
            do {
                if (index >= len) break
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlat = if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
            lat += dlat

            shift = 0
            result = 0
            do {
                if (index >= len) break
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlng = if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
            lng += dlng

            val latitude = lat / precision
            val longitude = lng / precision

            // Clamp coordinates defensively within valid range
            val safeLat = latitude.coerceIn(-90.0, 90.0)
            val safeLng = longitude.coerceIn(-180.0, 180.0)
            points.add(GeoPoint(safeLat, safeLng))
        }

        return points
    }

    /**
     * Encodes a list of [GeoPoint]s into a polyline string with the given precision.
     */
    fun encode(points: List<GeoPoint>, precision: Double = PRECISION_POLYLINE6): String {
        val result = StringBuilder()
        var prevLat = 0
        var prevLng = 0

        for (point in points) {
            val lat = Math.round(point.latitude * precision).toInt()
            val lng = Math.round(point.longitude * precision).toInt()

            encodeSignedNumber(lat - prevLat, result)
            encodeSignedNumber(lng - prevLng, result)

            prevLat = lat
            prevLng = lng
        }

        return result.toString()
    }

    private fun encodeSignedNumber(num: Int, output: StringBuilder) {
        var sgnNum = if (num < 0) (num shl 1).inv() else (num shl 1)
        while (sgnNum >= 0x20) {
            val b = (0x20 or (sgnNum and 0x1f)) + 63
            output.append(b.toChar())
            sgnNum = sgnNum shr 5
        }
        output.append((sgnNum + 63).toChar())
    }
}
