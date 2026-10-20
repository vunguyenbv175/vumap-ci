package vn.vumap.feature.search

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val Context.searchDataStore: DataStore<Preferences> by preferencesDataStore(name = "vumap_search")

private val KEY_FAVORITES = stringPreferencesKey("favorites_json")
private val KEY_HISTORY = stringPreferencesKey("history_json")

/**
 * Persistence boundary for [FavoritesRepository]: DataStore on device, an
 * in-memory fake in JVM unit tests.
 */
interface SearchStorage {
    val favorites: Flow<List<SearchResult>>
    val history: Flow<List<SearchResult>>

    /** One-time snapshot used to seed the in-memory [SearchStore]. */
    suspend fun load(): Pair<List<SearchResult>, List<SearchResult>>

    /** Persists the full favorites/history snapshot. */
    suspend fun save(favorites: List<SearchResult>, history: List<SearchResult>)
}

/**
 * DataStore-backed [SearchStorage]. The file stays in the app sandbox.
 * No account, no cloud sync.
 */
class DataStoreSearchStorage(private val appContext: Context) : SearchStorage {

    override val favorites: Flow<List<SearchResult>> =
        appContext.searchDataStore.data.map { prefs ->
            SearchStore.decode(prefs[KEY_FAVORITES].orEmpty())
        }

    override val history: Flow<List<SearchResult>> =
        appContext.searchDataStore.data.map { prefs ->
            SearchStore.decode(prefs[KEY_HISTORY].orEmpty())
        }

    override suspend fun load(): Pair<List<SearchResult>, List<SearchResult>> {
        val prefs = appContext.searchDataStore.data.first()
        return SearchStore.decode(prefs[KEY_FAVORITES].orEmpty()) to
            SearchStore.decode(prefs[KEY_HISTORY].orEmpty())
    }

    override suspend fun save(favorites: List<SearchResult>, history: List<SearchResult>) {
        appContext.searchDataStore.edit { prefs ->
            prefs[KEY_FAVORITES] = SearchStore.encode(favorites)
            prefs[KEY_HISTORY] = SearchStore.encode(history)
        }
    }
}

/**
 * Local favorites/history repository (project plan section 5; core/storage).
 * Delegates matching/cap logic to the unit-tested [SearchStore].
 *
 * Every load/mutate/persist operation is serialized by [mutex], and the
 * one-time initialization runs inside the same lock. Without that, a second
 * caller could enter the initialization while the first DataStore read was
 * still in flight and later restore its stale snapshot over a favorite or
 * history entry saved in the meantime (silently losing the mutation).
 */
class FavoritesRepository(
    private val storage: SearchStorage,
) : LocalRecall {

    constructor(appContext: Context) : this(DataStoreSearchStorage(appContext))

    private val store = SearchStore()
    private val mutex = Mutex()

    /** Guarded by [mutex]; true once [store] holds the persisted snapshot. */
    private var loaded = false

    val favorites: Flow<List<SearchResult>> get() = storage.favorites

    val history: Flow<List<SearchResult>> get() = storage.history

    private suspend fun ensureLoadedLocked() {
        if (loaded) return
        val (favs, hist) = storage.load()
        store.restore(favs, hist)
        loaded = true
    }

    private suspend fun <T> withLoadedStore(block: () -> T): T = mutex.withLock {
        ensureLoadedLocked()
        block()
    }

    /**
     * Runs one mutation plus its persistence under [mutex], so no concurrent
     * initialization can restore a stale snapshot over it and no two snapshots
     * can be written out of order.
     */
    private suspend fun mutate(block: (SearchStore) -> Unit) {
        mutex.withLock {
            ensureLoadedLocked()
            block(store)
            val (favs, hist) = store.snapshot()
            storage.save(favs, hist)
        }
    }

    suspend fun addFavorite(result: SearchResult) = mutate { it.addFavorite(result) }

    suspend fun removeFavorite(id: String) {
        mutate { it.removeFavorite(id) }
    }

    suspend fun isFavorite(id: String): Boolean = withLoadedStore { store.isFavorite(id) }

    override suspend fun recordHistory(result: SearchResult) = mutate { it.recordHistory(result) }

    suspend fun clearHistory() {
        mutate { it.clearHistory() }
    }

    /**
     * Offline recall for result merging (favorites first, diacritic-insensitive).
     */
    override suspend fun findMatches(query: String, limit: Int): List<SearchResult> =
        withLoadedStore { store.findMatches(query, limit) }
}
