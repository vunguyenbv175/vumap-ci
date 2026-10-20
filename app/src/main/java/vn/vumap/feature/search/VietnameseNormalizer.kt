package vn.vumap.feature.search

import java.text.Normalizer

/**
 * Vietnamese diacritic normalization (project plan section 5: "Viet co dau/khong dau").
 *
 * Pure Kotlin, no Android dependency, covered by JVM unit tests.
 *
 * Rules:
 * - Unicode NFD decomposition + strip combining marks (tones, hats, horns).
 * - `d`/`D` stroke letters map to plain `d` (both "đi" and "di" become "di").
 * - Lowercase, collapse whitespace, keep letters/digits/spaces only for matching.
 */
object VietnameseNormalizer {

    private val combiningMarks = Regex("\\p{M}+")
    private val whitespace = Regex("\\s+")
    private val nonWord = Regex("[^\\p{L}\\p{Nd} /.,+\\-@]+")

    /**
     * Full display-safe normalization: lowercased, diacritics removed.
     * Punctuation listed in [nonWord] is preserved for link/coordinate parsing.
     */
    fun normalized(input: String): String {
        val decomposed = Normalizer.normalize(input, Normalizer.Form.NFD)
        val stripped = combiningMarks.replace(decomposed, "")
        return whitespace.replace(
            stripped.replace('đ', 'd').replace('Đ', 'd').lowercase(),
            " ",
        ).trim()
    }

    /**
     * Strict matching form: only letters, digits and single spaces.
     * Use for contains-match between user query and candidate names.
     */
    fun matchingForm(input: String): String {
        val cleaned = nonWord.replace(normalized(input), " ")
        return whitespace.replace(cleaned, " ").trim()
    }

    /**
     * True when [candidate] contains [query] ignoring diacritics and case,
     * so "hem 51" matches "Hẻm 51" and "ngo 15" matches "Ngõ 15".
     */
    fun matches(query: String, candidate: String): Boolean {
        val q = matchingForm(query)
        if (q.isEmpty()) return false
        return matchingForm(candidate).contains(q)
    }

    /**
     * Token-level match: every query token must appear in the candidate.
     * More forgiving for reordered input like "quan 1 nguyen thi nghia".
     */
    fun matchesAllTokens(query: String, candidate: String): Boolean {
        val tokens = matchingForm(query).split(" ").filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return false
        val haystack = matchingForm(candidate)
        return tokens.all { haystack.contains(it) }
    }
}
