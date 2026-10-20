package vn.vumap.feature.search

import org.json.JSONArray
import org.json.JSONObject
import vn.vumap.core.model.GeoPoint

/**
 * Pure in-memory favorites/history logic (project plan section 5:
 * "luu local alias/favorite").
 *
 * Kept Android-free so JVM unit tests cover cap, dedupe and matching.
 * [FavoritesRepository] persists snapshots via DataStore.
 */
class SearchStore {

    private val favorites = LinkedHashMap<String, SearchResult>()
    private val history = ArrayDeque<SearchResult>()

    fun favorites(): List<SearchResult> = favorites.values.toList()

    fun history(): List<SearchResult> = history.toList()

    fun addFavorite(result: SearchResult) {
        favorites[result.id] = result.copy(source = SearchSource.FAVORITE)
        if (favorites.size > MAX_FAVORITES) {
            val oldest = favorites.keys.first()
            favorites.remove(oldest)
        }
    }

    fun removeFavorite(id: String): Boolean = favorites.remove(id) != null

    fun isFavorite(id: String): Boolean = favorites.containsKey(id)

    fun recordHistory(result: SearchResult) {
        history.removeAll { it.id == result.id }
        history.addFirst(result.copy(source = SearchSource.HISTORY))
        while (history.size > MAX_HISTORY) history.removeLast()
    }

    fun clearHistory() {
        history.clear()
    }

    /**
     * Offline recall: favorites first, then history, diacritic-insensitive.
     * Token-based so reordered input ("quan 1 nguyen thi nghia") still recalls.
     */
    fun findMatches(query: String, limit: Int = 5): List<SearchResult> {
        if (query.isBlank()) return emptyList()
        val favHits = favorites.values.filter {
            VietnameseNormalizer.matchesAllTokens(query, "${it.name} ${it.detail}")
        }
        val histHits = history.filter { h ->
            VietnameseNormalizer.matchesAllTokens(query, "${h.name} ${h.detail}") &&
                favHits.none { f -> f.id == h.id }
        }
        return (favHits + histHits).take(limit.coerceAtLeast(1))
    }

    fun snapshot(): Pair<List<SearchResult>, List<SearchResult>> =
        favorites() to history()

    /**
     * History entries for the "recent" UI section: history minus anything
     * already saved as a favorite.
     *
     * Picking a favorite also records it in history ([SearchCoordinator.select]
     * calls [recordHistory] on every pick), so without this filter the same id
     * would render in both the favorites and recent sections — and LazyColumn
     * keys must be unique across the whole list, not just per section, so the
     * duplicate would throw. Mirrors the favorites-first exclusion already used
     * by [findMatches].
     */
    fun recentExcludingFavorites(): List<SearchResult> =
        recentExcludingFavorites(favorites(), history())

    fun restore(favs: List<SearchResult>, hist: List<SearchResult>) {
        favorites.clear()
        favs.take(MAX_FAVORITES).forEach { favorites[it.id] = it }
        history.clear()
        hist.take(MAX_HISTORY).forEach { history.addLast(it) }
    }

    companion object {
        const val MAX_FAVORITES = 100
        const val MAX_HISTORY = 30

        /**
         * Pure helper behind [SearchStore.recentExcludingFavorites]: drops every
         * history entry whose id is already a favorite, preserving order.
         */
        fun recentExcludingFavorites(
            favorites: List<SearchResult>,
            history: List<SearchResult>,
        ): List<SearchResult> {
            if (favorites.isEmpty()) return history
            val favoriteIds = HashSet<String>(favorites.size)
            favorites.forEach { favoriteIds.add(it.id) }
            return history.filter { it.id !in favoriteIds }
        }

        /** JSON round-trip for DataStore persistence. */
        fun encode(results: List<SearchResult>): String {
            val arr = JSONArray()
            for (r in results) {
                arr.put(
                    JSONObject()
                        .put("id", r.id)
                        .put("name", r.name)
                        .put("detail", r.detail)
                        .put("lat", r.point.latitude)
                        .put("lon", r.point.longitude)
                        .put("source", r.source.name)
                        .put("verification", r.verification.name)
                        .put("locality", r.localityKey ?: JSONObject.NULL)
                        .put("countryCode", r.countryCode ?: JSONObject.NULL)
                        .put("countryName", r.countryName ?: JSONObject.NULL),
                )
            }
            return arr.toString()
        }

        fun decode(json: String): List<SearchResult> {
            if (json.isBlank()) return emptyList()
            val out = ArrayList<SearchResult>()
            try {
                val arr = JSONArray(json)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val lat = o.optDouble("lat", Double.NaN)
                    val lon = o.optDouble("lon", Double.NaN)
                    if (lat.isNaN() || lon.isNaN()) continue
                    try {
                        out.add(
                            SearchResult(
                                id = o.optString("id", "$lat,$lon"),
                                name = o.optString("name", "Đã lưu"),
                                detail = o.optString("detail", ""),
                                point = GeoPoint(lat, lon),
                                source = runCatching {
                                    SearchSource.valueOf(o.optString("source", "HISTORY"))
                                }.getOrDefault(SearchSource.HISTORY),
                                verification = runCatching {
                                    VerificationLevel.valueOf(o.optString("verification", "UNVERIFIED"))
                                }.getOrDefault(VerificationLevel.UNVERIFIED),
                                localityKey = o.optString("locality", "").ifEmpty { null },
                                // Older snapshots have no country fields: they
                                // decode as null (UNKNOWN scope), never guessed.
                                countryCode = o.optNullableString("countryCode"),
                                countryName = o.optNullableString("countryName"),
                            ),
                        )
                    } catch (e: IllegalArgumentException) {
                        continue
                    }
                }
            } catch (e: Exception) {
                return emptyList()
            }
            return out
        }
    }
}

/** Reads a nullable string field, treating an explicit JSON null as absent. */
private fun JSONObject.optNullableString(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return optString(key, "").trim().ifEmpty { null }
}
