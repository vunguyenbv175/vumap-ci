package vn.vumap.feature.search

import vn.vumap.core.model.GeoPoint

/**
 * Deterministic relevance ordering for provider text results.
 *
 * Photon's public instance is worldwide; without a country filter it happily
 * returns a UK pub and US driveways for "Landmark 81" next to the HCMC tower
 * (live evidence 2026-10-21). The app requests `countrycode=VN`, but result-side
 * handling still ranks and groups defensively:
 *
 * 1. Supported scope first: Vietnam, then results with missing country tags,
 *    then foreign results. Missing tags are NOT guessed as Vietnamese or
 *    foreign. V1 is Vietnam-first even when a provider ignores the request
 *    country filter.
 * 2. Query-match strength ([MatchEvidence.matchScore]) — exact phrase first.
 * 3. Distance from a REAL live fix only (null origin = no distance key).
 * 4. Original provider order (stable) for genuine ties.
 *
 * Foreign/unknown results are never hidden here; the UI labels them instead.
 */
object SearchResultRanker {

    fun rank(
        results: List<SearchResult>,
        query: String,
        origin: GeoPoint? = null,
    ): List<SearchResult> {
        if (results.size < 2) return results
        return results.withIndex()
            .sortedWith(
                compareBy<IndexedValue<SearchResult>> { scopeOrder(it.value.scope) }
                    .thenByDescending {
                        MatchEvidence.matchScore(query, it.value)
                    }
                    .thenBy {
                        origin?.let { from ->
                            SearchDistance.meters(from, it.value.point)
                        } ?: 0.0
                    }
                    .thenBy { it.index },
            )
            .map { it.value }
    }

    private fun scopeOrder(scope: ResultScope): Int = when (scope) {
        ResultScope.VIETNAM -> 0
        ResultScope.UNKNOWN -> 1
        ResultScope.FOREIGN -> 2
    }
}
