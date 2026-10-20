package vn.vumap.feature.search

import com.google.openlocationcode.OpenLocationCode
import vn.vumap.core.model.GeoPoint

/**
 * Shared-location input parser (project plan section 5):
 * Android `geo:` intents, plain `lat,lon`, Google Maps / shared-location links
 * and Plus Codes.
 *
 * Honesty rules:
 * - Only exact coordinates are decoded to pins. Place-name URLs are returned
 *   as text queries (never guessed to coordinates).
 * - Google Maps links: only the place data pair (`!3d/!4d`) or `q=`/`query=`
 *   coordinates are a shared destination. A bare `@lat,lng` is the map
 *   camera / viewport and is reported as [ParsedLocation.ViewportOnly]
 *   instead of a pin at the camera position.
 * - Short links (`goo.gl/maps`, `maps.app.goo.gl`) need a network resolve
 *   that P0 does not perform offline-safe; they are reported as
 *   [ParsedLocation.UnresolvableShortLink] with a clear message instead of a
 *   guessed pin.
 * - Plus Codes are decoded with the explicit `openlocationcode` library
 *   (no hand-rolled decoder). Short codes need a reference point (user GPS);
 *   without one they fall back to text search.
 *
 * Pure Kotlin (library `OpenLocationCode` is JVM-safe), covered by JVM tests.
 */
object GeoLinkParser {

    /** Parsed shared-location input. */
    sealed interface ParsedLocation {
        data class Coordinates(val point: GeoPoint, val label: String?) : ParsedLocation
        data class PlusCode(val code: String, val point: GeoPoint) : ParsedLocation
        /** A place URL whose name becomes a text search query. */
        data class PlaceQuery(val query: String) : ParsedLocation
        data class UnresolvableShortLink(val url: String, val message: String) : ParsedLocation
        /**
         * A Google Maps link whose only position is the `@lat,lng` camera /
         * viewport. The camera center is NOT a shared destination (the place
         * data `!3d/!4d` pair or `q=` carry those), so it must never be pinned
         * as the target.
         */
        data class ViewportOnly(val url: String, val message: String) : ParsedLocation
    }

    private val geoUri = Regex("^geo:(-?\\d+(?:[.,]\\d+)?),(-?\\d+(?:[.,]\\d+)?)(?:\\?.*)?$", RegexOption.IGNORE_CASE)
    private val coordPair = Regex("^(-?\\d+(?:[.,]\\d+)?)\\s*[,;]\\s*(-?\\d+(?:[.,]\\d+)?)$")
    private val mapsAtCoords = Regex("@(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)(?:,|$|/)")
    private val mapsQueryParam = Regex("[?&](?:q|query)=(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)")
    /**
     * Exact Google Maps place coordinates in the data parameter: `!3dLAT!4dLON`.
     * Degrees must be complete decimal fields (`10.7725`): a numeric prefix
     * that stops short of its decimals must never be accepted as a coordinate.
     */
    private val mapsPlaceData = Regex("!3d(-?\\d+\\.\\d+)!4d(-?\\d+\\.\\d+)")
    private val placePath = Regex("/place/([^/@?]+)")
    private val shortLinkHosts = listOf("goo.gl", "maps.app.goo.gl", "maps.google.com")
    private val geoQueryParam = Regex("[?&]q=([^&]*)", RegexOption.IGNORE_CASE)

    /**
     * Raw `q` value of a `geo:` URI (still percent/plus-encoded), or null when
     * the URI carries no query. Matched on the full input, not the base pair.
     */
    private fun geoQuery(input: String): String? =
        geoQueryParam.find(input)?.groupValues?.getOrNull(1)

    /** Decodes `+`/percent-encoding of a URI query value on plain JVM. */
    private fun decodeQueryParam(raw: String): String {
        val plus = raw.replace('+', ' ')
        val out = StringBuilder(plus.length)
        var i = 0
        while (i < plus.length) {
            val c = plus[i]
            if (c == '%' && i + 2 < plus.length) {
                val hex = plus.substring(i + 1, i + 3)
                val byte = hex.toIntOrNull(16)
                if (byte != null) {
                    // Reassemble UTF-8 sequences byte by byte.
                    val bytes = mutableListOf<Byte>()
                    var j = i
                    while (j + 2 < plus.length && plus[j] == '%' &&
                        plus.substring(j + 1, j + 3).toIntOrNull(16) != null
                    ) {
                        bytes.add(plus.substring(j + 1, j + 3).toInt(16).toByte())
                        j += 3
                    }
                    out.append(String(bytes.toByteArray(), Charsets.UTF_8))
                    i = j
                    continue
                }
            }
            out.append(c)
            i++
        }
        return out.toString().trim()
    }

    /**
     * Coordinates carried in a `geo:` q value: `q=lat,lon` or `q=lat,lon(label)`.
     * Null when q is a place name (caller runs a text search instead).
     */
    private fun geoQueryPoint(decodedQuery: String): GeoPoint? {
        val coords = decodedQuery.substringBefore('(').trim()
        coordPair.matchEntire(coords)?.let { m ->
            return toPoint(m.groupValues[1], m.groupValues[2])
        }
        return null
    }

    /** Optional `(label)` suffix of a `geo:` q value, for the result display name. */
    private fun geoLabel(decodedQuery: String): String? {
        val label = decodedQuery.substringAfter('(', "").substringBefore(')').trim()
        return label.ifEmpty { null }
    }
    private val fullPlusCode = Regex("\\b[23456789CFGHJMPQRVWX]{8}\\+[23456789CFGHJMPQRVWX]{2,3}\\b")
    private val shortPlusCode = Regex("\\b[23456789CFGHJMPQRVWX]{4,7}\\+[23456789CFGHJMPQRVWX]{0,3}\\b")

    /**
     * Parses shared-location input, or returns null when the input is a plain
     * text query (caller then runs Photon text search). [reference] is the
     * optional user GPS fix used to recover short Plus Codes.
     */
    fun parse(input: String, reference: GeoPoint? = null): ParsedLocation? {
        val text = input.trim()
        if (text.isEmpty()) return null

        // 1. Android geo: Intent.
        geoUri.matchEntire(text)?.let { m ->
            val queryPart = geoQuery(text)
            if (queryPart != null) {
                // The q parameter is the actual destination; the base pair is
                // often a placeholder (geo:0,0?q=...). Never route to a
                // placeholder: q coordinates win, a q place name becomes a
                // Photon text query, and an empty q uses the base pair.
                val decoded = decodeQueryParam(queryPart)
                if (decoded.isNotEmpty()) {
                    geoQueryPoint(decoded)?.let { point ->
                        val label = geoLabel(decoded)
                        return ParsedLocation.Coordinates(point, label)
                    }
                    return ParsedLocation.PlaceQuery(decoded)
                }
            }
            toPoint(m.groupValues[1], m.groupValues[2])?.let { return ParsedLocation.Coordinates(it, null) }
        }

        // 2. Plain lat,lon pair.
        coordPair.matchEntire(text)?.let { m ->
            toPoint(m.groupValues[1], m.groupValues[2])?.let { return ParsedLocation.Coordinates(it, null) }
        }

        // 3. Google Maps / shared-location links.
        if (text.contains("http", ignoreCase = true) || '/' in text) {
            val linkResult = parseLink(text)
            if (linkResult != null) return linkResult
        }

        // 4. Full Plus Code via the explicit library.
        fullPlusCode.find(text)?.let { m ->
            if (OpenLocationCode.isValidCode(m.value) && OpenLocationCode.isFullCode(m.value)) {
                try {
                    val area = OpenLocationCode(m.value).decode()
                    return ParsedLocation.PlusCode(
                        code = m.value,
                        point = GeoPoint(area.centerLatitude, area.centerLongitude),
                    )
                } catch (e: Exception) {
                    // Invalid per library: fall through to text search.
                }
            }
        }

        // 5. Short Plus Code with a GPS reference.
        if (reference != null) {
            shortPlusCode.find(text)?.let { m ->
                try {
                    if (OpenLocationCode.isValidCode(m.value) && OpenLocationCode.isShortCode(m.value)) {
                        val recovered = OpenLocationCode(m.value)
                            .recover(reference.latitude, reference.longitude).code
                        val area = OpenLocationCode(recovered).decode()
                        return ParsedLocation.PlusCode(
                            code = recovered,
                            point = GeoPoint(area.centerLatitude, area.centerLongitude),
                        )
                    }
                } catch (e: Exception) {
                    // Fall through to text search.
                }
            }
        }

        return null
    }

    private fun parseLink(text: String): ParsedLocation? {
        val lower = text.lowercase()
        val isMapLink = lower.contains("google.") && lower.contains("/maps") ||
            lower.contains("maps.google.") ||
            lower.contains("goo.gl/maps") ||
            lower.contains("maps.app.goo.gl")
        if (!isMapLink) return null

        // Short links need a network resolve P0 does not perform: honest message.
        if (lower.contains("goo.gl/maps") || lower.contains("maps.app.goo.gl")) {
            if (!mapsAtCoords.containsMatchIn(text) && !mapsQueryParam.containsMatchIn(text)) {
                return ParsedLocation.UnresolvableShortLink(
                    url = text,
                    message = "Link rút gọn cần mạng để giải mã; P0 chưa hỗ trợ — " +
                        "hãy mở link trong app bản đồ hoặc dán tọa độ.",
                )
            }
        }

        val placeName = placePath.find(text)?.groupValues?.getOrNull(1)
            ?.replace('+', ' ')?.replace('-', ' ')?.trim()
            ?.takeIf { it.length >= 2 }

        // 1. Exact destination from the place data parameter (!3dLAT!4dLON).
        //    Google also emits "@lat,lng,zoom" for the map camera; that pair is
        //    only a viewport and must never be treated as the destination.
        mapsPlaceData.find(text)?.let { m ->
            toPoint(m.groupValues[1], m.groupValues[2])?.let {
                return ParsedLocation.Coordinates(it, placeName)
            }
        }

        // 2. Destination coordinates in the query parameter (?q=/&query=).
        mapsQueryParam.find(text)?.let { m ->
            toPoint(m.groupValues[1], m.groupValues[2])?.let {
                return ParsedLocation.Coordinates(it, null)
            }
        }

        // 3. Place name in the path becomes a text search query.
        if (placeName != null) return ParsedLocation.PlaceQuery(placeName)

        // 4. Only camera/viewport coordinates are present: no destination.
        if (mapsAtCoords.containsMatchIn(text)) {
            return ParsedLocation.ViewportOnly(
                url = text,
                message = "Link chỉ mở khung nhìn bản đồ, không có điểm đến cụ thể — " +
                    "hãy mở link trong app bản đồ hoặc dán tọa độ điểm đến.",
            )
        }
        return null
    }

    private fun toPoint(latRaw: String, lonRaw: String): GeoPoint? {
        // Vietnamese decimal commas ("10,7725") are accepted when each side has
        // exactly one comma acting as the decimal separator.
        val lat = latRaw.replace(',', '.').toDoubleOrNull() ?: return null
        val lon = lonRaw.replace(',', '.').toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return try {
            GeoPoint(lat, lon)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /**
     * Converts a parsed location into a [SearchResult] pin. Coordinates and
     * full Plus Codes are exact pins (VERIFIED as user-supplied positions,
     * not geocoder evidence); place queries are handled by text search.
     * A caller-supplied label (e.g. a `geo:` q label) names the pin.
     */
    fun toResult(parsed: ParsedLocation): SearchResult? = when (parsed) {
        is ParsedLocation.Coordinates -> SearchResult(
            id = "link:${parsed.point.latitude},${parsed.point.longitude}",
            name = parsed.label ?: "${parsed.point.latitude}, ${parsed.point.longitude}",
            detail = "Tọa độ từ link chia sẻ",
            point = parsed.point,
            source = SearchSource.GEO_LINK,
            verification = VerificationLevel.VERIFIED,
        )
        is ParsedLocation.PlusCode -> SearchResult(
            id = "plus:${parsed.code}",
            name = parsed.code,
            detail = "Plus Code",
            point = parsed.point,
            source = SearchSource.PLUS_CODE,
            verification = VerificationLevel.VERIFIED,
        )
        else -> null
    }
}
