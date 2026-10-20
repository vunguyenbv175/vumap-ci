package vn.vumap.feature.search

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.vumap.core.model.GeoPoint

/**
 * [FavoritesRepository] ordering tests with a controllable storage fake.
 *
 * The DataStore implementation is a thin adapter, but the one-time load plus
 * mutation sequencing is pure logic and must not lose mutations when another
 * caller starts while the first read is still in flight (native review:
 * "loses mutations during concurrent initialization").
 */
class FavoritesRepositoryTest {

    private fun sample(id: String) = SearchResult(
        id = id,
        name = "Chợ Bến Thành",
        detail = "Quận 1, TP. Hồ Chí Minh",
        point = GeoPoint(10.7725, 106.6980),
        source = SearchSource.FAVORITE,
        verification = VerificationLevel.UNVERIFIED,
    )

    /**
     * In-memory [SearchStorage] whose [load] captures the snapshot when the read
     * *starts* (like DataStore) and then waits on a test gate, so tests can
     * force the "slow initialization + concurrent mutation" interleaving.
     */
    private class GatedStorage(
        favorites: List<SearchResult> = emptyList(),
        history: List<SearchResult> = emptyList(),
    ) : SearchStorage {
        private val favState = MutableStateFlow(favorites)
        private val histState = MutableStateFlow(history)
        private val gates = Channel<CompletableDeferred<Unit>>(Channel.UNLIMITED)
        val loadStarted = Channel<Unit>(Channel.UNLIMITED)

        var loadCount = 0
            private set
        var saveCount = 0
            private set

        override val favorites: Flow<List<SearchResult>> get() = favState
        override val history: Flow<List<SearchResult>> get() = histState

        /** Hand out one gate per expected [load] call. */
        fun nextGate(): CompletableDeferred<Unit> =
            CompletableDeferred<Unit>().also { gates.trySend(it) }

        override suspend fun load(): Pair<List<SearchResult>, List<SearchResult>> {
            loadCount++
            val snapshot = favState.value to histState.value
            loadStarted.trySend(Unit)
            gates.receive().await()
            return snapshot
        }

        override suspend fun save(favorites: List<SearchResult>, history: List<SearchResult>) {
            saveCount++
            favState.value = favorites
            histState.value = history
        }

        suspend fun savedFavorites(): List<SearchResult> = favState.first()
    }

    @Test
    fun concurrentFirstCallsShareOneInitialization() = runBlocking {
        val storage = GatedStorage(favorites = listOf(sample("old")))
        val repo = FavoritesRepository(storage)
        val firstGate = storage.nextGate()
        // A racing caller must not start a second load; hand out a gate anyway
        // so the un-fixed behavior fails on the assertion instead of hanging.
        val staleGate = storage.nextGate()

        val read = async(Dispatchers.Default) { repo.findMatches("cho ben thanh") }
        storage.loadStarted.receive()
        val favorite = async(Dispatchers.Default) { repo.addFavorite(sample("new")) }
        delay(100) // let the second caller reach the repository

        firstGate.complete(Unit)
        staleGate.complete(Unit)
        read.await()
        favorite.await()

        assertEquals("the persisted snapshot must be read exactly once", 1, storage.loadCount)
        assertEquals(listOf("old", "new"), storage.savedFavorites().map { it.id })
    }

    @Test
    fun mutationIsNotLostWhenAnotherInitializationIsStillInFlight() = runBlocking {
        val storage = GatedStorage(favorites = listOf(sample("old")))
        val repo = FavoritesRepository(storage)
        val mutationGate = storage.nextGate()
        val staleGate = storage.nextGate()

        val mutation = async(Dispatchers.Default) { repo.addFavorite(sample("new")) }
        storage.loadStarted.receive() // the mutation's own load is in flight

        val late = async(Dispatchers.Default) { repo.findMatches("cho ben thanh") }
        // Fixed: `late` waits for the mutex. Un-fixed: it starts a second
        // (stale) load that is still in flight when the mutation lands.
        withTimeoutOrNull(300) { storage.loadStarted.receive() }

        mutationGate.complete(Unit)
        mutation.await()
        assertEquals(listOf("old", "new"), storage.savedFavorites().map { it.id })

        staleGate.complete(Unit)
        late.await()

        // The late restore must not have wiped the freshly saved favorite.
        assertTrue(repo.isFavorite("new"))
        assertEquals(listOf("old", "new"), storage.savedFavorites().map { it.id })
    }

    @Test
    fun mutationsPersistThroughStorageAndFlow() = runBlocking {
        val storage = GatedStorage(favorites = listOf(sample("old")))
        val repo = FavoritesRepository(storage)
        val gate = storage.nextGate()

        val job = async(Dispatchers.Default) { repo.addFavorite(sample("new")) }
        storage.loadStarted.receive()
        gate.complete(Unit)
        job.await()

        assertTrue(repo.isFavorite("new"))
        assertEquals(listOf("old", "new"), storage.savedFavorites().map { it.id })
        assertEquals(listOf("old", "new"), repo.favorites.first().map { it.id })
        assertEquals(1, storage.saveCount)
    }
}
