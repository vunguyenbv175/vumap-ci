package vn.vumap.feature.search

/**
 * Query sanitizer for P0 search input (project plan section 5).
 *
 * - Trims, collapses whitespace, caps length (prevents oversized Photon URLs).
 * - Strips control characters and symbols that can never appear in a VN
 *   address, coordinate, Plus Code or supported link. Keeps `/ , . + - @`
 *   because house numbers (`123/45`), coordinates, Plus Codes and URLs need them.
 * - Returns null for empty input so callers map it to [SearchError.EMPTY_QUERY].
 *
 * Pure Kotlin, no Android dependency, covered by JVM unit tests.
 */
object QuerySanitizer {

    const val MAX_QUERY_LENGTH = 200

    // Allowed beyond letters/digits/spaces: slash (123/45), comma/dot (coords),
    // plus (Plus Codes and Maps place links), hyphen (ranges), @ (Maps camera),
    // : (geo:), ! (Google Maps data parameter `!3d..!4d..` = shared place).
    private val disallowed = Regex("[^\\p{L}\\p{Nd} \\t/.,+\\-:;@()?%&=#_\"'\\[\\]!]+")
    private val whitespace = Regex("\\s+")
    private val controlChars = Regex("[\\p{Cntrl}]+")

    /**
     * Sanitized query for a Photon text search, capped at [MAX_QUERY_LENGTH],
     * or null when nothing searchable remains.
     */
    fun sanitize(raw: String?): String? {
        val q = clean(raw) ?: return null
        val capped = if (q.length > MAX_QUERY_LENGTH) q.take(MAX_QUERY_LENGTH).trim() else q
        return capped.ifEmpty { null }
    }

    /**
     * Sanitized input for location PARSING (geo: URIs, coordinate pairs,
     * Maps links, Plus Codes), deliberately NOT length-capped: the text-query
     * cap must never shorten a shared URL before its destination is read.
     * A cut inside `!4d106.6980` leaves `106.69`, a complete-looking but
     * wrong longitude kilometers away — so parsing always sees intact input.
     */
    fun sanitizeUncapped(raw: String?): String? = clean(raw)

    /**
     * Builds a safe Photon `q` parameter (additionally trims trailing
     * separators left by sanitizing). Capped — text search only.
     */
    fun forPhoton(raw: String?): String? =
        sanitize(raw)?.trim(' ', ',', ';', '.', '/')?.ifEmpty { null }

    /**
     * Sanitized location input for parsing: same cleaning as [forPhoton]
     * (control chars stripped, disallowed symbols removed, trailing
     * separators trimmed) but uncapped.
     */
    fun forLocationInput(raw: String?): String? =
        sanitizeUncapped(raw)?.trim(' ', ',', ';', '.', '/')?.ifEmpty { null }

    private fun clean(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var q = controlChars.replace(raw, " ")
        q = disallowed.replace(q, " ")
        q = whitespace.replace(q, " ").trim()
        return q.ifEmpty { null }
    }
}
