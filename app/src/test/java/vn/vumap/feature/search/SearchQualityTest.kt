package vn.vumap.feature.search

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0 search quality score: 20 local address queries covering HCM hem,
 * HN ngo, Da Nang, Lam Dong and Mien Tay (project plan section 10 corpus
 * starter; initial task queue item 3).
 *
 * Every case runs fully offline (sanitizer, diacritic normalizer, admin
 * aliases, hem/ngo fallback detector, geo/link parser). The score is
 * reported per case; the gate requires 20/20.
 */
class SearchQualityTest {

    private fun loadCorpus(): JSONArray {
        val stream = javaClass.classLoader?.getResourceAsStream("search_quality_20.json")
            ?: throw IllegalStateException("search_quality_20.json not found on classpath")
        return JSONArray(stream.bufferedReader().use { it.readText() })
    }

    @Test
    fun twentyLocalQueriesScoreTwenty() {
        val corpus = loadCorpus()
        assertEquals("corpus must hold exactly 20 queries", 20, corpus.length())

        val report = StringBuilder()
        var score = 0
        for (i in 0 until corpus.length()) {
            val item = corpus.getJSONObject(i)
            val query = item.getString("query")
            val region = item.getString("region")
            val failures = mutableListOf<String>()

            // 1. Sanitizer must keep a searchable query.
            val sanitized = QuerySanitizer.forPhoton(query)
            if (sanitized.isNullOrEmpty()) failures.add("sanitized-empty")

            // 2. Expected normalized content (diacritic-insensitive recall).
            if (item.has("normalizedContains")) {
                val want = VietnameseNormalizer.matchingForm(item.getString("normalizedContains"))
                val got = VietnameseNormalizer.matchingForm(query)
                if (!got.contains(want)) failures.add("normalized-miss")
            }

            // 3. Expected admin alias variant (old/new names, nicknames).
            if (item.has("aliasVariant")) {
                val want = item.getString("aliasVariant")
                val variants = AdminAliases.variants(query)
                val hit = variants.any {
                    it.contains(want) ||
                        VietnameseNormalizer.matchingForm(it)
                            .contains(VietnameseNormalizer.matchingForm(want))
                }
                if (!hit) failures.add("alias-miss")
            }

            // 4. Hem/ngo/so-nha detailed-number intent flag.
            val wantHem = item.optBoolean("hemIntent", false)
            if (HemNgoFallback.hasDetailedNumber(query) != wantHem) failures.add("hem-intent")

            // 5. Street-level fallback remainder.
            if (item.has("fallbackQuery")) {
                val want = item.getString("fallbackQuery")
                if (HemNgoFallback.streetFallbackQuery(query) != want) failures.add("fallback-query")
            }

            // 6. Geo/link fast-path kind.
            if (item.has("geoKind")) {
                val parsed = GeoLinkParser.parse(query, null)
                val kind = when (parsed) {
                    is GeoLinkParser.ParsedLocation.Coordinates -> "coordinates"
                    is GeoLinkParser.ParsedLocation.PlusCode -> "pluscode"
                    is GeoLinkParser.ParsedLocation.PlaceQuery -> "placequery"
                    is GeoLinkParser.ParsedLocation.UnresolvableShortLink -> "shortlink"
                    is GeoLinkParser.ParsedLocation.ViewportOnly -> "viewport"
                    null -> "text"
                }
                if (kind != item.getString("geoKind")) failures.add("geo-kind=$kind")
            }

            if (failures.isEmpty()) {
                score++
                report.appendLine("PASS [$region] $query")
            } else {
                report.appendLine("FAIL [$region] $query :: ${failures.joinToString(",")}")
            }
        }

        println("VUMAP P0 search quality score: $score/20\n$report")
        assertTrue("quality report:\n$report", score == 20)
    }
}
