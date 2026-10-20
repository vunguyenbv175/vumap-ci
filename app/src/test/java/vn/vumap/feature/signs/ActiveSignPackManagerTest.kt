package vn.vumap.feature.signs

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import vn.vumap.core.model.BoundingBox
import vn.vumap.core.model.PhysicalSign
import vn.vumap.feature.signs.db.JdbcSignPackDbFactory
import vn.vumap.feature.signs.db.SignPackDb
import vn.vumap.feature.signs.db.SignPackDbFactory
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.RandomAccessFile

class ActiveSignPackManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var baseDir: File
    private val dbFactory = JdbcSignPackDbFactory()
    private lateinit var storageManager: OfflinePackStorageManager

    private lateinit var packAFile: File
    private lateinit var manifestAFile: File
    private lateinit var packASha: String

    private lateinit var packBFile: File
    private lateinit var manifestBFile: File
    private lateinit var packBSha: String

    private lateinit var bundledPackFile: File
    private lateinit var bundledManifestFile: File

    private class TestSignsMapRenderer : SignsMapRenderer {
        override var isAttached: Boolean = true
        val renderedBatches = mutableListOf<List<PhysicalSign>>()
        var clearCount = 0

        override fun renderSigns(signs: List<PhysicalSign>) {
            renderedBatches.add(signs)
        }

        override fun clearSigns() {
            clearCount++
        }
    }

    /**
     * DB factory wrapper that records every opened SQLite handle so tests can assert
     * handle closure (no leaks, no double closes) and can pause inside a specific open.
     */
    private class RecordingDbFactory(
        private val delegate: SignPackDbFactory,
        private val pauseOnOpen: ((File) -> Unit)? = null
    ) : SignPackDbFactory {

        class CountingHandle(val path: String, private val delegate: SignPackDb) : SignPackDb by delegate {
            val closeCount = java.util.concurrent.atomic.AtomicInteger(0)

            override fun close() {
                closeCount.incrementAndGet()
                delegate.close()
            }
        }

        val openedHandles = java.util.concurrent.CopyOnWriteArrayList<CountingHandle>()

        override fun openReadOnly(databaseFile: File): SignPackDb {
            val real = delegate.openReadOnly(databaseFile)
            val handle = CountingHandle(databaseFile.absolutePath, real)
            openedHandles.add(handle)
            pauseOnOpen?.invoke(databaseFile)
            return handle
        }
    }

    @Before
    fun setUp() {
        baseDir = tempFolder.newFolder("signs_manager_test")
        storageManager = OfflinePackStorageManager(baseDir, dbFactory, syntheticOnly = true)

        // 1. Create Bundled Pack (1 sign: BUNDLE_01)
        val bundleDir = tempFolder.newFolder("bundle_src")
        bundledPackFile = File(bundleDir, "bundled.vupack")
        bundledManifestFile = File(bundleDir, "bundled.vupack.manifest.json")
        TestPackBuilder.buildPack(
            packFile = bundledPackFile,
            manifestFile = bundledManifestFile,
            version = "bundled-v1",
            source = "bundled-source",
            edges = listOf(TestEdge(edgeId = "E_BUNDLE")),
            points = listOf(
                TestPoint(pointId = "BUNDLE_01", type = "R420", lat = 10.823, lon = 106.630, edgeId = "E_BUNDLE")
            )
        )

        // 2. Create DISTINCT Pack A (1 sign: A_01 at 10.825, 106.635)
        val dirA = tempFolder.newFolder("pack_a_src")
        packAFile = File(dirA, "packA.vupack")
        manifestAFile = File(dirA, "packA.vupack.manifest.json")
        TestPackBuilder.buildPack(
            packFile = packAFile,
            manifestFile = manifestAFile,
            version = "PackA-v1",
            source = "source-A",
            edges = listOf(TestEdge(edgeId = "E_A")),
            points = listOf(
                TestPoint(pointId = "A_01", type = "R420", lat = 10.825, lon = 106.635, edgeId = "E_A")
            )
        )
        packASha = TestPackBuilder.computeSha256(packAFile)

        // 3. Create DISTINCT Pack B (2 signs: B_01 and B_02 at 10.828, 106.640)
        val dirB = tempFolder.newFolder("pack_b_src")
        packBFile = File(dirB, "packB.vupack")
        manifestBFile = File(dirB, "packB.vupack.manifest.json")
        TestPackBuilder.buildPack(
            packFile = packBFile,
            manifestFile = manifestBFile,
            version = "PackB-v2",
            source = "source-B",
            edges = listOf(TestEdge(edgeId = "E_B")),
            points = listOf(
                TestPoint(pointId = "B_01", type = "P127", lat = 10.828, lon = 106.640, valueKmh = 50, edgeId = "E_B"),
                TestPoint(pointId = "B_02", type = "R421", lat = 10.829, lon = 106.641, edgeId = "E_B")
            )
        )
        packBSha = TestPackBuilder.computeSha256(packBFile)

        // Verify Pack A and Pack B are strictly distinct
        assertFalse(packASha.equals(packBSha, ignoreCase = true))
    }

    private fun createManager(): ActiveSignPackManager {
        return ActiveSignPackManager(
            storageManager = storageManager,
            dbFactory = dbFactory,
            bundledPackProvider = {
                val manifestText = bundledManifestFile.readText(Charsets.UTF_8)
                val repo = OfflineSignRepository(
                    packFile = bundledPackFile,
                    manifestJson = manifestText,
                    dbFactory = dbFactory,
                    ioDispatcher = Dispatchers.Unconfined,
                    syntheticOnly = true
                )
                repo.initialize()
                repo
            },
            ioDispatcher = Dispatchers.Unconfined,
            syntheticOnly = true
        )
    }

    @Test
    fun `initializes to bundled pack when no imported generation exists`() = runBlocking {
        val manager = createManager()
        val status = manager.initialize()

        assertTrue(status is PackStatus.Ready)
        assertEquals("bundled-v1", (status as PackStatus.Ready).version)

        val state = manager.activePackState.value
        assertTrue(state is ActivePackState.Bundled)

        val signs = manager.nearby(BoundingBox(10.8, 106.6, 10.9, 106.7), limit = 10)
        assertEquals(1, signs.size)
        assertEquals("BUNDLE_01", signs[0].id)
    }

    @Test
    fun `importPackFromStreams with DISTINCT valid Pack A then Pack B`() = runBlocking {
        val manager = createManager()
        manager.initialize()

        // 1. Import Pack A
        val resultA = manager.importPackFromStreams(
            packStreamProvider = { FileInputStream(packAFile) },
            manifestStreamProvider = { FileInputStream(manifestAFile) }
        )
        assertTrue("Import Pack A must succeed: $resultA", resultA is PackImportResult.Success)
        val successA = resultA as PackImportResult.Success
        assertEquals("PackA-v1", successA.metadata.version)
        assertEquals(1, successA.metadata.pointCount)

        // Verify active state is Pack A
        val stateA = manager.activePackState.value
        assertTrue(stateA is ActivePackState.Imported)
        val importedA = stateA as ActivePackState.Imported
        assertEquals("PackA-v1", importedA.pointer.version)
        assertEquals(packASha, importedA.pointer.sha256)

        // Verify query returns Pack A distinct signs
        val signsA = manager.nearby(BoundingBox(10.8, 106.6, 10.9, 106.7), limit = 10)
        assertEquals(1, signsA.size)
        assertEquals("A_01", signsA[0].id)

        // 2. Import Pack B
        val resultB = manager.importPackFromStreams(
            packStreamProvider = { FileInputStream(packBFile) },
            manifestStreamProvider = { FileInputStream(manifestBFile) }
        )
        assertTrue("Import Pack B must succeed: $resultB", resultB is PackImportResult.Success)
        val successB = resultB as PackImportResult.Success
        assertEquals("PackB-v2", successB.metadata.version)
        assertEquals(2, successB.metadata.pointCount)

        // Verify active state is now Pack B
        val stateB = manager.activePackState.value
        assertTrue(stateB is ActivePackState.Imported)
        val importedB = stateB as ActivePackState.Imported
        assertEquals("PackB-v2", importedB.pointer.version)
        assertEquals(packBSha, importedB.pointer.sha256)

        // Verify query now returns Pack B distinct signs and NOT Pack A
        val signsB = manager.nearby(BoundingBox(10.8, 106.6, 10.9, 106.7), limit = 10)
        assertEquals(2, signsB.size)
        assertTrue(signsB.any { it.id == "B_01" })
        assertTrue(signsB.any { it.id == "B_02" })
        assertFalse(signsB.any { it.id == "A_01" })
    }

    @Test
    fun `distinct old new pairs fault injection proving rollback detects missing restore`() = runBlocking {
        val manager = createManager()
        manager.initialize()

        // 1. Establish Pack A as the active generation
        val resultA = manager.importPackFromStreams(
            packStreamProvider = { FileInputStream(packAFile) },
            manifestStreamProvider = { FileInputStream(manifestAFile) }
        )
        assertTrue(resultA is PackImportResult.Success)
        val activePointerA = storageManager.getActivePointer()
        assertNotNull(activePointerA)
        val installedPackA = storageManager.resolvePackFile(activePointerA!!)
        assertEquals(packASha, TestPackBuilder.computeSha256(installedPackA))

        // 2. Prepare corrupted Pack C (starts from Pack B, but with flipped byte in pack)
        val dirC = tempFolder.newFolder("pack_c_corrupt")
        val packCFile = File(dirC, "packC.vupack")
        val manifestCFile = File(dirC, "packC.vupack.manifest.json")
        packBFile.copyTo(packCFile)
        manifestBFile.copyTo(manifestCFile)

        // Flip byte at offset 400 in packCFile without updating manifest sha
        RandomAccessFile(packCFile, "rw").use { raf ->
            raf.seek(400)
            val b = raf.readByte()
            raf.seek(400)
            raf.writeByte(b.toInt() xor 0xFF)
        }

        // 3. Attempt to import corrupted Pack C
        val resultC = manager.importPackFromStreams(
            packStreamProvider = { FileInputStream(packCFile) },
            manifestStreamProvider = { FileInputStream(manifestCFile) }
        )

        // Must be rejected
        assertTrue("Corrupted pack must be rejected", resultC is PackImportResult.Failure)
        val failure = resultC as PackImportResult.Failure
        assertEquals(PackImportResult.Failure.ErrorCode.VALIDATION_FAILED, failure.errorCode)
        assertTrue(failure.errors.contains("CHECKSUM_MISMATCH"))

        // 4. VERIFY ROLLBACK / PRESERVATION:
        // The active generation MUST still be Pack A, and its bytes MUST be 100% byte-identical
        val activePointerAfter = storageManager.getActivePointer()
        assertNotNull(activePointerAfter)
        assertEquals(activePointerA.generationId, activePointerAfter!!.generationId)
        assertEquals("PackA-v1", activePointerAfter.version)

        val packFileAfter = storageManager.resolvePackFile(activePointerAfter)
        assertTrue(packFileAfter.exists())
        assertEquals(packASha, TestPackBuilder.computeSha256(packFileAfter))

        // Queries must STILL return Pack A signs, proving rollback restored Pack A and not Pack C or empty!
        val signs = manager.nearby(BoundingBox(10.8, 106.6, 10.9, 106.7), limit = 10)
        assertEquals(1, signs.size)
        assertEquals("A_01", signs[0].id)
    }

    @Test
    fun `fault injection during commit phase preserves prior generation`() = runBlocking {
        val manager = createManager()
        manager.initialize()

        // 1. Pack A active
        manager.importPackFromStreams(
            packStreamProvider = { FileInputStream(packAFile) },
            manifestStreamProvider = { FileInputStream(manifestAFile) }
        )
        val pointerBefore = storageManager.getActivePointer()!!

        // 2. Inject fault before pointer commit
        storageManager.preCommitFaultInjector = {
            throw IOException("Simulated disk write failure")
        }

        // 3. Attempt import Pack B
        val result = manager.importPackFromStreams(
            packStreamProvider = { FileInputStream(packBFile) },
            manifestStreamProvider = { FileInputStream(manifestBFile) }
        )

        assertTrue(result is PackImportResult.Failure)
        assertEquals(PackImportResult.Failure.ErrorCode.COMMIT_FAILED, (result as PackImportResult.Failure).errorCode)

        // 4. Verify Pack A pointer and files are completely unchanged
        val pointerAfter = storageManager.getActivePointer()!!
        assertEquals(pointerBefore.generationId, pointerAfter.generationId)
        assertEquals("PackA-v1", pointerAfter.version)

        val signs = manager.nearby(BoundingBox(10.8, 106.6, 10.9, 106.7), limit = 10)
        assertEquals(1, signs.size)
        assertEquals("A_01", signs[0].id)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `production reader integration SignsOverlayCoordinator picks up new generation`() = runTest {
        val manager = createManager()
        manager.initialize()

        // Pack A active
        manager.importPackFromStreams(
            packStreamProvider = { FileInputStream(packAFile) },
            manifestStreamProvider = { FileInputStream(manifestAFile) }
        )

        val renderer = TestSignsMapRenderer()
        val coordinator = SignsOverlayCoordinator(
            signProvider = manager,
            renderer = renderer,
            ioDispatcher = Dispatchers.Unconfined,
            debounceMs = 0L,
            coroutineScope = this
        )

        // Enable coordinator
        coordinator.setEnabled(true)
        val queryBounds = BoundingBox(10.8, 106.6, 10.9, 106.7)
        coordinator.onCameraBoundsChanged(queryBounds)
        advanceUntilIdle()

        // Initial render received Pack A
        assertEquals(1, renderer.renderedBatches.size)
        assertEquals(1, renderer.renderedBatches[0].size)
        assertEquals("A_01", renderer.renderedBatches[0][0].id)

        // Now import Pack B
        val importResult = manager.importPackFromStreams(
            packStreamProvider = { FileInputStream(packBFile) },
            manifestStreamProvider = { FileInputStream(manifestBFile) }
        )
        assertTrue(importResult is PackImportResult.Success)

        // Coordinator refreshes
        coordinator.refresh()
        advanceUntilIdle()

        // Render received Pack B
        assertTrue(renderer.renderedBatches.size >= 2)
        val latestBatch = renderer.renderedBatches.last()
        assertEquals(2, latestBatch.size)
        assertTrue(latestBatch.any { it.id == "B_01" })
        assertTrue(latestBatch.any { it.id == "B_02" })

        coordinator.onDispose()
    }

    @Test
    fun `resetToBundled restores bundled pack cleanly`() = runBlocking {
        val manager = createManager()
        manager.initialize()

        // Import Pack B
        manager.importPackFromStreams(
            packStreamProvider = { FileInputStream(packBFile) },
            manifestStreamProvider = { FileInputStream(manifestBFile) }
        )
        assertTrue(manager.activePackState.value is ActivePackState.Imported)

        // Reset to bundled
        val resetOk = manager.resetToBundled()
        assertTrue(resetOk)

        assertTrue(manager.activePackState.value is ActivePackState.Bundled)
        assertNull(storageManager.getActivePointer())
        // Genuine success control: a successful reset reports no failure outcome
        assertNull(manager.lastImportResult.value)

        val signs = manager.nearby(BoundingBox(10.8, 106.6, 10.9, 106.7), limit = 10)
        assertEquals(1, signs.size)
        assertEquals("BUNDLE_01", signs[0].id)
    }

    @Test
    fun `crash consistency interrupted staging directory ignored on startup`() = runBlocking {
        // Initial import Pack A
        val manager1 = createManager()
        manager1.initialize()
        manager1.importPackFromStreams(
            packStreamProvider = { FileInputStream(packAFile) },
            manifestStreamProvider = { FileInputStream(manifestAFile) }
        )
        manager1.close()

        // Simulate crash leaving leftover partial staging files
        val crashDir = File(storageManager.stagingDir, "stage_crashed_run_456")
        crashDir.mkdirs()
        File(crashDir, "pack.vupack").writeText("incomplete garbage bytes")

        // New manager starts up
        val manager2 = createManager()
        val status = manager2.initialize()

        assertTrue(status is PackStatus.Ready)
        assertEquals("PackA-v1", (status as PackStatus.Ready).version)

        // Leftover staging folder was cleaned up
        assertFalse(crashDir.exists())

        // Query succeeds on Pack A
        val signs = manager2.nearby(BoundingBox(10.8, 106.6, 10.9, 106.7), limit = 10)
        assertEquals(1, signs.size)
        assertEquals("A_01", signs[0].id)
        manager2.close()
    }

    @Test
    fun `fault injection after pointer publication restores prior pointer and cleans unactivated generation`() = runBlocking {
        val manager = createManager()
        manager.initialize()

        // 1. Pack A active
        manager.importPackFromStreams(
            packStreamProvider = { FileInputStream(packAFile) },
            manifestStreamProvider = { FileInputStream(manifestAFile) }
        )
        val pointerA = storageManager.getActivePointer()!!
        assertEquals("PackA-v1", pointerA.version)

        // 2. Inject fault right after pointer publication
        storageManager.postCommitFaultInjector = {
            throw IOException("Simulated fault immediately after pointer write")
        }

        // 3. Attempt import Pack B
        val result = manager.importPackFromStreams(
            packStreamProvider = { FileInputStream(packBFile) },
            manifestStreamProvider = { FileInputStream(manifestBFile) }
        )

        assertTrue("Import must fail on fault injection", result is PackImportResult.Failure)
        assertEquals(PackImportResult.Failure.ErrorCode.COMMIT_FAILED, (result as PackImportResult.Failure).errorCode)

        // 4. Pointer must be restored to Pack A, NOT left pointing to B!
        val pointerAfter = storageManager.getActivePointer()!!
        assertEquals(pointerA.generationId, pointerAfter.generationId)
        assertEquals("PackA-v1", pointerAfter.version)

        // Queries continue returning Pack A
        val signs = manager.nearby(BoundingBox(10.8, 106.6, 10.9, 106.7), limit = 10)
        assertEquals(1, signs.size)
        assertEquals("A_01", signs[0].id)

        // Reset fault injector
        storageManager.postCommitFaultInjector = null

        // Restarting a fresh manager instance must cleanly load Pack A
        val freshManager = createManager()
        val status = freshManager.initialize()
        assertTrue(status is PackStatus.Ready)
        assertEquals("PackA-v1", (status as PackStatus.Ready).version)
        val freshSigns = freshManager.nearby(BoundingBox(10.8, 106.6, 10.9, 106.7), limit = 10)
        assertEquals(1, freshSigns.size)
        assertEquals("A_01", freshSigns[0].id)
        freshManager.close()
        manager.close()
    }

    @Test
    fun `lifecycle serialization prevents races between overlapping imports`() = runBlocking(Dispatchers.Default) {
        val manager = createManager()
        manager.initialize()

        // Launch two concurrent imports simultaneously on different threads
        val jobA = async {
            manager.importPackFromStreams(
                packStreamProvider = { FileInputStream(packAFile) },
                manifestStreamProvider = { FileInputStream(manifestAFile) }
            )
        }
        val jobB = async {
            manager.importPackFromStreams(
                packStreamProvider = { FileInputStream(packBFile) },
                manifestStreamProvider = { FileInputStream(manifestBFile) }
            )
        }

        val rA = jobA.await()
        val rB = jobB.await()

        // Both imports must complete safely because of lifecycle serialization (no staging directory corruption)
        assertTrue(rA is PackImportResult.Success)
        assertTrue(rB is PackImportResult.Success)

        // Final state is a valid committed pack
        val activePointer = storageManager.getActivePointer()
        assertNotNull(activePointer)
        assertTrue(activePointer!!.version in listOf("PackA-v1", "PackB-v2"))

        val status = manager.packStatus()
        assertTrue(status is PackStatus.Ready)

        manager.close()
    }

    @Test
    fun `in-flight query holds lease and completes safely across generation swap`() = runBlocking(Dispatchers.Default) {
        val insideCursorLatch = java.util.concurrent.CountDownLatch(1)
        val swapCommittedLatch = java.util.concurrent.CountDownLatch(1)
        var shouldPauseCursor = false

        // Custom factory that wraps cursor to pause inside moveToNext
        val interceptingFactory = object : vn.vumap.feature.signs.db.SignPackDbFactory {
            private val delegate = JdbcSignPackDbFactory()
            override fun openReadOnly(file: File): vn.vumap.feature.signs.db.SignPackDb {
                val realDb = delegate.openReadOnly(file)
                return object : vn.vumap.feature.signs.db.SignPackDb by realDb {
                    override fun rawQuery(sql: String, args: Array<String>): vn.vumap.feature.signs.db.SignPackCursor {
                        val realCursor = realDb.rawQuery(sql, args)
                        return object : vn.vumap.feature.signs.db.SignPackCursor by realCursor {
                            override fun moveToNext(): Boolean {
                                if (shouldPauseCursor) {
                                    insideCursorLatch.countDown()
                                    // Pause inside cursor iteration while generation swap takes place
                                    swapCommittedLatch.await(5, java.util.concurrent.TimeUnit.SECONDS)
                                }
                                return realCursor.moveToNext()
                            }
                        }
                    }
                }
            }
        }

        val manager = ActiveSignPackManager(
            storageManager = storageManager,
            dbFactory = interceptingFactory,
            bundledPackProvider = {
                val manifestText = bundledManifestFile.readText(Charsets.UTF_8)
                val repo = OfflineSignRepository(
                    packFile = bundledPackFile,
                    manifestJson = manifestText,
                    dbFactory = interceptingFactory,
                    ioDispatcher = Dispatchers.Unconfined,
                    syntheticOnly = true
                )
                repo.initialize()
                repo
            },
            ioDispatcher = Dispatchers.Unconfined,
            syntheticOnly = true
        )
        manager.initialize()

        // Establish Pack A as active
        manager.importPackFromStreams(
            packStreamProvider = { FileInputStream(packAFile) },
            manifestStreamProvider = { FileInputStream(manifestAFile) }
        )

        var querySigns: List<PhysicalSign>? = null

        // In-flight query starts reading Pack A, pauses INSIDE cursor.moveToNext()
        shouldPauseCursor = true
        val queryJob = launch {
            querySigns = manager.nearby(BoundingBox(10.8, 106.6, 10.9, 106.7), limit = 10)
        }

        // Wait until query is paused INSIDE SQLite cursor read
        insideCursorLatch.await(5, java.util.concurrent.TimeUnit.SECONDS)

        // Do not pause future cursor reads
        shouldPauseCursor = false

        // Import Pack B while Pack A query is paused mid-cursor
        val swapResult = manager.importPackFromStreams(
            packStreamProvider = { FileInputStream(packBFile) },
            manifestStreamProvider = { FileInputStream(manifestBFile) }
        )
        assertTrue(swapResult is PackImportResult.Success)

        // Now resume the paused cursor iteration on Pack A
        swapCommittedLatch.countDown()
        queryJob.join()

        // In-flight query safely completed and returned Pack A signs without SQLite closed error
        assertNotNull(querySigns)
        assertEquals(1, querySigns!!.size)
        assertEquals("A_01", querySigns[0].id)

        // Subsequent queries immediately get Pack B signs
        val newSigns = manager.nearby(BoundingBox(10.8, 106.6, 10.9, 106.7), limit = 10)
        assertEquals(2, newSigns.size)
        assertTrue(newSigns.any { it.id == "B_01" })

        manager.close()
    }

    @Test
    fun `promotion failure during directory move cleans staging and returns COMMIT_FAILED without altering active pointer`() = runBlocking {
        val manager = createManager()
        manager.initialize()

        // 1. Establish Pack A as active
        manager.importPackFromStreams(
            packStreamProvider = { FileInputStream(packAFile) },
            manifestStreamProvider = { FileInputStream(manifestAFile) }
        )
        val pointerA = storageManager.getActivePointer()!!
        assertEquals("PackA-v1", pointerA.version)

        // 2. Inject fault during directory promotion
        storageManager.promoteFaultInjector = {
            throw IOException("Simulated disk promotion failure")
        }

        // 3. Attempt import Pack B
        val result = manager.importPackFromStreams(
            packStreamProvider = { FileInputStream(packBFile) },
            manifestStreamProvider = { FileInputStream(manifestBFile) }
        )

        // Must return COMMIT_FAILED and update lastImportResult
        assertTrue("Promotion failure must be caught and returned as Failure", result is PackImportResult.Failure)
        val failure = result as PackImportResult.Failure
        assertEquals(PackImportResult.Failure.ErrorCode.COMMIT_FAILED, failure.errorCode)
        assertEquals(failure, manager.lastImportResult.value)

        // Staging directory must be cleaned up
        val stagingDirs = storageManager.stagingDir.listFiles()
        assertTrue(stagingDirs.isNullOrEmpty())

        // Pointer must remain untouched at Pack A
        val pointerAfter = storageManager.getActivePointer()!!
        assertEquals(pointerA.generationId, pointerAfter.generationId)
        assertEquals("PackA-v1", pointerAfter.version)

        // Queries must continue to serve Pack A
        val signs = manager.nearby(BoundingBox(10.8, 106.6, 10.9, 106.7), limit = 10)
        assertEquals(1, signs.size)
        assertEquals("A_01", signs[0].id)

        storageManager.promoteFaultInjector = null
        manager.close()
    }

    @Test
    fun `close during database initialization or import cleanly terminates without leaving live repository`() = runBlocking(Dispatchers.Default) {
        val openStartedLatch = java.util.concurrent.CountDownLatch(1)
        val managerClosedLatch = java.util.concurrent.CountDownLatch(1)
        var shouldPauseOpen = false
        var openedDbRef: vn.vumap.feature.signs.db.SignPackDb? = null

        val pausingFactory = object : vn.vumap.feature.signs.db.SignPackDbFactory {
            private val delegate = JdbcSignPackDbFactory()
            override fun openReadOnly(file: File): vn.vumap.feature.signs.db.SignPackDb {
                val db = delegate.openReadOnly(file)
                if (shouldPauseOpen && file.name == "pack.vupack") {
                    openedDbRef = db
                    openStartedLatch.countDown()
                    managerClosedLatch.await(5, java.util.concurrent.TimeUnit.SECONDS)
                }
                return db
            }
        }

        val manager = ActiveSignPackManager(
            storageManager = storageManager,
            dbFactory = pausingFactory,
            bundledPackProvider = {
                val manifestText = bundledManifestFile.readText(Charsets.UTF_8)
                val repo = OfflineSignRepository(
                    packFile = bundledPackFile,
                    manifestJson = manifestText,
                    dbFactory = pausingFactory,
                    ioDispatcher = Dispatchers.Unconfined,
                    syntheticOnly = true
                )
                repo.initialize()
                repo
            },
            ioDispatcher = Dispatchers.Unconfined,
            syntheticOnly = true
        )

        // Initialize to bundled
        manager.initialize()

        // Pause inside candidate DB open during import
        shouldPauseOpen = true
        val importJob = launch {
            manager.importPackFromStreams(
                packStreamProvider = { FileInputStream(packAFile) },
                manifestStreamProvider = { FileInputStream(manifestAFile) }
            )
        }

        // Wait until import is paused inside DB factory
        openStartedLatch.await(5, java.util.concurrent.TimeUnit.SECONDS)

        // Close manager concurrently while import is in-flight
        manager.close()
        managerClosedLatch.countDown()

        val importResult = importJob.join()

        // The candidate DB opened during import must be closed
        assertNotNull(openedDbRef)
        assertFalse("Candidate DB must be closed when manager is closed during import", openedDbRef!!.isOpen())

        // Manager is permanently closed: queries return empty/Unavailable, no re-initialization
        val status = manager.packStatus()
        assertTrue(status is PackStatus.Unavailable)
        assertEquals("Quản lý gói đã bị đóng", (status as PackStatus.Unavailable).reason)

        val signs = manager.nearby(BoundingBox(10.8, 106.6, 10.9, 106.7), limit = 10)
        assertTrue(signs.isEmpty())
    }

    @Test
    fun `bundled provider failure during reset does not destroy active generation or pointer`() = runBlocking {
        var bundledShouldFail = false
        val manager = ActiveSignPackManager(
            storageManager = storageManager,
            dbFactory = dbFactory,
            bundledPackProvider = {
                if (bundledShouldFail) {
                    throw IOException("Simulated asset load failure")
                }
                val manifestText = bundledManifestFile.readText(Charsets.UTF_8)
                val repo = OfflineSignRepository(
                    packFile = bundledPackFile,
                    manifestJson = manifestText,
                    dbFactory = dbFactory,
                    ioDispatcher = Dispatchers.Unconfined,
                    syntheticOnly = true
                )
                repo.initialize()
                repo
            },
            ioDispatcher = Dispatchers.Unconfined,
            syntheticOnly = true
        )
        manager.initialize()

        // 1. Establish Pack A as active
        manager.importPackFromStreams(
            packStreamProvider = { FileInputStream(packAFile) },
            manifestStreamProvider = { FileInputStream(manifestAFile) }
        )
        val pointerA = storageManager.getActivePointer()!!
        assertEquals("PackA-v1", pointerA.version)

        // 2. Trigger bundled provider failure on reset
        bundledShouldFail = true
        val resetSuccess = manager.resetToBundled()

        // Reset must fail cleanly
        assertFalse("Reset must return false when bundled provider fails", resetSuccess)

        // 3. Active generation files and persistent pointer must NOT be destroyed!
        val pointerAfter = storageManager.getActivePointer()
        assertNotNull("Persistent pointer must still exist after failed reset", pointerAfter)
        assertEquals(pointerA.generationId, pointerAfter!!.generationId)
        assertEquals("PackA-v1", pointerAfter.version)

        val packFile = storageManager.resolvePackFile(pointerAfter)
        assertTrue("Active pack file must still exist after failed reset", packFile.exists())

        // Active manager queries continue serving Pack A
        val signs = manager.nearby(BoundingBox(10.8, 106.6, 10.9, 106.7), limit = 10)
        assertEquals(1, signs.size)
        assertEquals("A_01", signs[0].id)

        manager.close()
    }

    @Test
    fun `rollback restore pointer failure propagates and uses backup pointer for durability`() = runBlocking {
        val manager = createManager()
        manager.initialize()

        // 1. Establish Pack A as active
        manager.importPackFromStreams(
            packStreamProvider = { FileInputStream(packAFile) },
            manifestStreamProvider = { FileInputStream(manifestAFile) }
        )
        val pointerA = storageManager.getActivePointer()!!
        assertEquals("PackA-v1", pointerA.version)

        // 2. Fault injector after pointer publication throws on commit
        storageManager.postCommitFaultInjector = {
            throw IOException("Simulated fault after pointer write")
        }
        // And fault injector during rollback restore also fails
        storageManager.restorePointerFaultInjector = {
            throw IOException("Simulated disk failure during pointer restore")
        }

        // 3. Attempt import Pack B
        val result = manager.importPackFromStreams(
            packStreamProvider = { FileInputStream(packBFile) },
            manifestStreamProvider = { FileInputStream(manifestBFile) }
        )

        assertTrue(result is PackImportResult.Failure)
        val failure = result as PackImportResult.Failure
        assertEquals(PackImportResult.Failure.ErrorCode.COMMIT_FAILED, failure.errorCode)
        assertTrue(failure.errors.any { it.startsWith("ROLLBACK_RESTORE_FAILED") })

        storageManager.postCommitFaultInjector = null
        storageManager.restorePointerFaultInjector = null

        // 4. Even with primary pointer restoration failing, getActivePointer recovers from backup pointer!
        val recoveredPointer = storageManager.getActivePointer()
        assertNotNull("Storage manager must recover from backup pointer", recoveredPointer)
        assertEquals(pointerA.generationId, recoveredPointer!!.generationId)
        assertEquals("PackA-v1", recoveredPointer.version)

        // Fresh manager restarts cleanly on Pack A
        val freshManager = createManager()
        val status = freshManager.initialize()
        assertTrue(status is PackStatus.Ready)
        assertEquals("PackA-v1", (status as PackStatus.Ready).version)
        freshManager.close()
        manager.close()
    }

    @Test
    fun `bundled initialization exception during reset does not destroy active generation or pointer and returns false`() = runBlocking {
        var bundledShouldThrowDuringInit = false
        val manager = ActiveSignPackManager(
            storageManager = storageManager,
            dbFactory = dbFactory,
            bundledPackProvider = {
                val manifestText = if (bundledShouldThrowDuringInit) "{ invalid json" else bundledManifestFile.readText(Charsets.UTF_8)
                val repo = OfflineSignRepository(
                    packFile = bundledPackFile,
                    manifestJson = manifestText,
                    dbFactory = dbFactory,
                    ioDispatcher = Dispatchers.Unconfined,
                    syntheticOnly = true
                )
                repo
            },
            ioDispatcher = Dispatchers.Unconfined,
            syntheticOnly = true
        )
        manager.initialize()

        // 1. Establish Pack A as active
        manager.importPackFromStreams(
            packStreamProvider = { FileInputStream(packAFile) },
            manifestStreamProvider = { FileInputStream(manifestAFile) }
        )
        val pointerA = storageManager.getActivePointer()!!
        assertEquals("PackA-v1", pointerA.version)

        // 2. Trigger bundled initialize() failure on reset
        bundledShouldThrowDuringInit = true
        val resetSuccess = manager.resetToBundled()

        // Reset must return false cleanly without escaping exception
        assertFalse("Reset must return false when bundled initialize fails", resetSuccess)

        // 3. Active generation files and persistent pointer must NOT be destroyed!
        val pointerAfter = storageManager.getActivePointer()
        assertNotNull("Persistent pointer must still exist after failed reset", pointerAfter)
        assertEquals(pointerA.generationId, pointerAfter!!.generationId)
        assertEquals("PackA-v1", pointerAfter.version)

        val packFile = storageManager.resolvePackFile(pointerAfter)
        assertTrue("Active pack file must still exist after failed reset", packFile.exists())

        // Active manager queries continue serving Pack A
        val signs = manager.nearby(BoundingBox(10.8, 106.6, 10.9, 106.7), limit = 10)
        assertEquals(1, signs.size)
        assertEquals("A_01", signs[0].id)

        manager.close()
    }

    @Test
    fun `storage reset failure preserves old generation and active repository and returns false`() = runBlocking {
        val manager = createManager()
        manager.initialize()

        // 1. Establish Pack A as active
        manager.importPackFromStreams(
            packStreamProvider = { FileInputStream(packAFile) },
            manifestStreamProvider = { FileInputStream(manifestAFile) }
        )
        val pointerA = storageManager.getActivePointer()!!
        assertEquals("PackA-v1", pointerA.version)

        // 2. Inject fault during storage reset
        storageManager.resetFaultInjector = {
            throw IOException("Simulated disk error during storage reset")
        }

        // 3. Attempt resetToBundled
        val resetSuccess = manager.resetToBundled()
        assertFalse("resetToBundled must return false when storageManager fails", resetSuccess)

        storageManager.resetFaultInjector = null

        // 4. Pointer and files must remain untouched at Pack A
        val pointerAfter = storageManager.getActivePointer()
        assertNotNull(pointerAfter)
        assertEquals(pointerA.generationId, pointerAfter!!.generationId)
        assertEquals("PackA-v1", pointerAfter.version)

        // In-memory state remains Imported Pack A
        val state = manager.activePackState.value
        assertTrue("Active state must remain Imported when reset fails", state is ActivePackState.Imported)
        assertEquals("PackA-v1", (state as ActivePackState.Imported).pointer.version)

        // Queries continue to return Pack A signs
        val signs = manager.nearby(BoundingBox(10.8, 106.6, 10.9, 106.7), limit = 10)
        assertEquals(1, signs.size)
        assertEquals("A_01", signs[0].id)

        // Failure is reported with the dedicated typed rollback-restored code, not UNKNOWN
        val lastResult = manager.lastImportResult.value
        assertTrue(lastResult is PackImportResult.Failure)
        val failure = lastResult as PackImportResult.Failure
        assertEquals(PackImportResult.Failure.ErrorCode.RESET_FAILED_PRIOR_RESTORED, failure.errorCode)
        assertTrue(failure.errors.contains("RESET_FAILED_PRIOR_RESTORED"))

        manager.close()
    }

    @Test
    fun `reset failing after pointer deletion restores prior repository and state`() = runBlocking {
        val manager = createManager()
        manager.initialize()

        // 1. Establish Pack A as active
        manager.importPackFromStreams(
            packStreamProvider = { FileInputStream(packAFile) },
            manifestStreamProvider = { FileInputStream(manifestAFile) }
        )
        val pointerA = storageManager.getActivePointer()!!
        assertEquals("PackA-v1", pointerA.version)

        // 2. Inject fault AFTER pointer files are deleted but BEFORE generation directory deletion
        storageManager.resetAfterPointerFaultInjector = {
            throw IOException("Simulated disk failure after pointer deletion during reset")
        }

        // 3. Attempt resetToBundled
        val resetSuccess = manager.resetToBundled()
        assertFalse("resetToBundled must return false after partial storage reset failure", resetSuccess)

        storageManager.resetAfterPointerFaultInjector = null

        // 4. Prior pointer must be restored!
        val pointerAfter = storageManager.getActivePointer()
        assertNotNull("Prior pointer must be restored after partial reset failure", pointerAfter)
        assertEquals(pointerA.generationId, pointerAfter!!.generationId)
        assertEquals("PackA-v1", pointerAfter.version)

        // 5. Active in-memory state must be restored to Imported Pack A
        val state = manager.activePackState.value
        assertTrue("Active state must be restored to Imported when reset rollback succeeds", state is ActivePackState.Imported)
        assertEquals("PackA-v1", (state as ActivePackState.Imported).pointer.version)

        // 6. Queries continue serving Pack A signs via restored repository
        val signs = manager.nearby(BoundingBox(10.8, 106.6, 10.9, 106.7), limit = 10)
        assertEquals(1, signs.size)
        assertEquals("A_01", signs[0].id)

        // 7. packStatus() agrees with activePackState (Imported Pack A)
        val status = manager.packStatus()
        assertTrue(status is PackStatus.Ready)
        assertEquals("PackA-v1", (status as PackStatus.Ready).version)

        manager.close()
    }

    @Test
    fun `reset failing after pointer deletion and impossible rollback reports honest unavailable state`() = runBlocking {
        val manager = createManager()
        manager.initialize()

        // 1. Establish Pack A as active
        manager.importPackFromStreams(
            packStreamProvider = { FileInputStream(packAFile) },
            manifestStreamProvider = { FileInputStream(manifestAFile) }
        )
        val pointerA = storageManager.getActivePointer()!!
        assertEquals("PackA-v1", pointerA.version)

        // 2. Inject fault AFTER pointer deletion: also delete the manifest inside the injector
        // (priorPointer is captured before storage reset runs, so rollback is attempted but
        // the generation files are already gone, making restore impossible)
        val manifestFile = storageManager.resolveManifestFile(pointerA)
        storageManager.resetAfterPointerFaultInjector = {
            manifestFile.delete()
            throw IOException("Simulated disk failure after pointer deletion during reset")
        }

        // 3. Attempt resetToBundled
        val resetSuccess = manager.resetToBundled()
        assertFalse(resetSuccess)

        storageManager.resetAfterPointerFaultInjector = null

        // 4. State must NOT falsely claim Imported when rollback failed
        val state = manager.activePackState.value
        assertFalse("State must NOT claim Imported when rollback cannot restore generation", state is ActivePackState.Imported)

        // 5. P1: published state must match what is actually serving. Bundled candidate is live,
        // so state must be Bundled, and packStatus()/nearby() must agree with it.
        assertTrue("State must be Bundled (what actually serves) when rollback cannot restore", state is ActivePackState.Bundled)

        val status = manager.packStatus()
        assertTrue("packStatus() must agree with activePackState (Bundled)", status is PackStatus.Ready)
        assertEquals("bundled-source", (status as PackStatus.Ready).source)

        val signs = manager.nearby(BoundingBox(10.8, 106.6, 10.9, 106.7), limit = 10)
        assertEquals("nearby() must serve bundled signs matching state", 1, signs.size)
        assertEquals("BUNDLE_01", signs[0].id)

        // Failure surfaced explicitly via lastImportResult
        val lastResult = manager.lastImportResult.value
        assertTrue(lastResult is PackImportResult.Failure)
        val failure = lastResult as PackImportResult.Failure
        assertTrue(failure.errors.contains("RESET_ROLLBACK_FAILED"))
        assertEquals(
            PackImportResult.Failure.ErrorCode.RESET_FAILED_ROLLBACK_INCOMPLETE,
            failure.errorCode
        )

        manager.close()
    }

    @Test
    fun `reset rollback with restorePointer failure reports bundled state matching disk and queries`() = runBlocking {
        val manager = createManager()
        manager.initialize()

        // 1. Establish Pack A as active
        manager.importPackFromStreams(
            packStreamProvider = { FileInputStream(packAFile) },
            manifestStreamProvider = { FileInputStream(manifestAFile) }
        )
        val pointerA = storageManager.getActivePointer()!!
        assertEquals("PackA-v1", pointerA.version)

        // 2. Inject faults: reset fails after pointer deletion AND restorePointer throws
        storageManager.resetAfterPointerFaultInjector = {
            throw IOException("Simulated disk failure after pointer deletion during reset")
        }
        storageManager.restorePointerFaultInjector = {
            throw IOException("Simulated disk failure during pointer restore")
        }

        // 3. Attempt resetToBundled
        val resetSuccess = manager.resetToBundled()
        assertFalse("reset must report failure", resetSuccess)

        storageManager.resetAfterPointerFaultInjector = null
        storageManager.restorePointerFaultInjector = null

        // 4. P2: pointer restore failed, so NOTHING on disk points to the old generation —
        // in-memory state must NOT claim Imported (restart would fall back to bundled)
        val state = manager.activePackState.value
        assertFalse("State must NOT claim Imported when pointer restore failed", state is ActivePackState.Imported)
        assertTrue("State must be Bundled to match disk (no pointer) and serving repo", state is ActivePackState.Bundled)

        // Disk agrees: no active pointer (restart falls back to bundled)
        assertNull("Disk must have no active pointer when restore failed", storageManager.getActivePointer())

        // 5. packStatus() and nearby() agree with activePackState
        val status = manager.packStatus()
        assertTrue(status is PackStatus.Ready)
        assertEquals("bundled-source", (status as PackStatus.Ready).source)

        val signs = manager.nearby(BoundingBox(10.8, 106.6, 10.9, 106.7), limit = 10)
        assertEquals(1, signs.size)
        assertEquals("BUNDLE_01", signs[0].id)

        // Failure surfaced explicitly
        val lastResult = manager.lastImportResult.value
        assertTrue(lastResult is PackImportResult.Failure)
        val failure = lastResult as PackImportResult.Failure
        assertTrue(failure.errors.contains("RESET_ROLLBACK_FAILED"))
        assertEquals(
            PackImportResult.Failure.ErrorCode.RESET_FAILED_ROLLBACK_INCOMPLETE,
            failure.errorCode
        )

        manager.close()
    }

    @Test
    fun `reset restore rejected by concurrent close keeps terminal state and closes candidate once`() =
        runBlocking(Dispatchers.Default) {
            val restoredOpenPaused = java.util.concurrent.CountDownLatch(1)
            val restoredOpenRelease = java.util.concurrent.CountDownLatch(1)
            val restoredOpenCount = java.util.concurrent.atomic.AtomicInteger(0)
            var restoredPackPath: String? = null

            val recordingFactory = RecordingDbFactory(
                delegate = dbFactory,
                pauseOnOpen = { file ->
                    // Pause inside OfflineSignRepository.initialize() of the restored
                    // repository: the validator opens the prior generation first, the
                    // repository itself opens it second.
                    if (file.absolutePath == restoredPackPath &&
                        restoredOpenCount.incrementAndGet() == 2
                    ) {
                        restoredOpenPaused.countDown()
                        restoredOpenRelease.await(5, java.util.concurrent.TimeUnit.SECONDS)
                    }
                }
            )

            val manager = ActiveSignPackManager(
                storageManager = storageManager,
                dbFactory = recordingFactory,
                bundledPackProvider = {
                    val manifestText = bundledManifestFile.readText(Charsets.UTF_8)
                    val repo = OfflineSignRepository(
                        packFile = bundledPackFile,
                        manifestJson = manifestText,
                        dbFactory = recordingFactory,
                        ioDispatcher = Dispatchers.Unconfined,
                        syntheticOnly = true
                    )
                    repo.initialize()
                    repo
                },
                ioDispatcher = Dispatchers.Unconfined,
                syntheticOnly = true
            )
            manager.initialize()

            // 1. Establish DISTINCT Pack A as the active imported generation
            val importA = manager.importPackFromStreams(
                packStreamProvider = { FileInputStream(packAFile) },
                manifestStreamProvider = { FileInputStream(manifestAFile) }
            )
            assertTrue("Pack A import must succeed: $importA", importA is PackImportResult.Success)
            val pointerA = storageManager.getActivePointer()!!
            assertEquals("PackA-v1", pointerA.version)
            restoredPackPath = storageManager.resolvePackFile(pointerA).absolutePath

            // 2. Fault the storage reset AFTER pointer deletion so reset recovery reopens the
            // prior generation from disk (actual fault path, not a helper stub).
            storageManager.resetAfterPointerFaultInjector = {
                throw IOException("Simulated disk failure after pointer deletion during reset")
            }

            // 3. Run the reset concurrently, pause it inside the restored repository
            // initialization, then close the manager before it can swap the replacement.
            var resetResult: Boolean? = null
            val resetJob = launch { resetResult = manager.resetToBundled() }

            assertTrue(
                "reset must reach the restored repository initialization",
                restoredOpenPaused.await(5, java.util.concurrent.TimeUnit.SECONDS)
            )

            val stateBeforeClose = manager.activePackState.value
            assertTrue(stateBeforeClose is ActivePackState.Imported)
            manager.close()
            restoredOpenRelease.countDown()
            resetJob.join()

            // 4. Terminal close wins: no success claim, no state published after closure,
            // and the rejected replacement is closed exactly once.
            assertFalse(
                "reset must report failure when terminal close interrupts it",
                resetResult == true
            )
            assertSame(
                "no Imported/Bundled state may be published after terminal closure",
                stateBeforeClose,
                manager.activePackState.value
            )

            val lastResult = manager.lastImportResult.value
            assertTrue("reset must report a typed cancellation: $lastResult", lastResult is PackImportResult.Failure)
            val failure = lastResult as PackImportResult.Failure
            assertEquals(PackImportResult.Failure.ErrorCode.CANCELLED, failure.errorCode)
            assertTrue(failure.errors.contains("RESET_CANCELLED"))

            val restoredHandles = recordingFactory.openedHandles.filter { it.path == restoredPackPath }
            assertTrue(
                "restored repository must have reopened the prior generation",
                restoredHandles.isNotEmpty()
            )
            for (handle in recordingFactory.openedHandles) {
                assertFalse("SQLite handle must not leak: ${handle.path}", handle.isOpen())
                assertEquals(
                    "SQLite handle must be closed exactly once: ${handle.path}",
                    1,
                    handle.closeCount.get()
                )
            }

            // 5. Terminal closure is permanent: no resurrected queries
            val status = manager.packStatus()
            assertTrue(status is PackStatus.Unavailable)
            assertEquals("Quản lý gói đã bị đóng", (status as PackStatus.Unavailable).reason)
            assertTrue(manager.nearby(BoundingBox(10.8, 106.6, 10.9, 106.7), limit = 10).isEmpty())

            // 6. Durable disk behavior: the rollback restored the prior pointer, so a restart
            // serves the prior imported generation again
            val pointerAfter = storageManager.getActivePointer()
            assertNotNull("prior pointer must be restored on disk", pointerAfter)
            assertEquals(pointerA.generationId, pointerAfter!!.generationId)

            storageManager.resetAfterPointerFaultInjector = null
            val freshManager = createManager()
            val freshStatus = freshManager.initialize()
            assertTrue(freshStatus is PackStatus.Ready)
            assertEquals("PackA-v1", (freshStatus as PackStatus.Ready).version)
            val freshSigns = freshManager.nearby(BoundingBox(10.8, 106.6, 10.9, 106.7), limit = 10)
            assertEquals(1, freshSigns.size)
            assertEquals("A_01", freshSigns[0].id)
            freshManager.close()
        }

    @Test
    fun `reset success racing terminal close keeps terminal state and reports typed cancellation`() =
        runBlocking(Dispatchers.Default) {
            val storageResetPaused = java.util.concurrent.CountDownLatch(1)
            val storageResetRelease = java.util.concurrent.CountDownLatch(1)

            val recordingFactory = RecordingDbFactory(delegate = dbFactory)
            val manager = ActiveSignPackManager(
                storageManager = storageManager,
                dbFactory = recordingFactory,
                bundledPackProvider = {
                    val manifestText = bundledManifestFile.readText(Charsets.UTF_8)
                    val repo = OfflineSignRepository(
                        packFile = bundledPackFile,
                        manifestJson = manifestText,
                        dbFactory = recordingFactory,
                        ioDispatcher = Dispatchers.Unconfined,
                        syntheticOnly = true
                    )
                    repo.initialize()
                    repo
                },
                ioDispatcher = Dispatchers.Unconfined,
                syntheticOnly = true
            )
            manager.initialize()

            val importA = manager.importPackFromStreams(
                packStreamProvider = { FileInputStream(packAFile) },
                manifestStreamProvider = { FileInputStream(manifestAFile) }
            )
            assertTrue("Pack A import must succeed: $importA", importA is PackImportResult.Success)
            assertTrue(manager.activePackState.value is ActivePackState.Imported)

            // Let the storage reset run to success, but pause it before the manager publishes
            // the bundled state; close the manager while it is paused.
            storageManager.resetAfterPointerFaultInjector = {
                storageResetPaused.countDown()
                storageResetRelease.await(5, java.util.concurrent.TimeUnit.SECONDS)
            }

            var resetResult: Boolean? = null
            val resetJob = launch { resetResult = manager.resetToBundled() }
            assertTrue(
                "reset must reach the storage reset",
                storageResetPaused.await(5, java.util.concurrent.TimeUnit.SECONDS)
            )

            val stateBeforeClose = manager.activePackState.value
            manager.close()
            storageResetRelease.countDown()
            resetJob.join()

            assertSame(
                "no Bundled state may be published after terminal closure",
                stateBeforeClose,
                manager.activePackState.value
            )
            assertFalse("reset must not claim success after terminal close", resetResult == true)

            val lastResult = manager.lastImportResult.value
            assertTrue("reset must report a typed cancellation: $lastResult", lastResult is PackImportResult.Failure)
            val failure = lastResult as PackImportResult.Failure
            assertEquals(PackImportResult.Failure.ErrorCode.CANCELLED, failure.errorCode)
            assertTrue(failure.errors.contains("RESET_CANCELLED"))

            for (handle in recordingFactory.openedHandles) {
                assertFalse("SQLite handle must not leak: ${handle.path}", handle.isOpen())
                assertEquals(
                    "SQLite handle must be closed exactly once: ${handle.path}",
                    1,
                    handle.closeCount.get()
                )
            }

            val status = manager.packStatus()
            assertTrue(status is PackStatus.Unavailable)
            assertEquals("Quản lý gói đã bị đóng", (status as PackStatus.Unavailable).reason)
            assertTrue(manager.nearby(BoundingBox(10.8, 106.6, 10.9, 106.7), limit = 10).isEmpty())

            // The storage reset itself completed durably: no pointer remains, so a restart
            // comes up on the bundled pack
            assertNull(storageManager.getActivePointer())

            storageManager.resetAfterPointerFaultInjector = null
            val freshManager = createManager()
            val freshStatus = freshManager.initialize()
            assertTrue(freshStatus is PackStatus.Ready)
            assertEquals("bundled-v1", (freshStatus as PackStatus.Ready).version)
            val freshSigns = freshManager.nearby(BoundingBox(10.8, 106.6, 10.9, 106.7), limit = 10)
            assertEquals(1, freshSigns.size)
            assertEquals("BUNDLE_01", freshSigns[0].id)
            freshManager.close()
        }

    @Test
    fun `reset failure without prior generation reports typed no-prior code and keeps bundled serving`() = runBlocking {
        val manager = createManager()
        manager.initialize()
        assertTrue(manager.activePackState.value is ActivePackState.Bundled)
        assertNull(storageManager.getActivePointer())

        storageManager.resetFaultInjector = {
            throw IOException("Simulated disk error before pointer removal during reset")
        }
        val resetSuccess = manager.resetToBundled()
        assertFalse("reset must report failure", resetSuccess)
        storageManager.resetFaultInjector = null

        val lastResult = manager.lastImportResult.value
        assertTrue(lastResult is PackImportResult.Failure)
        val failure = lastResult as PackImportResult.Failure
        assertEquals(PackImportResult.Failure.ErrorCode.RESET_FAILED_NO_PRIOR, failure.errorCode)
        assertTrue(failure.errors.contains("RESET_FAILED_NO_PRIOR"))

        // Reported state, serving repository, and durable pointer must all agree on bundled
        assertTrue(manager.activePackState.value is ActivePackState.Bundled)
        val status = manager.packStatus()
        assertTrue(status is PackStatus.Ready)
        assertEquals("bundled-source", (status as PackStatus.Ready).source)
        val signs = manager.nearby(BoundingBox(10.8, 106.6, 10.9, 106.7), limit = 10)
        assertEquals(1, signs.size)
        assertEquals("BUNDLE_01", signs[0].id)

        manager.close()
    }
}
