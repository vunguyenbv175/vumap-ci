package vn.vumap.feature.search

/**
 * Query-match evidence for verification badges (project plan sections 0.3, 5).
 *
 * [VerificationLevel.VERIFIED] from Photon states that the pin carries
 * structural precision — a housenumber or a named POI. It does NOT state that
 * the result answers the query. Live evidence 2026-10-17 (vumap-30) showed an
 * unrelated church, a zoo and a Nha Trang academy returned as VERIFIED top-1
 * for unrelated HCM queries: structural precision must not masquerade as a
 * verified requested hem/address.
 *
 * A VERIFIED badge therefore also needs match evidence: after stripping
 * locality vocabulary (alias keys/canonicals from [AdminAliases], generic
 * administrative words) and pure digits — which prove nothing about the match —
 * the user's query must share at least one content token with the result's
 * name or street.
 *
 * Pure Kotlin, no Android dependency, covered by JVM tests. The rule is a
 * conservative heuristic: a zero-overlap result is shown as UNVERIFIED (never
 * hidden), a pure-locality query always passes, and a same-street /
 * different-number overlap still passes — the UI caveat tells users to check
 * the pin before navigating.
 */
object MatchEvidence {

    private const val EXACT_PHRASE_SCORE = 100
    private const val ALL_TOKENS_SCORE = 60
    private const val PARTIAL_TOKEN_SCORE = 10

    /** Generic administrative words that prove nothing about a match. */
    private val GENERIC_LOCALITY_WORDS = setOf("quan", "phuong", "xa", "tp")

    /**
     * True when [result] may keep a [VerificationLevel.VERIFIED] badge for
     * [query]; false when the pin is exact but shares no content with what the
     * user actually asked for.
     */
    fun matchesQuery(query: String, result: SearchResult): Boolean {
        if (contentTokenList(query).isEmpty()) return true // pure locality: nothing to prove
        // Badge evidence deliberately ignores pure digits: "Quận 1" or a bare
        // house number must not turn an unrelated exact pin into a match.
        return score(query, result, keepDigits = false) > 0
    }

    /**
     * Relevance score used only for ranking. Zero means no content overlap
     * (the result is still displayed, just lower). Higher is stronger:
     * - the query's content words appear as one contiguous phrase in the
     *   result name/street,
     * - every content word appears (possibly reordered),
     * - only some words appear.
     *
     * Digits are kept here (unlike [matchesQuery]'s badge gate): "81" is real
     * evidence when ranking "Landmark 81", even though a bare house number
     * alone must not prove the requested address.
     */
    fun matchScore(query: String, result: SearchResult): Int =
        score(query, result, keepDigits = true)

    private fun score(query: String, result: SearchResult, keepDigits: Boolean): Int {
        val tokens = contentTokenList(query, keepDigits = keepDigits)
        if (tokens.isEmpty()) return 0
        val hayTokens = resultTextTokens(result)
        val haySet = hayTokens.toSet()
        // Whole-token windows, not String.contains: "cafe" must not score as
        // an exact phrase just because "Cafeteria" contains the substring.
        val hasExactTokenPhrase = tokens.size <= hayTokens.size &&
            hayTokens.windowed(tokens.size).any { window -> window == tokens }
        return when {
            hasExactTokenPhrase -> EXACT_PHRASE_SCORE + tokens.size
            tokens.all { it in haySet } -> ALL_TOKENS_SCORE + tokens.size
            else -> tokens.count { it in haySet } * PARTIAL_TOKEN_SCORE
        }
    }

    /**
     * Content tokens of the query: normalized words minus locality vocabulary
     * (alias keys/canonicals + generic administrative words) and, for the
     * badge gate, pure digits.
     */
    private fun contentTokenList(query: String, keepDigits: Boolean = false): List<String> =
        wordTokens(query)
            .filter { keepDigits || !it.all { c -> c.isDigit() } }
            .filterNot { it in GENERIC_LOCALITY_WORDS }
            .filterNot { it in AdminAliases.localityTokens }
            .distinct()

    /**
     * Tokens the result may be matched by: its name plus the street/house
     * segment — [PhotonResponseParser] writes detail as street, district,
     * city, country, so the first segment is the street-level part.
     */
    private fun resultTextTokens(result: SearchResult): List<String> =
        wordTokens("${result.name} ${result.detail.substringBefore(',')}")

    private fun wordTokens(text: String): List<String> =
        VietnameseNormalizer.matchingForm(text)
            .split(' ')
            .filter { it.isNotEmpty() }
            // "TP.", "Quận 1," and "TP" are the same words for matching: the
            // normalizer preserves commas for link/coordinate parsing, so the
            // token-level matcher must trim separator punctuation itself.
            .map { it.trim('.', ',', ';', ':', '/') }
            .filter { it.isNotEmpty() }
}
