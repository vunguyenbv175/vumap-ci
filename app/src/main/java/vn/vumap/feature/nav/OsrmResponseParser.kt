package vn.vumap.feature.nav

import org.json.JSONObject
import vn.vumap.core.model.GeoPoint

/**
 * Parses OSRM-compatible routing responses.
 *
 * Both public OSRM and Valhalla with `format:"osrm"` emit this format.
 * Coordinates in geometry are polyline6 encoded (factor 1e6).
 * Distance is in meters, duration is in seconds.
 */
object OsrmResponseParser {

    fun parse(jsonString: String): Route {
        val root = JSONObject(jsonString)
        val code = root.optString("code", "")
        if (!code.equals("Ok", ignoreCase = true)) {
            val message = root.optString("message", "Status code not Ok: $code")
            throw IllegalArgumentException("OSRM response error: $message (code=$code)")
        }

        val routesArray = root.optJSONArray("routes")
            ?: throw IllegalArgumentException("No routes array found in response")
        if (routesArray.length() == 0) {
            throw IllegalArgumentException("Routes array is empty")
        }

        val routeObj = routesArray.getJSONObject(0)
        val geometryStr = routeObj.optString("geometry", "")
        val totalDistance = routeObj.optDouble("distance", 0.0)
        val totalDuration = routeObj.optDouble("duration", 0.0)

        val geometry = if (geometryStr.isNotEmpty()) {
            PolylineDecoder.decode(geometryStr, PolylineDecoder.PRECISION_POLYLINE6)
        } else {
            emptyList()
        }

        val steps = mutableListOf<RouteStep>()
        val legsArray = routeObj.optJSONArray("legs")
        var summary = ""

        if (legsArray != null) {
            for (i in 0 until legsArray.length()) {
                val legObj = legsArray.getJSONObject(i)
                if (summary.isEmpty()) {
                    summary = legObj.optString("summary", "")
                }
                val stepsArray = legObj.optJSONArray("steps") ?: continue
                for (j in 0 until stepsArray.length()) {
                    val stepObj = stepsArray.getJSONObject(j)
                    val stepDist = stepObj.optDouble("distance", 0.0)
                    val stepDur = stepObj.optDouble("duration", 0.0)
                    val streetName = stepObj.optString("name", "").trim()

                    val maneuverObj = stepObj.optJSONObject("maneuver")
                    val type = maneuverObj?.optString("type", "continue") ?: "continue"
                    val rawModifier = maneuverObj?.optString("modifier", "") ?: ""
                    val modifier = rawModifier.ifEmpty { null }

                    val locArray = maneuverObj?.optJSONArray("location")
                    val stepLoc = if (locArray != null && locArray.length() >= 2) {
                        val lon = locArray.getDouble(0)
                        val lat = locArray.getDouble(1)
                        GeoPoint(lat, lon)
                    } else if (geometry.isNotEmpty()) {
                        geometry.first()
                    } else {
                        GeoPoint(0.0, 0.0)
                    }

                    val providedInstruction = stepObj.optString("instruction", "").trim()
                    val instruction = if (providedInstruction.isNotEmpty()) {
                        providedInstruction
                    } else {
                        synthesizeVietnameseInstruction(type, modifier, streetName)
                    }

                    steps.add(
                        RouteStep(
                            instruction = instruction,
                            distanceMeters = stepDist,
                            durationSeconds = stepDur,
                            location = stepLoc,
                            maneuverType = type,
                            maneuverModifier = modifier,
                            streetName = streetName,
                        ),
                    )
                }
            }
        }

        return Route(
            geometry = geometry,
            distanceMeters = totalDistance,
            durationSeconds = totalDuration,
            steps = steps,
            summary = summary,
            polylinePrecision = 6,
        )
    }

    /**
     * Synthesizes natural Vietnamese turn instruction from maneuver type, modifier, and street name.
     */
    fun synthesizeVietnameseInstruction(type: String, modifier: String?, streetName: String): String {
        val target = if (streetName.isNotEmpty()) " vào $streetName" else ""
        val onStreet = if (streetName.isNotEmpty()) " trên $streetName" else ""

        return when (type.lowercase()) {
            "depart" -> if (streetName.isNotEmpty()) "Bắt đầu khởi hành theo $streetName" else "Bắt đầu khởi hành"
            "arrive" -> "Bạn đã đến điểm đến"
            "turn" -> when (modifier?.lowercase()) {
                "left" -> "Rẽ trái$target"
                "right" -> "Rẽ phải$target"
                "slight left" -> "Chếch sang trái$target"
                "slight right" -> "Chếch sang phải$target"
                "sharp left" -> "Rẽ ngoặt sang trái$target"
                "sharp right" -> "Rẽ ngoặt sang phải$target"
                "uturn" -> "Quay đầu xe"
                else -> "Rẽ$target"
            }
            "new name", "continue" -> "Đi tiếp$onStreet"
            "merge" -> "Nhập làn$target"
            "fork" -> when (modifier?.lowercase()) {
                "left" -> "Rẽ nhánh bên trái$target"
                "right" -> "Rẽ nhánh bên phải$target"
                else -> "Đi theo lối rẽ$target"
            }
            "roundabout", "rotary" -> "Đi vào vòng xuyến$target"
            "exit roundabout", "exit rotary" -> "Rời khỏi vòng xuyến$target"
            else -> "Đi tiếp$onStreet"
        }
    }
}
