package vn.vumap.feature.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.vumap.core.model.GeoPoint

/**
 * Favorites/history store tests (pure logic; DataStore wrapper is thin).
 */
class SearchStoreTest {

    private fun sample(id: String, name: String = "Chợ Bến Thành") = SearchResult(
        id = id,
        name = name,
        detail = "Quận 1, TP. Hồ Chí Minh",
        point = GeoPoint(10.7725, 106.6980),
        source = SearchSource.PHOTON,
        verification = VerificationLevel.VERIFIED,
    )

    @Test
    fun favoritesAddRemoveAndLookup() {
        val store = SearchStore()
        store.addFavorite(sample("a"))
        assertTrue(store.isFavorite("a"))
        assertTrue(store.removeFavorite("a"))
        assertFalse(store.isFavorite("a"))
        assertFalse(store.removeFavorite("missing"))
    }

    @Test
    fun historyDedupesAndCaps() {
        val store = SearchStore()
        repeat(SearchStore.MAX_HISTORY + 5) { i -> store.recordHistory(sample("h$i", "Place $i")) }
        val hist = store.history()
        assertEquals(SearchStore.MAX_HISTORY, hist.size)
        // Re-recording moves to front without duplication.
        store.recordHistory(sample("h0", "Place 0"))
        val updated = store.history()
        assertEquals(SearchStore.MAX_HISTORY, updated.size)
        assertEquals("h0", updated.first().id)
        assertEquals(1, updated.count { it.id == "h0" })
        store.clearHistory()
        assertTrue(store.history().isEmpty())
    }

    @Test
    fun findMatchesIsDiacriticInsensitiveFavoritesFirst() {
        val store = SearchStore()
        val fav = sample("fav", "Hẻm 51 Nguyễn Thị Nghĩa")
        store.addFavorite(fav)
        store.recordHistory(sample("hist", "Hẻm 52 Nguyễn Thị Nghĩa"))
        // No-diacritics query matches both accented entries, favorite first.
        val hits = store.findMatches("hem nguyen thi nghia")
        assertEquals(2, hits.size)
        assertEquals("fav", hits.first().id)
        assertEquals("hist", hits.last().id)
        // Narrower query matches only the favorite.
        val narrow = store.findMatches("hem 51 nguyen thi nghia")
        assertEquals(1, narrow.size)
        assertEquals("fav", narrow.first().id)
    }

    @Test
    fun recentExcludesFavoritesAfterSelect() {
        val store = SearchStore()
        val fav = sample("fav", "Hẻm 51 Nguyễn Thị Nghĩa")
        val other = sample("other", "Chợ Bến Thành")
        // Save flow: favorite it, then select it (SearchScreen onPick calls
        // coordinator.select -> recordHistory) and select another result.
        store.addFavorite(fav)
        store.recordHistory(fav)
        store.recordHistory(other)
        // Recent section input hides the favorite id, so the favorites +
        // recent sections share no id and LazyColumn keys stay unique.
        val recent = store.recentExcludingFavorites()
        assertEquals(listOf("other"), recent.map { it.id })
        assertTrue(recent.none { it.id == "fav" })
        val combined = store.favorites() + recent
        assertEquals(combined.size, combined.map { it.id }.toSet().size)
        // Removing the favorite makes it reappear in recent (history kept it).
        store.removeFavorite("fav")
        assertEquals(listOf("other", "fav"), store.recentExcludingFavorites().map { it.id })
    }

    @Test
    fun encodeDecodeRoundTrip() {
        val store = SearchStore()
        store.addFavorite(sample("a", "Ngõ 15 Trung Kính"))
        store.recordHistory(sample("b"))
        val (favs, hist) = store.snapshot()
        val restored = SearchStore()
        restored.restore(
            SearchStore.decode(SearchStore.encode(favs)),
            SearchStore.decode(SearchStore.encode(hist)),
        )
        assertEquals(listOf("a"), restored.favorites().map { it.id })
        assertEquals(listOf("b"), restored.history().map { it.id })
    }

    @Test
    fun decodeRejectsGarbage() {
        assertTrue(SearchStore.decode("").isEmpty())
        assertTrue(SearchStore.decode("not json").isEmpty())
        // Out-of-range coordinates are skipped.
        assertTrue(SearchStore.decode("""[{"id":"x","name":"X","detail":"","lat":999.0,"lon":0.0}]""").isEmpty())
    }
}
