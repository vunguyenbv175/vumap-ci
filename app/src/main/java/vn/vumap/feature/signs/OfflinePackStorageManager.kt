package vn.vumap.feature.signs

import vn.vumap.feature.signs.db.SignPackDbFactory
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Manages atomic offline pack staging, validation, generation commit, rollback,
 * and crash-consistent lifecycle in private app storage.
 *
 * Implements:
 * 1. Bounded stream copies stopping at MAX_BYTES + 1 (protects destination disk).
 * 2. Private staging directory isolation: validation executes on the exact bytes committed.
 * 3. Atomic generation pointer updates: pointer file written to .tmp, fsynced, and atomically replaced.
 * 4. Byte-identical rollback: any error before pointer commit preserves prior active generation untouched.
 * 5. Crash recovery: stale staging directories cleaned on startup; uncommitted generations pruned.
 * 6. Retention: keeps current active generation + 1 previous generation for rollback/audit.
 */
class OfflinePackStorageManager(
    val baseDir: File,
    private val dbFactory: SignPackDbFactory,
    val syntheticOnly: Boolean = true
) {
    companion object {
        const val MAX_PACK_BYTES = OfflinePackValidator.MAX_BYTES // 5 MiB
        const val MAX_MANIFEST_BYTES = 1024L * 1024L // 1 MiB
        const val POINTER_FILE_NAME = "active_generation.json"
        const val POINTER_TMP_FILE_NAME = "active_generation.json.tmp"
        const val POINTER_BAK_FILE_NAME = "active_generation.json.bak"
        const val GENERATIONS_DIR_NAME = "generations"
        const val STAGING_DIR_NAME = "staging"
        const val PACK_FILE_NAME = "pack.vupack"
        const val MANIFEST_FILE_NAME = "pack.vupack.manifest.json"
    }

    val generationsDir = File(baseDir, GENERATIONS_DIR_NAME)
    val stagingDir = File(baseDir, STAGING_DIR_NAME)
    val pointerFile = File(baseDir, POINTER_FILE_NAME)
    val pointerTmpFile = File(baseDir, POINTER_TMP_FILE_NAME)
    val pointerBakFile = File(baseDir, POINTER_BAK_FILE_NAME)

    /**
     * Test-only fault injector hook executed immediately before promoting staging to generation dir.
     */
    var promoteFaultInjector: (() -> Unit)? = null

    /**
     * Test-only fault injector hook executed immediately before committing the generation pointer.
     */
    var preCommitFaultInjector: (() -> Unit)? = null

    /**
     * Test-only fault injector hook executed immediately after publishing the pointer,
     * before live activation completes.
     */
    var postCommitFaultInjector: (() -> Unit)? = null

    /**
     * Test-only fault injector hook executed when attempting to restore the pointer during rollback.
     */
    var restorePointerFaultInjector: (() -> Unit)? = null

    /**
     * Test-only fault injector hook executed when attempting to reset storage to bundled.
     */
    var resetFaultInjector: (() -> Unit)? = null

    /**
     * Test-only fault injector hook executed immediately after pointer files are deleted,
     * before generation directories are deleted.
     */
    var resetAfterPointerFaultInjector: (() -> Unit)? = null

    init {
        baseDir.mkdirs()
        generationsDir.mkdirs()
        stagingDir.mkdirs()
    }

    /**
     * Cleans up any leftover staging directories from previous interrupted runs or crashes.
     */
    fun cleanStaging() {
        if (!stagingDir.exists()) return
        stagingDir.listFiles()?.forEach { file ->
            try {
                file.deleteRecursively()
            } catch (_: Exception) {}
        }
    }

    /**
     * Reads and verifies the current active generation pointer.
     * Recovers from active_generation.json.bak if the primary pointer is missing/corrupted.
     * Returns null if no pointer exists or if the referenced files are missing.
     */
    fun getActivePointer(): ActivePackPointer? {
        val primary = parseAndVerifyPointerFile(pointerFile)
        if (primary != null) return primary

        // Recovery path from backup pointer
        val backup = parseAndVerifyPointerFile(pointerBakFile)
        if (backup != null) {
            try {
                writeAtomicPointer(backup)
            } catch (_: Exception) {}
            return backup
        }
        return null
    }

    private fun parseAndVerifyPointerFile(file: File): ActivePackPointer? {
        if (!file.exists() || !file.isFile) return null
        return try {
            val text = file.readText(Charsets.UTF_8)
            val pointer = ActivePackPointer.fromJson(text) ?: return null
            val packFile = resolvePackFile(pointer)
            val manifestFile = resolveManifestFile(pointer)
            if (packFile.exists() && packFile.isFile && manifestFile.exists() && manifestFile.isFile) {
                pointer
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun resolvePackFile(pointer: ActivePackPointer): File = File(baseDir, pointer.packRelativePath)
    fun resolveManifestFile(pointer: ActivePackPointer): File = File(baseDir, pointer.manifestRelativePath)

    /**
     * Intermediate result of staging and validating candidate pack streams.
     */
    sealed interface StageResult {
        data class Ready(
            val stageDir: File,
            val stagedPack: File,
            val stagedManifest: File,
            val generationId: String,
            val metadata: PackStatus.Ready,
            val manifestJson: String
        ) : StageResult

        data class Rejected(
            val failure: PackImportResult.Failure
        ) : StageResult
    }

    /**
     * Copies pack and manifest streams to an isolated private staging folder,
     * bounds the copy to prevent disk exhaustion, and validates the staged files.
     *
     * If validation fails or an exception occurs, the staging folder is wiped
     * and the prior active generation is preserved byte-identically.
     */
    fun stageAndValidate(
        packStreamProvider: () -> InputStream,
        manifestStreamProvider: () -> InputStream
    ): StageResult {
        cleanStaging()
        val generationId = "gen_${System.currentTimeMillis()}_${UUID.randomUUID().toString().replace("-", "").take(8)}"
        val stageDir = File(stagingDir, "stage_$generationId")
        if (!stageDir.mkdirs()) {
            return StageResult.Rejected(
                PackImportResult.Failure(
                    reason = "Không thể tạo thư mục staging tạm thời",
                    errors = listOf("STAGING_MKDIR_FAILED"),
                    errorCode = PackImportResult.Failure.ErrorCode.COMMIT_FAILED
                )
            )
        }

        val stagedPack = File(stageDir, PACK_FILE_NAME)
        val stagedManifest = File(stageDir, MANIFEST_FILE_NAME)

        try {
            // 1. Bounded copy of pack stream
            try {
                packStreamProvider().use { input ->
                    copyBoundedStream(input, stagedPack, MAX_PACK_BYTES)
                }
            } catch (e: StreamTooLargeException) {
                stageDir.deleteRecursively()
                return StageResult.Rejected(
                    PackImportResult.Failure(
                        reason = "Tệp gói .vupack vượt quá giới hạn tối đa 5 MiB (${e.bytesRead} bytes)",
                        errors = listOf("PACK_TOO_LARGE:${e.bytesRead}"),
                        errorCode = PackImportResult.Failure.ErrorCode.PACK_TOO_LARGE
                    )
                )
            } catch (e: Exception) {
                stageDir.deleteRecursively()
                return StageResult.Rejected(
                    PackImportResult.Failure(
                        reason = "Lỗi đọc luồng gói .vupack: ${e.message}",
                        errors = listOf("PACK_STREAM_IO_ERROR:${e.message}"),
                        errorCode = PackImportResult.Failure.ErrorCode.STREAM_IO_ERROR
                    )
                )
            }

            // 2. Bounded copy of manifest stream
            try {
                manifestStreamProvider().use { input ->
                    copyBoundedStream(input, stagedManifest, MAX_MANIFEST_BYTES)
                }
            } catch (e: StreamTooLargeException) {
                stageDir.deleteRecursively()
                return StageResult.Rejected(
                    PackImportResult.Failure(
                        reason = "Tệp manifest vượt quá giới hạn tối đa 1 MiB (${e.bytesRead} bytes)",
                        errors = listOf("MANIFEST_TOO_LARGE:${e.bytesRead}"),
                        errorCode = PackImportResult.Failure.ErrorCode.MANIFEST_TOO_LARGE
                    )
                )
            } catch (e: Exception) {
                stageDir.deleteRecursively()
                return StageResult.Rejected(
                    PackImportResult.Failure(
                        reason = "Lỗi đọc luồng manifest: ${e.message}",
                        errors = listOf("MANIFEST_STREAM_IO_ERROR:${e.message}"),
                        errorCode = PackImportResult.Failure.ErrorCode.STREAM_IO_ERROR
                    )
                )
            }

            // 3. Validate the staged files (the exact bytes that will be committed)
            val manifestJson = stagedManifest.readText(Charsets.UTF_8)
            val validator = OfflinePackValidator(dbFactory, syntheticOnly = syntheticOnly)
            val validation = validator.validate(stagedPack, manifestJson)

            if (!validation.isOk || validation.metadata == null) {
                stageDir.deleteRecursively()
                val isNonSynthetic = validation.errors.contains("MANIFEST_SYNTHETIC_NOT_TRUE")
                return StageResult.Rejected(
                    PackImportResult.Failure(
                        reason = if (isNonSynthetic) "Chỉ cho phép gói thử nghiệm synthetic trong phiên bản này" else "Xác thực gói ngoại tuyến thất bại với ${validation.errors.size} lỗi",
                        errors = validation.errors,
                        errorCode = if (isNonSynthetic) PackImportResult.Failure.ErrorCode.NON_SYNTHETIC_REJECTED else PackImportResult.Failure.ErrorCode.VALIDATION_FAILED
                    )
                )
            }

            val meta = validation.metadata
            if (syntheticOnly && !meta.isSynthetic) {
                stageDir.deleteRecursively()
                return StageResult.Rejected(
                    PackImportResult.Failure(
                        reason = "Chỉ cho phép gói thử nghiệm synthetic trong phiên bản này",
                        errors = listOf("MANIFEST_SYNTHETIC_NOT_TRUE"),
                        errorCode = PackImportResult.Failure.ErrorCode.NON_SYNTHETIC_REJECTED
                    )
                )
            }

            return StageResult.Ready(
                stageDir = stageDir,
                stagedPack = stagedPack,
                stagedManifest = stagedManifest,
                generationId = generationId,
                metadata = meta,
                manifestJson = manifestJson
            )
        } catch (e: Exception) {
            stageDir.deleteRecursively()
            return StageResult.Rejected(
                PackImportResult.Failure(
                    reason = "Lỗi trong quá trình staging và kiểm tra gói: ${e.message}",
                    errors = listOf("STAGE_ERROR:${e.message}"),
                    errorCode = PackImportResult.Failure.ErrorCode.UNKNOWN
                )
            )
        }
    }

    /**
     * Promotes the staging directory to an immutable generation directory under generations/<generationId>.
     */
    fun promoteStaging(ready: StageResult.Ready): File {
        promoteFaultInjector?.invoke()
        generationsDir.mkdirs()
        val targetGenDir = File(generationsDir, ready.generationId)
        if (!ready.stageDir.renameTo(targetGenDir)) {
            try {
                Files.move(ready.stageDir.toPath(), targetGenDir.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Exception) {
                Files.move(ready.stageDir.toPath(), targetGenDir.toPath())
            }
        }
        return targetGenDir
    }

    /**
     * Publishes a new generation pointer atomically.
     * Preserves a backup of the prior pointer before writing the new pointer.
     */
    fun publishPointer(pointer: ActivePackPointer) {
        preCommitFaultInjector?.invoke()
        if (pointerFile.exists() && pointerFile.isFile) {
            try {
                Files.copy(pointerFile.toPath(), pointerBakFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } catch (_: Exception) {}
        }
        writeAtomicPointer(pointer)
        postCommitFaultInjector?.invoke()
    }

    /**
     * Rolls back a promoted generation if live activation or initialization fails.
     * Restores the prior persistent pointer (or removes it if no prior pointer existed)
     * and deletes the unactivated generation directory.
     * Throws on restoration failure so callers know persistent state could not be rewritten.
     */
    fun rollbackPromotion(generationId: String, priorPointer: ActivePackPointer?) {
        val targetGenDir = File(generationsDir, generationId)
        try {
            targetGenDir.deleteRecursively()
        } catch (_: Exception) {}

        restorePointerFaultInjector?.invoke()

        if (priorPointer != null) {
            writeAtomicPointer(priorPointer)
        } else {
            removePointer()
        }
        if (pointerBakFile.exists()) {
            try { pointerBakFile.delete() } catch (_: Exception) {}
        }
        cleanStaging()
    }

    /**
     * Finalizes a committed generation: prunes old generations, cleans backup pointer and staging.
     */
    fun finalizeCommit(activeGenerationId: String) {
        if (pointerBakFile.exists()) {
            try { pointerBakFile.delete() } catch (_: Exception) {}
        }
        pruneOldGenerations(activeGenerationId = activeGenerationId)
        cleanStaging()
    }

    /**
     * Commits a validated staged generation:
     * 1. Promotes the staging directory to generations/<generationId>.
     * 2. Writes the new active generation pointer file via tmp + fsync + atomic rename.
     * 3. Prunes older generations (keeps current + 1 prior generation).
     *
     * If an error occurs during commit, the previous generation pointer remains active.
     */
    fun commitGeneration(ready: StageResult.Ready): ActivePackPointer {
        promoteStaging(ready)

        val pointer = ActivePackPointer(
            generationId = ready.generationId,
            packRelativePath = "$GENERATIONS_DIR_NAME/${ready.generationId}/$PACK_FILE_NAME",
            manifestRelativePath = "$GENERATIONS_DIR_NAME/${ready.generationId}/$MANIFEST_FILE_NAME",
            sha256 = ready.metadata.sha256,
            version = ready.metadata.version,
            source = ready.metadata.source,
            license = ready.metadata.license,
            isSynthetic = ready.metadata.isSynthetic,
            pointCount = ready.metadata.pointCount,
            edgeCount = ready.metadata.edgeCount,
            importedAtMs = System.currentTimeMillis()
        )

        publishPointer(pointer)
        finalizeCommit(activeGenerationId = ready.generationId)
        return pointer
    }

    /**
     * Removes the active pointer file and temporary/backup pointer files.
     */
    fun removePointer(): Boolean {
        var success = true
        if (pointerFile.exists()) {
            success = pointerFile.delete()
        }
        if (pointerTmpFile.exists()) {
            pointerTmpFile.delete()
        }
        if (pointerBakFile.exists()) {
            pointerBakFile.delete()
        }
        return success
    }

    /**
     * Restores an active pointer atomically.
     * Throws on failure so callers can distinguish a durable restore from a lost one.
     */
    fun restorePointer(pointer: ActivePackPointer) {
        restorePointerFaultInjector?.invoke()
        writeAtomicPointer(pointer)
    }

    /**
     * Resets the active generation pointer and deletes custom generations, restoring fallback to bundled pack.
     * Returns true only when pointer files and generation directories are cleanly removed.
     */
    fun resetToBundled(): Boolean {
        resetFaultInjector?.invoke()
        val pointerRemoved = removePointer()
        if (!pointerRemoved || pointerFile.exists() || pointerBakFile.exists()) {
            return false
        }
        resetAfterPointerFaultInjector?.invoke()
        cleanStaging()
        try {
            if (generationsDir.exists()) {
                val deleted = generationsDir.deleteRecursively()
                if (!deleted && generationsDir.exists()) {
                    return false
                }
            }
            if (!generationsDir.mkdirs() && !generationsDir.exists()) {
                return false
            }
        } catch (e: Exception) {
            return false
        }
        return true
    }

    fun writeAtomicPointer(pointer: ActivePackPointer) {
        val jsonBytes = pointer.toJson().toByteArray(Charsets.UTF_8)
        FileOutputStream(pointerTmpFile).use { fos ->
            fos.write(jsonBytes)
            fos.flush()
            try {
                fos.fd.sync()
            } catch (_: Exception) {}
        }

        try {
            Files.move(
                pointerTmpFile.toPath(),
                pointerFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(
                pointerTmpFile.toPath(),
                pointerFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (e: Exception) {
            if (!pointerTmpFile.renameTo(pointerFile)) {
                // Try replacing directly
                if (pointerFile.exists()) pointerFile.delete()
                if (!pointerTmpFile.renameTo(pointerFile)) {
                    throw IOException("Failed to replace pointer file: ${e.message}")
                }
            }
        }
    }

    private fun pruneOldGenerations(activeGenerationId: String) {
        val genDirs = generationsDir.listFiles()?.filter { it.isDirectory } ?: return
        if (genDirs.size <= 2) return

        // Sort by folder name (starts with gen_<timestamp>) descending
        val sorted = genDirs.sortedByDescending { it.name }
        var keptCount = 0
        for (dir in sorted) {
            if (dir.name == activeGenerationId) {
                keptCount++
            } else if (keptCount < 2) {
                // Keep 1 retention generation
                keptCount++
            } else {
                try {
                    dir.deleteRecursively()
                } catch (_: Exception) {}
            }
        }
    }

    private fun copyBoundedStream(
        input: InputStream,
        targetFile: File,
        maxBytes: Long
    ): Long {
        var totalCopied = 0L
        val buffer = ByteArray(64 * 1024)
        targetFile.parentFile?.mkdirs()
        FileOutputStream(targetFile).use { output ->
            while (true) {
                val toRead = minOf(buffer.size.toLong(), maxBytes + 1L - totalCopied).toInt()
                if (toRead <= 0) {
                    throw StreamTooLargeException(totalCopied)
                }
                val read = input.read(buffer, 0, toRead)
                if (read == -1) break
                output.write(buffer, 0, read)
                totalCopied += read
                if (totalCopied > maxBytes) {
                    output.flush()
                    try { output.fd.sync() } catch (_: Exception) {}
                    throw StreamTooLargeException(totalCopied)
                }
            }
            output.flush()
            try {
                output.fd.sync()
            } catch (_: Exception) {}
        }
        return totalCopied
    }
}

class StreamTooLargeException(val bytesRead: Long) : IOException("Stream exceeded limit: $bytesRead bytes")
