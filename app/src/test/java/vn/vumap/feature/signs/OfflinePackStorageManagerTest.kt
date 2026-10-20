package vn.vumap.feature.signs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import vn.vumap.feature.signs.db.JdbcSignPackDbFactory
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException

class OfflinePackStorageManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var baseDir: File
    private val dbFactory = JdbcSignPackDbFactory()
    private lateinit var storageManager: OfflinePackStorageManager

    private lateinit var validPackFile: File
    private lateinit var validManifestFile: File

    @Before
    fun setUp() {
        baseDir = tempFolder.newFolder("signs_storage")
        storageManager = OfflinePackStorageManager(baseDir, dbFactory, syntheticOnly = true)

        val srcDir = tempFolder.newFolder("src_pack")
        validPackFile = File(srcDir, "valid.vupack")
        validManifestFile = File(srcDir, "valid.vupack.manifest.json")
        TestPackBuilder.buildPack(
            packFile = validPackFile,
            manifestFile = validManifestFile,
            version = "gen-1.0",
            source = "test-storage"
        )
    }

    @Test
    fun `initial storage manager has no active pointer`() {
        assertNull(storageManager.getActivePointer())
        assertFalse(storageManager.pointerFile.exists())
    }

    @Test
    fun `stageAndValidate stages snapshot and produces ready result`() {
        val stageResult = storageManager.stageAndValidate(
            packStreamProvider = { FileInputStream(validPackFile) },
            manifestStreamProvider = { FileInputStream(validManifestFile) }
        )

        assertTrue(stageResult is OfflinePackStorageManager.StageResult.Ready)
        val ready = stageResult as OfflinePackStorageManager.StageResult.Ready
        assertTrue(ready.stageDir.exists())
        assertTrue(ready.stagedPack.exists())
        assertTrue(ready.stagedManifest.exists())
        assertEquals(ready.stagedPack.length(), validPackFile.length())
        assertEquals(ready.stagedManifest.length(), validManifestFile.length())
        assertEquals("gen-1.0", ready.metadata.version)
        assertEquals("test-storage", ready.metadata.source)
    }

    @Test
    fun `commitGeneration promotes directory and writes atomic pointer`() {
        val stageResult = storageManager.stageAndValidate(
            packStreamProvider = { FileInputStream(validPackFile) },
            manifestStreamProvider = { FileInputStream(validManifestFile) }
        ) as OfflinePackStorageManager.StageResult.Ready

        val pointer = storageManager.commitGeneration(stageResult)

        assertNotNull(pointer)
        assertTrue(storageManager.pointerFile.exists())
        assertFalse(storageManager.pointerTmpFile.exists())

        val readPointer = storageManager.getActivePointer()
        assertNotNull(readPointer)
        assertEquals(pointer.generationId, readPointer!!.generationId)
        assertEquals(pointer.sha256, readPointer.sha256)
        assertEquals("gen-1.0", readPointer.version)

        val resolvedPack = storageManager.resolvePackFile(readPointer)
        val resolvedManifest = storageManager.resolveManifestFile(readPointer)
        assertTrue(resolvedPack.exists())
        assertTrue(resolvedManifest.exists())
        assertEquals(validPackFile.length(), resolvedPack.length())
    }

    @Test
    fun `rejects oversized pack input and caps copy at limit plus one`() {
        // Stream of 6 MiB (limit is 5 MiB)
        val oversizedBytes = ByteArray(OfflinePackValidator.MAX_BYTES.toInt() + 2048) { 0x41 }

        val stageResult = storageManager.stageAndValidate(
            packStreamProvider = { ByteArrayInputStream(oversizedBytes) },
            manifestStreamProvider = { FileInputStream(validManifestFile) }
        )

        assertTrue(stageResult is OfflinePackStorageManager.StageResult.Rejected)
        val rejected = stageResult as OfflinePackStorageManager.StageResult.Rejected
        assertEquals(PackImportResult.Failure.ErrorCode.PACK_TOO_LARGE, rejected.failure.errorCode)

        // Staging directory was deleted
        val stageDirs = storageManager.stagingDir.listFiles()
        assertTrue(stageDirs.isNullOrEmpty())
    }

    @Test
    fun `rejects oversized manifest input`() {
        val oversizedManifest = ByteArray(OfflinePackStorageManager.MAX_MANIFEST_BYTES.toInt() + 1024) { 0x7B }

        val stageResult = storageManager.stageAndValidate(
            packStreamProvider = { FileInputStream(validPackFile) },
            manifestStreamProvider = { ByteArrayInputStream(oversizedManifest) }
        )

        assertTrue(stageResult is OfflinePackStorageManager.StageResult.Rejected)
        val rejected = stageResult as OfflinePackStorageManager.StageResult.Rejected
        assertEquals(PackImportResult.Failure.ErrorCode.MANIFEST_TOO_LARGE, rejected.failure.errorCode)
        val stageDirs = storageManager.stagingDir.listFiles()
        assertTrue(stageDirs.isNullOrEmpty())
    }

    @Test
    fun `rejects non-synthetic pack when syntheticOnly is enabled`() {
        val nonSynthDir = tempFolder.newFolder("non_synth")
        val packFile = File(nonSynthDir, "non_synth.vupack")
        val manifestFile = File(nonSynthDir, "non_synth.vupack.manifest.json")
        TestPackBuilder.buildPack(
            packFile = packFile,
            manifestFile = manifestFile,
            isSynthetic = false
        )

        val stageResult = storageManager.stageAndValidate(
            packStreamProvider = { FileInputStream(packFile) },
            manifestStreamProvider = { FileInputStream(manifestFile) }
        )

        assertTrue(stageResult is OfflinePackStorageManager.StageResult.Rejected)
        val rejected = stageResult as OfflinePackStorageManager.StageResult.Rejected
        assertEquals(PackImportResult.Failure.ErrorCode.NON_SYNTHETIC_REJECTED, rejected.failure.errorCode)
    }

    @Test
    fun `prunes generations leaving active plus one previous generation`() {
        val gen1Dir = tempFolder.newFolder("pack_g1")
        val p1 = File(gen1Dir, "g1.vupack")
        val m1 = File(gen1Dir, "g1.vupack.manifest.json")
        TestPackBuilder.buildPack(p1, m1, version = "g1")

        val gen2Dir = tempFolder.newFolder("pack_g2")
        val p2 = File(gen2Dir, "g2.vupack")
        val m2 = File(gen2Dir, "g2.vupack.manifest.json")
        TestPackBuilder.buildPack(p2, m2, version = "g2")

        val gen3Dir = tempFolder.newFolder("pack_g3")
        val p3 = File(gen3Dir, "g3.vupack")
        val m3 = File(gen3Dir, "g3.vupack.manifest.json")
        TestPackBuilder.buildPack(p3, m3, version = "g3")

        // Install g1
        val r1 = storageManager.stageAndValidate({ FileInputStream(p1) }, { FileInputStream(m1) }) as OfflinePackStorageManager.StageResult.Ready
        storageManager.commitGeneration(r1)

        // Install g2
        val r2 = storageManager.stageAndValidate({ FileInputStream(p2) }, { FileInputStream(m2) }) as OfflinePackStorageManager.StageResult.Ready
        storageManager.commitGeneration(r2)

        // Install g3
        val r3 = storageManager.stageAndValidate({ FileInputStream(p3) }, { FileInputStream(m3) }) as OfflinePackStorageManager.StageResult.Ready
        storageManager.commitGeneration(r3)

        val active = storageManager.getActivePointer()
        assertNotNull(active)
        assertEquals("g3", active!!.version)

        // Generations directory must retain at most 2 folders (active + 1 retention copy)
        val genFolders = storageManager.generationsDir.listFiles()?.filter { it.isDirectory }
        assertNotNull(genFolders)
        assertEquals(2, genFolders!!.size)
        assertTrue(genFolders.any { it.name == active.generationId })
    }

    @Test
    fun `cleanStaging removes stale staging files after simulated crash`() {
        val staleStage = File(storageManager.stagingDir, "stage_stale_123")
        staleStage.mkdirs()
        File(staleStage, "pack.vupack.stage").writeText("partial garbage")

        assertTrue(staleStage.exists())
        storageManager.cleanStaging()
        assertFalse(staleStage.exists())
    }

    @Test
    fun `resetToBundled wipes pointer and generations`() {
        val stageResult = storageManager.stageAndValidate(
            packStreamProvider = { FileInputStream(validPackFile) },
            manifestStreamProvider = { FileInputStream(validManifestFile) }
        ) as OfflinePackStorageManager.StageResult.Ready
        storageManager.commitGeneration(stageResult)

        assertTrue(storageManager.pointerFile.exists())
        assertTrue(storageManager.getActivePointer() != null)

        val resetSuccess = storageManager.resetToBundled()
        assertTrue(resetSuccess)
        assertFalse(storageManager.pointerFile.exists())
        assertNull(storageManager.getActivePointer())
    }
}
