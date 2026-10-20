package vn.vumap.feature.search

/**
 * Hẻm/ngõ/số-nhà street-level fallback (project plan section 5).
 *
 * Photon (OSM-based) often lacks VN alley house numbers such as `123/45`,
 * `hẻm 123` or `ngõ 15`. When a detailed query returns nothing, retry with the
 * street + ward/district remainder and mark every fallback result
 * [VerificationLevel.STREET_ONLY] so the UI shows [STREET_ONLY_MESSAGE]
 * instead of a fake exact house-number pin.
 *
 * Pure Kotlin, no Android dependency, covered by JVM unit tests.
 */
object HemNgoFallback {

    // Operates on VietnameseNormalizer.matchingForm(query): lowercase, no diacritics.
    private val slashHouseNumber = Regex("^\\s*\\d+\\s*/\\s*\\d+[a-z]?\\s*,?\\s*")
    private val hemNgoPrefix = Regex("^\\s*(hem|ngo|ngach|hem)\\s+\\d+\\S*\\s*,?\\s*")
    private val soNhaPrefix = Regex("^\\s*(so\\s+nha|sn)\\s+\\d+\\S*\\s*,?\\s*")
    private val hemNgoAnywhere = Regex("\\b(hem|ngo|ngach)\\b\\s*\\d*")
    private val slashAnywhere = Regex("\\b\\d+\\s*/\\s*\\d+[a-z]?\\b")
    private val soNhaAnywhere = Regex("\\b(so\\s+nha|sn)\\s+\\d+")
    private val duongSoAnywhere = Regex("\\bduong\\s+so\\s+\\d+")
    private val phuongSoAnywhere = Regex("\\bphuong\\s+\\d+")

    /**
     * True when the query asks for a detailed house/alley number that Photon
     * frequently cannot resolve (`123/45`, `hẻm 123`, `ngõ 15`, `số nhà 8`,
     * `đường số 5`, `phường 3` style input).
     */
    fun hasDetailedNumber(query: String): Boolean {
        val norm = VietnameseNormalizer.matchingForm(query)
        if (norm.isEmpty()) return false
        return hemNgoAnywhere.containsMatchIn(norm) ||
            slashAnywhere.containsMatchIn(norm) ||
            soNhaAnywhere.containsMatchIn(norm) ||
            duongSoAnywhere.containsMatchIn(norm) ||
            phuongSoAnywhere.containsMatchIn(norm)
    }

    /**
     * Strips the leading detailed-number part, keeping street + admin remainder
     * for the street-level retry. Returns null when nothing searchable remains.
     *
     * Examples:
     * - "Hẻm 51 Nguyễn Thị Nghĩa, Quận 1" -> "Nguyễn Thị Nghĩa, Quận 1"
     * - "123/45 Cách Mạng Tháng Tám, Quận 3" -> "Cách Mạng Tháng Tám, Quận 3"
     */
    fun streetFallbackQuery(query: String): String? {
        var rest = query.trim()
        // Match on the normalized prefix length by re-matching against raw text
        // with diacritic-tolerant patterns.
        val rawPatterns = listOf(
            Regex("^\\s*\\d+\\s*/\\s*\\d+[a-zA-Z]?\\s*,?\\s*"),
            Regex("^\\s*(hẻm|hem|ngõ|ngo|ngách|ngach)\\s+\\d+\\S*\\s*,?\\s*", RegexOption.IGNORE_CASE),
            Regex("^\\s*(số\\s+nhà|so\\s+nha|sn)\\s+\\d+\\S*\\s*,?\\s*", RegexOption.IGNORE_CASE),
        )
        for (pattern in rawPatterns) {
            val m = pattern.find(rest)
            if (m != null) {
                rest = rest.substring(m.value.length)
                break
            }
        }
        rest = rest.trim().trimStart(',', ';', '-', '/').trim()
        if (rest.length < 3) return null
        return rest
    }

    /**
     * Downgrades results to street-level verification for the honest fallback path.
     * Already-Verified results are never fabricated here: the fallback path only
     * runs when the precise query returned nothing, so every result is at best
     * street-level.
     */
    fun markStreetOnly(results: List<SearchResult>): List<SearchResult> =
        results.map { it.copy(verification = VerificationLevel.STREET_ONLY) }
}
