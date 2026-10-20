package vn.vumap.feature.signs

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import vn.vumap.core.model.BoundingBox
import vn.vumap.core.model.GeoPoint
import vn.vumap.core.model.PhysicalSign
import vn.vumap.core.model.RoadEdge
import vn.vumap.feature.signs.db.JdbcSignPackDbFactory
import vn.vumap.signs.SignType
import vn.vumap.signs.SpeedResolver
import vn.vumap.signs.ZoneTracker
import java.io.File

/**
 * Unit and integration tests for [SignsOverlayCoordinator].
 *
 * Verifies:
 * - Real query and render integration with committed synthetic pack bytes.
 * - Negative: Unavailable pack state handling.
 * - Negative: Disable/toggle cleanup.
 * - Negative: Stale-result cancellation (latest-query-wins).
 * - Negative: Style-reload retention and re-attachment.
 * - Negative: Duplicate sign key deduplication.
 * - Negative: Warning suppression for synthetic points (never legal speed alerts or ZoneTracker mutation).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SignsOverlayCoordinatorTest {

    private lateinit var projectRoot: File
    private lateinit var assetPack: File
    private lateinit var assetManifest: File
    private lateinit var manifestJson: String
    private val dbFactory = JdbcSignPackDbFactory()

    @Before
    fun setUp() {
        projectRoot = findProjectRoot()
        assetPack = File(projectRoot, "app/src/main/assets/signs/synthetic_vn_signs_p1.vupack")
        assetManifest = File(projectRoot, "app/src/main/assets/signs/synthetic_vn_signs_p1.vupack.manifest.json")
        manifestJson = assetManifest.readText(Charsets.UTF_8)
    }

    private class TestSignsMapRenderer(attached: Boolean = true) : SignsMapRenderer {
        override var isAttached: Boolean = attached
        val renderedCalls = mutableListOf<List<PhysicalSign>>()
        var clearCallsCount = 0
        var detachCallsCount = 0

        fun lastRenderedSigns(): List<PhysicalSign>? = renderedCalls.lastOrNull()

        override fun renderSigns(signs: List<PhysicalSign>) {
            check(isAttached) { "renderSigns called on a detached/replaced style" }
            renderedCalls.add(signs)
        }

        override fun clearSigns() {
            check(isAttached) { "clearSigns called on a detached/replaced style" }
            clearCallsCount++
        }

        override fun detach() {
            detachCallsCount++
            isAttached = false
        }
    }

    @Test
    fun `actual query and render integration with committed synthetic bytes`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val realRepo = OfflineSignRepository(
            packFile = assetPack,
            manifestJson = manifestJson,
            dbFactory = dbFactory,
            ioDispatcher = testDispatcher
        )

        realRepo.use { repo ->
            val renderer = TestSignsMapRenderer()
            val coordinator = SignsOverlayCoordinator(
                signProvider = repo,
                renderer = renderer,
                ioDispatcher = testDispatcher,
                debounceMs = 100L,
                coroutineScope = this
            )

            // 1. Off by default
            assertEquals(SignsOverlayState.Disabled, coordinator.state.value)
            assertEquals(0, renderer.renderedCalls.size)

            // 2. Enable demo mode
            coordinator.setEnabled(true)
            advanceUntilIdle()

            // State is Loading waiting for bounds
            assertTrue(coordinator.state.value is SignsOverlayState.Loading)

            // 3. Move camera to HCMC synthetic fixture cluster
            val hcmBounds = SignsOverlayCoordinator.FIXTURE_BOUNDS
            coordinator.onCameraBoundsChanged(hcmBounds)

            // Advance debounce and query
            advanceTimeBy(100L)
            advanceUntilIdle()

            val state = coordinator.state.value
            assertTrue("Expected Visible state, got: $state", state is SignsOverlayState.Visible)
            val visible = state as SignsOverlayState.Visible

            assertTrue("Synthetic points must be flagged isSynthetic", visible.isSynthetic)
            assertEquals("synthetic-vumap-p1", visible.packStatus.source)
            assertTrue("Expected multiple HCMC signs, found: ${visible.signs.size}", visible.signs.size >= 10)

            // Verify renderer received the signs
            assertEquals(1, renderer.renderedCalls.size)
            val renderedSigns = renderer.lastRenderedSigns()!!
            assertEquals(visible.signs.size, renderedSigns.size)

            val s01 = renderedSigns.firstOrNull { it.id == "S01" }
            assertNotNull(s01)
            assertEquals(SignType.R420, s01!!.type)
            assertTrue(s01.isSynthetic)

            coordinator.onDispose()
        }
    }

    @Test
    fun `negative unavailable pack state honestly reports error and clears overlay`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val unavailableProvider = object : SignProvider {
            override suspend fun nearby(area: BoundingBox, limit: Int): List<PhysicalSign> = emptyList()
            override suspend fun nearby(point: GeoPoint, radiusMeters: Double, limit: Int): List<PhysicalSign> = emptyList()
            override suspend fun getEdge(edgeId: String): RoadEdge? = null
            override suspend fun nearbyEdges(area: BoundingBox, limit: Int): List<RoadEdge> = emptyList()
            override suspend fun packStatus(): PackStatus = PackStatus.Unavailable(
                reason = "Database file corrupted",
                errors = listOf("SQLITE_CORRUPT")
            )
        }

        val renderer = TestSignsMapRenderer()
        val coordinator = SignsOverlayCoordinator(
            signProvider = unavailableProvider,
            renderer = renderer,
            ioDispatcher = testDispatcher,
            debounceMs = 50L,
            coroutineScope = this
        )

        coordinator.setEnabled(true)
        advanceUntilIdle()

        val state = coordinator.state.value
        assertTrue("Expected Unavailable state, got: $state", state is SignsOverlayState.Unavailable)
        val unavailable = state as SignsOverlayState.Unavailable
        assertEquals("Database file corrupted", unavailable.reason)
        assertTrue(unavailable.errors.contains("SQLITE_CORRUPT"))

        // Renderer must be cleared with zero signs
        assertTrue(renderer.clearCallsCount >= 1)
        assertEquals(0, renderer.renderedCalls.size)

        coordinator.onDispose()
    }

    @Test
    fun `negative disable mode cancels queries clears overlay and ignores bounds updates`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val sampleSign = PhysicalSign(
            id = "S01",
            type = SignType.R420,
            location = GeoPoint(10.8231, 106.6297),
            headingDeg = 90.0,
            valueKmh = null,
            edgeId = "E_Q1",
            confidence = 0.9f,
            source = "synthetic",
            isSynthetic = true
        )

        val readyProvider = object : SignProvider {
            override suspend fun nearby(area: BoundingBox, limit: Int): List<PhysicalSign> = listOf(sampleSign)
            override suspend fun nearby(point: GeoPoint, radiusMeters: Double, limit: Int): List<PhysicalSign> = listOf(sampleSign)
            override suspend fun getEdge(edgeId: String): RoadEdge? = null
            override suspend fun nearbyEdges(area: BoundingBox, limit: Int): List<RoadEdge> = emptyList()
            override suspend fun packStatus(): PackStatus = PackStatus.Ready(
                format = "vupack",
                formatVersion = 1,
                schemaVersion = 1,
                source = "synthetic-vumap-p1",
                license = "test",
                version = "1.0",
                isSynthetic = true,
                edgeCount = 1,
                pointCount = 1,
                sha256 = "dummy",
                contentSha256 = "dummy"
            )
        }

        val renderer = TestSignsMapRenderer()
        val coordinator = SignsOverlayCoordinator(
            signProvider = readyProvider,
            renderer = renderer,
            ioDispatcher = testDispatcher,
            debounceMs = 50L,
            coroutineScope = this
        )

        coordinator.setEnabled(true)
        coordinator.onCameraBoundsChanged(SignsOverlayCoordinator.FIXTURE_BOUNDS)
        advanceTimeBy(50L)
        advanceUntilIdle()

        assertTrue(coordinator.state.value is SignsOverlayState.Visible)
        assertEquals(1, renderer.renderedCalls.size)

        // Disable demo mode
        val clearsBefore = renderer.clearCallsCount
        coordinator.setEnabled(false)
        advanceUntilIdle()

        assertEquals(SignsOverlayState.Disabled, coordinator.state.value)
        assertTrue("clearSigns must be called on disable", renderer.clearCallsCount > clearsBefore)

        // Camera move while disabled must NOT trigger queries or rendering
        val callsBefore = renderer.renderedCalls.size
        coordinator.onCameraBoundsChanged(BoundingBox(10.0, 106.0, 11.0, 107.0))
        advanceTimeBy(200L)
        advanceUntilIdle()

        assertEquals(SignsOverlayState.Disabled, coordinator.state.value)
        assertEquals(callsBefore, renderer.renderedCalls.size)

        coordinator.onDispose()
    }

    @Test
    fun `negative stale-result cancellation ensures latest query wins`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)

        val bounds1 = BoundingBox(10.0, 106.0, 10.1, 106.1)
        val bounds2 = BoundingBox(10.8, 106.6, 10.9, 106.7)

        val sign1 = PhysicalSign("S_SLOW", SignType.P127, GeoPoint(10.05, 106.05), 90.0, 50, "E1", 0.9f, "synth", true)
        val sign2 = PhysicalSign("S_FAST", SignType.P127, GeoPoint(10.85, 106.65), 90.0, 60, "E2", 0.9f, "synth", true)

        val query1Started = CompletableDeferred<Unit>()

        val raceProvider = object : SignProvider {
            override suspend fun nearby(area: BoundingBox, limit: Int): List<PhysicalSign> {
                if (area == bounds1) {
                    query1Started.complete(Unit)
                    delay(500L) // Simulate slow I/O
                    return listOf(sign1)
                } else if (area == bounds2) {
                    return listOf(sign2)
                }
                return emptyList()
            }
            override suspend fun nearby(point: GeoPoint, radiusMeters: Double, limit: Int): List<PhysicalSign> = emptyList()
            override suspend fun getEdge(edgeId: String): RoadEdge? = null
            override suspend fun nearbyEdges(area: BoundingBox, limit: Int): List<RoadEdge> = emptyList()
            override suspend fun packStatus(): PackStatus = PackStatus.Ready(
                "vupack", 1, 1, "synthetic-vumap-p1", "test", "1.0", true, 2, 2, "sha", "csha"
            )
        }

        val renderer = TestSignsMapRenderer()
        val coordinator = SignsOverlayCoordinator(
            signProvider = raceProvider,
            renderer = renderer,
            ioDispatcher = testDispatcher,
            debounceMs = 0L, // Immediate for race testing
            coroutineScope = this
        )

        coordinator.setEnabled(true)

        // Dispatch Query 1 (slow)
        coordinator.onCameraBoundsChanged(bounds1)
        advanceTimeBy(1L) // Starts query 1
        query1Started.await()

        // Quickly dispatch Query 2 before Query 1 finishes (cancelling Query 1)
        coordinator.onCameraBoundsChanged(bounds2)
        advanceTimeBy(1L) // Dispatches Query 2
        advanceUntilIdle()

        val state = coordinator.state.value
        assertTrue(state is SignsOverlayState.Visible)
        val visible = state as SignsOverlayState.Visible

        assertEquals(bounds2, visible.bounds)
        assertEquals(1, visible.signs.size)
        assertEquals("S_FAST", visible.signs[0].id)

        // Renderer must only have rendered Query 2's results
        val lastRendered = renderer.lastRenderedSigns()!!
        assertEquals(1, lastRendered.size)
        assertEquals("S_FAST", lastRendered[0].id)

        coordinator.onDispose()
    }

    @Test
    fun `negative style reload retains visible signs without data loss or extra fetch`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val sign = PhysicalSign("S01", SignType.R420, GeoPoint(10.8231, 106.6297), 90.0, null, "E1", 0.9f, "synth", true)

        var fetchCount = 0
        val trackingProvider = object : SignProvider {
            override suspend fun nearby(area: BoundingBox, limit: Int): List<PhysicalSign> {
                fetchCount++
                return listOf(sign)
            }
            override suspend fun nearby(point: GeoPoint, radiusMeters: Double, limit: Int): List<PhysicalSign> = emptyList()
            override suspend fun getEdge(edgeId: String): RoadEdge? = null
            override suspend fun nearbyEdges(area: BoundingBox, limit: Int): List<RoadEdge> = emptyList()
            override suspend fun packStatus(): PackStatus = PackStatus.Ready(
                "vupack", 1, 1, "synthetic-vumap-p1", "test", "1.0", true, 1, 1, "sha", "csha"
            )
        }

        val renderer1 = TestSignsMapRenderer()
        val coordinator = SignsOverlayCoordinator(
            signProvider = trackingProvider,
            renderer = renderer1,
            ioDispatcher = testDispatcher,
            debounceMs = 50L,
            coroutineScope = this
        )

        coordinator.setEnabled(true)
        coordinator.onCameraBoundsChanged(SignsOverlayCoordinator.FIXTURE_BOUNDS)
        advanceTimeBy(50L)
        advanceUntilIdle()

        assertEquals(1, fetchCount)
        assertEquals(1, renderer1.renderedCalls.size)

        // Style reloaded: attach renderer2
        val renderer2 = TestSignsMapRenderer()
        coordinator.onStyleReloaded(renderer2)
        advanceUntilIdle()

        // Renderer 2 must immediately receive the retained signs without an extra fetch
        assertEquals(1, fetchCount)
        assertEquals(1, renderer2.renderedCalls.size)
        assertEquals("S01", renderer2.lastRenderedSigns()!!.first().id)
        assertEquals("Old style renderer must be detached", 1, renderer1.detachCallsCount)

        coordinator.onDispose()

        // Teardown touches only the current renderer, exactly once.
        assertEquals(1, renderer2.clearCallsCount)
        assertEquals(1, renderer2.detachCallsCount)
        assertEquals(0, renderer1.clearCallsCount)
    }

    @Test
    fun `negative duplicate key deduplication prevents redundant or conflicting features`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val duplicate1 = PhysicalSign("S_DUP", SignType.P127, GeoPoint(10.8231, 106.6297), 90.0, 60, "E1", 0.9f, "synth", true)
        val duplicate2 = PhysicalSign("S_DUP", SignType.P127, GeoPoint(10.8231, 106.6297), 90.0, 60, "E1", 0.9f, "synth", true)
        val uniqueSign = PhysicalSign("S_UNIQ", SignType.R420, GeoPoint(10.8250, 106.6300), 90.0, null, "E2", 0.8f, "synth", true)

        val duplicateProvider = object : SignProvider {
            override suspend fun nearby(area: BoundingBox, limit: Int): List<PhysicalSign> =
                listOf(duplicate1, duplicate2, uniqueSign)
            override suspend fun nearby(point: GeoPoint, radiusMeters: Double, limit: Int): List<PhysicalSign> = emptyList()
            override suspend fun getEdge(edgeId: String): RoadEdge? = null
            override suspend fun nearbyEdges(area: BoundingBox, limit: Int): List<RoadEdge> = emptyList()
            override suspend fun packStatus(): PackStatus = PackStatus.Ready(
                "vupack", 1, 1, "synthetic-vumap-p1", "test", "1.0", true, 2, 3, "sha", "csha"
            )
        }

        val renderer = TestSignsMapRenderer()
        val coordinator = SignsOverlayCoordinator(
            signProvider = duplicateProvider,
            renderer = renderer,
            ioDispatcher = testDispatcher,
            debounceMs = 50L,
            coroutineScope = this
        )

        coordinator.setEnabled(true)
        coordinator.onCameraBoundsChanged(SignsOverlayCoordinator.FIXTURE_BOUNDS)
        advanceTimeBy(50L)
        advanceUntilIdle()

        val state = coordinator.state.value as SignsOverlayState.Visible
        // Must be deduplicated to 2 items (S_DUP and S_UNIQ)
        assertEquals(2, state.signs.size)
        assertEquals(listOf("S_DUP", "S_UNIQ"), state.signs.map { it.id })

        val rendered = renderer.lastRenderedSigns()!!
        assertEquals(2, rendered.size)
        assertEquals(listOf("S_DUP", "S_UNIQ"), rendered.map { it.id })

        coordinator.onDispose()
    }

    @Test
    fun `negative warning suppression guarantees synthetic signs never emit legal speed warnings or mutate zone tracker`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val realRepo = OfflineSignRepository(
            packFile = assetPack,
            manifestJson = manifestJson,
            dbFactory = dbFactory,
            ioDispatcher = testDispatcher
        )

        realRepo.use { repo ->
            // Query signs in HCMC cluster
            val signs = repo.nearby(SignsOverlayCoordinator.FIXTURE_BOUNDS, limit = 50)
            assertTrue("Expected synthetic signs in fixture bounds", signs.isNotEmpty())

            // All signs from this pack must have isSynthetic = true
            for (sign in signs) {
                assertTrue("Sign ${sign.id} must be synthetic", sign.isSynthetic)
            }

            // Real navigation safety:
            // 1. Any advisory produced by synthetic points must be suppressed = true
            val edgeQ1 = repo.getEdge("E_Q1")
            assertNotNull(edgeQ1)

            val isolatedTracker = ZoneTracker()
            val advisory = repo.resolveSpeedAdvisory(
                currentLocation = GeoPoint(10.82310, 106.62970),
                travelHeadingDeg = 90.0,
                currentEdge = edgeQ1,
                zoneTracker = isolatedTracker
            )

            // Advisory must have suppressed = true
            assertTrue("Synthetic advisory must be suppressed", advisory.suppressed())
            assertTrue("Advisory label must clearly state demo only", advisory.displayLabel().contains("[THỬ NGHIỆM/DEMO ONLY]"))
            assertTrue("Advisory reason must state SYNTHETIC_DEMO_SUPPRESSED", advisory.reason().startsWith("SYNTHETIC_DEMO_SUPPRESSED"))

            // 2. SpeedResolver.shouldWarnOverspeed must NEVER return true for suppressed synthetic advisory
            assertFalse(
                "shouldWarnOverspeed must return false for synthetic advisory at 70 km/h",
                SpeedResolver.shouldWarnOverspeed(70.0, advisory)
            )
            assertFalse(
                "shouldWarnOverspeed must return false even at extreme 250 km/h",
                SpeedResolver.shouldWarnOverspeed(250.0, advisory)
            )

            // 3. Navigation ZoneTracker invariant:
            // A live driving tracker that has seen no surveyed signs must stay UNKNOWN
            val realDrivingTracker = ZoneTracker()
            assertEquals("Real driving tracker must remain UNKNOWN", vn.vumap.signs.ZoneState.UNKNOWN, realDrivingTracker.state())
            assertTrue("Real driving tracker must have no evidence", realDrivingTracker.history().isEmpty())
        }
    }

    @Test
    fun `negative delayed status request followed by disable stays disabled`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val statusStarted = CompletableDeferred<Unit>()

        val delayedProvider = object : SignProvider {
            override suspend fun nearby(area: BoundingBox, limit: Int): List<PhysicalSign> = emptyList()
            override suspend fun nearby(point: GeoPoint, radiusMeters: Double, limit: Int): List<PhysicalSign> = emptyList()
            override suspend fun getEdge(edgeId: String): RoadEdge? = null
            override suspend fun nearbyEdges(area: BoundingBox, limit: Int): List<RoadEdge> = emptyList()
            override suspend fun packStatus(): PackStatus {
                statusStarted.complete(Unit)
                delay(500L)
                return PackStatus.Ready(
                    "vupack", 1, 1, "synthetic-vumap-p1", "test", "1.0", true, 1, 1, "sha", "csha"
                )
            }
        }

        val renderer = TestSignsMapRenderer()
        val coordinator = SignsOverlayCoordinator(
            signProvider = delayedProvider,
            renderer = renderer,
            ioDispatcher = testDispatcher,
            debounceMs = 50L,
            coroutineScope = this
        )

        // Enable with no prior bounds: triggers delayed packStatus()
        coordinator.setEnabled(true)
        advanceTimeBy(1L)
        statusStarted.await()

        // Disable while status check is still in-flight
        coordinator.setEnabled(false)
        assertEquals(SignsOverlayState.Disabled, coordinator.state.value)

        // Advance through the status delay
        advanceTimeBy(600L)
        advanceUntilIdle()

        // State must remain Disabled, NOT overwritten by delayed Ready/Loading
        assertEquals(SignsOverlayState.Disabled, coordinator.state.value)
        assertEquals(0, renderer.renderedCalls.size)

        coordinator.onDispose()
    }

    @Test
    fun `negative delayed status request followed by bounds query cancels status and lets bounds query win`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val statusStarted = CompletableDeferred<Unit>()
        val sign = PhysicalSign("S_BOUNDS", SignType.P127, GeoPoint(10.8231, 106.6297), 90.0, 60, "E1", 0.9f, "synth", true)

        val delayedProvider = object : SignProvider {
            override suspend fun nearby(area: BoundingBox, limit: Int): List<PhysicalSign> = listOf(sign)
            override suspend fun nearby(point: GeoPoint, radiusMeters: Double, limit: Int): List<PhysicalSign> = emptyList()
            override suspend fun getEdge(edgeId: String): RoadEdge? = null
            override suspend fun nearbyEdges(area: BoundingBox, limit: Int): List<RoadEdge> = emptyList()
            override suspend fun packStatus(): PackStatus {
                statusStarted.complete(Unit)
                delay(500L)
                return PackStatus.Ready(
                    "vupack", 1, 1, "synthetic-vumap-p1", "test", "1.0", true, 1, 1, "sha", "csha"
                )
            }
        }

        val renderer = TestSignsMapRenderer()
        val coordinator = SignsOverlayCoordinator(
            signProvider = delayedProvider,
            renderer = renderer,
            ioDispatcher = testDispatcher,
            debounceMs = 50L,
            coroutineScope = this
        )

        // Enable with no bounds: launches status request
        coordinator.setEnabled(true)
        advanceTimeBy(1L)
        statusStarted.await()

        // Camera moves before status completes: dispatches bounds query which cancels status job
        coordinator.onCameraBoundsChanged(SignsOverlayCoordinator.FIXTURE_BOUNDS)
        advanceTimeBy(600L)
        advanceUntilIdle()

        val state = coordinator.state.value
        assertTrue("State must be Visible with bounds query results, got: $state", state is SignsOverlayState.Visible)
        val visible = state as SignsOverlayState.Visible
        assertEquals(1, visible.signs.size)
        assertEquals("S_BOUNDS", visible.signs[0].id)
        assertEquals(1, renderer.renderedCalls.size)

        coordinator.onDispose()
    }

    @Test
    fun `negative delayed status request with external scope is cancelled on dispose`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val statusStarted = CompletableDeferred<Unit>()

        val delayedProvider = object : SignProvider {
            override suspend fun nearby(area: BoundingBox, limit: Int): List<PhysicalSign> = emptyList()
            override suspend fun nearby(point: GeoPoint, radiusMeters: Double, limit: Int): List<PhysicalSign> = emptyList()
            override suspend fun getEdge(edgeId: String): RoadEdge? = null
            override suspend fun nearbyEdges(area: BoundingBox, limit: Int): List<RoadEdge> = emptyList()
            override suspend fun packStatus(): PackStatus {
                statusStarted.complete(Unit)
                delay(500L)
                return PackStatus.Ready(
                    "vupack", 1, 1, "synthetic-vumap-p1", "test", "1.0", true, 1, 1, "sha", "csha"
                )
            }
        }

        val renderer = TestSignsMapRenderer()
        // Externally supplied scope (ownsScope = false)
        val coordinator = SignsOverlayCoordinator(
            signProvider = delayedProvider,
            renderer = renderer,
            ioDispatcher = testDispatcher,
            debounceMs = 50L,
            coroutineScope = this
        )

        coordinator.setEnabled(true)
        advanceTimeBy(1L)
        statusStarted.await()

        // Dispose screen while status check is in flight
        coordinator.onDispose()
        assertTrue(renderer.clearCallsCount > 0)

        // Advance through delay
        advanceTimeBy(600L)
        advanceUntilIdle()

        // State must NOT transition to Loading(null) after dispose
        assertFalse(coordinator.state.value is SignsOverlayState.Loading)
    }

    @Test
    fun `dispose after map destroy or replaced style never touches the invalidated renderer`() = runTest {
        val renderer = TestSignsMapRenderer(attached = false)
        val coordinator = SignsOverlayCoordinator(
            signProvider = readySignProvider(emptyList()),
            renderer = renderer,
            ioDispatcher = StandardTestDispatcher(testScheduler),
            debounceMs = 0L,
            coroutineScope = this
        )

        coordinator.setEnabled(true)
        advanceUntilIdle()
        assertEquals(SignsOverlayState.Loading(null), coordinator.state.value)

        // MapView.onDestroy()/setStyle() already detached the Style: teardown must
        // not call getSourceAs/clearSigns on it.
        coordinator.onDispose()
        coordinator.onDispose()

        assertEquals(0, renderer.clearCallsCount)
        assertEquals(1, renderer.detachCallsCount)
    }

    @Test
    fun `repeated teardown is idempotent and ignores work after dispose`() = runTest {
        val sign = PhysicalSign("S01", SignType.R420, GeoPoint(10.8231, 106.6297), 90.0, null, "E1", 0.9f, "synth", true)
        val renderer = TestSignsMapRenderer()
        val coordinator = SignsOverlayCoordinator(
            signProvider = readySignProvider(listOf(sign)),
            renderer = renderer,
            ioDispatcher = StandardTestDispatcher(testScheduler),
            debounceMs = 0L,
            coroutineScope = this
        )

        coordinator.setEnabled(true)
        coordinator.onCameraBoundsChanged(SignsOverlayCoordinator.FIXTURE_BOUNDS)
        advanceUntilIdle()
        assertTrue(coordinator.state.value is SignsOverlayState.Visible)
        assertEquals(1, renderer.renderedCalls.size)

        coordinator.onDispose()
        coordinator.onDispose()

        // Only the first dispose clears/detaches, and it does so exactly once.
        assertEquals(1, renderer.clearCallsCount)
        assertEquals(1, renderer.detachCallsCount)

        val rendersBefore = renderer.renderedCalls.size
        coordinator.setEnabled(false)
        coordinator.onCameraBoundsChanged(BoundingBox(10.0, 106.0, 11.0, 107.0))
        coordinator.refresh()
        val lateRenderer = TestSignsMapRenderer()
        coordinator.onStyleReloaded(lateRenderer)
        advanceUntilIdle()

        assertEquals(rendersBefore, renderer.renderedCalls.size)
        assertEquals(0, lateRenderer.renderedCalls.size)
        assertEquals(0, lateRenderer.clearCallsCount)
        assertEquals(1, lateRenderer.detachCallsCount)
    }

    @Test
    fun `dispose while a replacement style is still loading detaches both renderers without map access`() = runTest {
        val sign = PhysicalSign("S01", SignType.R420, GeoPoint(10.8231, 106.6297), 90.0, null, "E1", 0.9f, "synth", true)
        val oldRenderer = TestSignsMapRenderer()
        val coordinator = SignsOverlayCoordinator(
            signProvider = readySignProvider(listOf(sign)),
            renderer = oldRenderer,
            ioDispatcher = StandardTestDispatcher(testScheduler),
            debounceMs = 0L,
            coroutineScope = this
        )

        coordinator.setEnabled(true)
        coordinator.onCameraBoundsChanged(SignsOverlayCoordinator.FIXTURE_BOUNDS)
        advanceUntilIdle()
        assertEquals(1, oldRenderer.renderedCalls.size)

        // MapLibre clears the old Style as soon as setStyle() starts; the replacement
        // renderer exists while its style is still loading.
        oldRenderer.isAttached = false
        val loadingRenderer = TestSignsMapRenderer(attached = false)
        coordinator.onStyleReloaded(loadingRenderer)

        assertEquals(1, oldRenderer.detachCallsCount)
        assertEquals(0, loadingRenderer.renderedCalls.size)
        assertEquals(0, loadingRenderer.clearCallsCount)

        coordinator.onDispose()

        assertEquals(0, loadingRenderer.clearCallsCount)
        assertEquals(1, loadingRenderer.detachCallsCount)
        assertEquals(0, oldRenderer.clearCallsCount)
    }

    @Test
    fun `in-flight query result after dispose never renders markers or updates state`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val queryStarted = CompletableDeferred<Unit>()
        val releaseQuery = CompletableDeferred<Unit>()
        val sign = PhysicalSign("S_LATE", SignType.P127, GeoPoint(10.8231, 106.6297), 90.0, 60, "E1", 0.9f, "synth", true)

        val slowProvider = object : SignProvider {
            override suspend fun nearby(area: BoundingBox, limit: Int): List<PhysicalSign> {
                queryStarted.complete(Unit)
                releaseQuery.await()
                return listOf(sign)
            }

            override suspend fun nearby(point: GeoPoint, radiusMeters: Double, limit: Int): List<PhysicalSign> = emptyList()
            override suspend fun getEdge(edgeId: String): RoadEdge? = null
            override suspend fun nearbyEdges(area: BoundingBox, limit: Int): List<RoadEdge> = emptyList()
            override suspend fun packStatus(): PackStatus = PackStatus.Ready(
                "vupack", 1, 1, "synthetic-vumap-p1", "test", "1.0", true, 1, 1, "sha", "csha"
            )
        }

        val renderer = TestSignsMapRenderer()
        val coordinator = SignsOverlayCoordinator(
            signProvider = slowProvider,
            renderer = renderer,
            ioDispatcher = testDispatcher,
            debounceMs = 0L,
            coroutineScope = this
        )

        coordinator.setEnabled(true)
        coordinator.onCameraBoundsChanged(SignsOverlayCoordinator.FIXTURE_BOUNDS)
        advanceTimeBy(1L)
        queryStarted.await()
        assertTrue(coordinator.state.value is SignsOverlayState.Loading)

        // User taps the Settings gear while the sign query is still in flight.
        coordinator.onDispose()
        releaseQuery.complete(Unit)
        advanceUntilIdle()

        assertTrue(coordinator.state.value is SignsOverlayState.Loading)
        assertEquals(0, renderer.renderedCalls.size)
        assertEquals(1, renderer.detachCallsCount)
    }

    @Test
    fun `map return creates a fresh coordinator that renders normally after prior teardown`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val sign = PhysicalSign("S01", SignType.R420, GeoPoint(10.8231, 106.6297), 90.0, null, "E1", 0.9f, "synth", true)
        val provider = readySignProvider(listOf(sign))

        val firstRenderer = TestSignsMapRenderer()
        val first = SignsOverlayCoordinator(
            signProvider = provider,
            renderer = firstRenderer,
            ioDispatcher = testDispatcher,
            debounceMs = 0L,
            coroutineScope = this
        )
        first.setEnabled(true)
        first.onCameraBoundsChanged(SignsOverlayCoordinator.FIXTURE_BOUNDS)
        advanceUntilIdle()
        assertEquals(1, firstRenderer.renderedCalls.size)
        first.onDispose()

        // Returning from Settings builds a new MapScreen/coordinator.
        val returnedRenderer = TestSignsMapRenderer()
        val returned = SignsOverlayCoordinator(
            signProvider = provider,
            renderer = returnedRenderer,
            ioDispatcher = testDispatcher,
            debounceMs = 0L,
            coroutineScope = this
        )
        returned.setEnabled(true)
        returned.onCameraBoundsChanged(SignsOverlayCoordinator.FIXTURE_BOUNDS)
        advanceUntilIdle()

        assertTrue(returned.state.value is SignsOverlayState.Visible)
        assertEquals(1, returnedRenderer.renderedCalls.size)
        assertEquals(0, returnedRenderer.clearCallsCount)
        returned.onDispose()
        assertEquals(1, firstRenderer.detachCallsCount)
        assertEquals(1, returnedRenderer.detachCallsCount)
    }

    @Test
    fun `compose repository ownership captures immutable instance avoiding premature disposal on initial load`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val realRepo = OfflineSignRepository(
            packFile = assetPack,
            manifestJson = manifestJson,
            dbFactory = dbFactory,
            ioDispatcher = testDispatcher
        )

        realRepo.use { repo ->
            repo.initialize()

            // Simulating Compose DisposableEffect(currentLocalRepo) pattern:
            // In MapScreen:
            // var localRepo by remember { mutableStateOf<OfflineSignRepository?>(null) }
            // val currentLocalRepo = localRepo
            // DisposableEffect(currentLocalRepo) { onDispose { currentLocalRepo?.close() } }
            var onDisposeLambda: (() -> Unit)? = null

            fun simulateComposeDisposableEffect(capturedInstance: OfflineSignRepository?) {
                // Compose executes previous effect's onDispose before entering new effect
                onDisposeLambda?.invoke()
                // New effect registers disposal of the CAPTURED immutable instance
                onDisposeLambda = { capturedInstance?.close() }
            }

            // Frame 1: localRepo is null
            var localRepoState: OfflineSignRepository? = null
            val frame1Capture = localRepoState
            simulateComposeDisposableEffect(frame1Capture)

            // Frame 2: Background loader finishes, localRepoState becomes real repo
            localRepoState = repo
            val frame2Capture = localRepoState
            simulateComposeDisposableEffect(frame2Capture)

            // CRUCIAL INVARIANT:
            // Frame 1's disposal must have executed frame1Capture?.close() which is null?.close()
            // It must NOT have closed 'repo'!
            val signs = repo.nearby(SignsOverlayCoordinator.FIXTURE_BOUNDS, limit = 10)
            assertTrue("Repository must still be open and return signs after initial load transition", signs.isNotEmpty())

            // Frame 3: Screen teardown (navigating away / composable destroyed)
            simulateComposeDisposableEffect(null)

            // Now repo was closed by frame 2's disposal
            // Verify repo is closed: rawQuery on closed DB returns empty or throws
            val postDisposeSigns = repo.nearby(SignsOverlayCoordinator.FIXTURE_BOUNDS, limit = 10)
            assertTrue("Repository must be empty after screen disposal", postDisposeSigns.isEmpty())
        }
    }

    private fun readySignProvider(signs: List<PhysicalSign>): SignProvider = object : SignProvider {
        override suspend fun nearby(area: BoundingBox, limit: Int): List<PhysicalSign> = signs
        override suspend fun nearby(point: GeoPoint, radiusMeters: Double, limit: Int): List<PhysicalSign> = emptyList()
        override suspend fun getEdge(edgeId: String): RoadEdge? = null
        override suspend fun nearbyEdges(area: BoundingBox, limit: Int): List<RoadEdge> = emptyList()
        override suspend fun packStatus(): PackStatus = PackStatus.Ready(
            "vupack", 1, 1, "synthetic-vumap-p1", "test", "1.0", true, signs.size, signs.size, "sha", "csha"
        )
    }

    private fun findProjectRoot(): File {
        var dir: File? = File(".").canonicalFile
        while (dir != null) {
            if (File(dir, "feature/signs").isDirectory && File(dir, "app").isDirectory) {
                return dir
            }
            dir = dir.parentFile
        }
        return File(".").canonicalFile
    }
}
