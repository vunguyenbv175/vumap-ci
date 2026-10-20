package vn.vumap.feature.signs

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import vn.vumap.core.model.BoundingBox
import vn.vumap.core.model.GeoPoint
import vn.vumap.core.model.PhysicalSign
import vn.vumap.core.model.RoadEdge
import vn.vumap.feature.signs.db.SignPackDbFactory
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Health and provenance description of the currently active sign pack.
 */
sealed interface ActivePackState {
    data object Loading : ActivePackState

    /**
     * The built-in bundled pack packaged with the app assets is active.
     */
    data class Bundled(
        val metadata: PackStatus.Ready
    ) : ActivePackState

    /**
     * An externally imported pack generation is active.
     */
    data class Imported(
        val pointer: ActivePackPointer,
        val metadata: PackStatus.Ready
    ) : ActivePackState

    /**
     * Neither bundled nor imported pack is available or valid.
     */
    data class Unavailable(
        val reason: String,
        val errors: List<String> = emptyList()
    ) : ActivePackState
}

/**
 * Manages the lifecycle, dynamic swapping, SAF updates, and query routing
 * for offline traffic sign data.
 *
 * Enforces:
 * - Full lifecycle serialization: staging, validation, promotion, pointer publication,
 *   and reset are serialized under a lifecycle transaction mutex to eliminate overlap races.
 * - Single atomic activation transaction: if database opening, manifest reading, or
 *   pointer publication fails, the unactivated generation is deleted, the prior pointer
 *   is restored, and the prior generation remains byte-identical.
 * - Atomic reader lease acquisition: selection and lease acquisition are combined under
 *   a shared lock, guaranteeing no concurrent swap can close a selected instance before
 *   its reader lease is acquired.
 * - Replaced database closure deferred: replaced repositories close only when all in-flight
 *   readers release their leases, preventing empty results during swaps.
 * - Terminal closure coordination: [close] sets terminal closed state; in-progress lifecycle
 *   operations reject publication (including reset recovery states) and cleanly close
 *   candidate repositories.
 */
class ActiveSignPackManager(
    val storageManager: OfflinePackStorageManager,
    private val dbFactory: SignPackDbFactory,
    private val bundledPackProvider: suspend () -> OfflineSignRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val syntheticOnly: Boolean = true
) : SignProvider, Closeable {

    private val lifecycleMutex = Mutex()
    private val repoSwapLock = Any()

    private var activeLeasedRepo: LeasedRepository? = null
    private var isInitialized = false
    private var isManagerClosed = false

    private val _activePackState = MutableStateFlow<ActivePackState>(ActivePackState.Loading)
    val activePackState: StateFlow<ActivePackState> = _activePackState.asStateFlow()

    private val _lastImportResult = MutableStateFlow<PackImportResult?>(null)
    val lastImportResult: StateFlow<PackImportResult?> = _lastImportResult.asStateFlow()

    /**
     * Tracks reader leases on a repository instance. A replaced repository is
     * closed only when all in-flight readers have released their leases.
     */
    class LeasedRepository(val repo: OfflineSignRepository) {
        val activeReaders = AtomicInteger(0)
        val isClosed = AtomicBoolean(false)
        private val stateLock = Any()

        fun acquire(): Boolean {
            synchronized(stateLock) {
                if (isClosed.get()) return false
                activeReaders.incrementAndGet()
                return true
            }
        }

        fun release() {
            val remaining = activeReaders.decrementAndGet()
            synchronized(stateLock) {
                if (isClosed.get() && remaining == 0) {
                    repo.close()
                }
            }
        }

        fun closeWhenIdle() {
            synchronized(stateLock) {
                isClosed.set(true)
                if (activeReaders.get() == 0) {
                    repo.close()
                }
            }
        }
    }

    /**
     * Initializes the manager on startup under the lifecycle lock.
     */
    suspend fun initialize(): PackStatus = withContext(ioDispatcher) {
        lifecycleMutex.withLock {
            val closed = synchronized(repoSwapLock) { isManagerClosed }
            if (closed) return@withLock PackStatus.Unavailable("Quản lý gói đã bị đóng")

            val alreadyInitialized = synchronized(repoSwapLock) { isInitialized && activeLeasedRepo != null }
            if (alreadyInitialized) {
                val leased = acquireLease()
                if (leased != null) {
                    return@withLock try {
                        leased.repo.packStatus()
                    } finally {
                        leased.release()
                    }
                } else {
                    return@withLock PackStatus.Unavailable("Quản lý gói đã bị đóng")
                }
            }

            storageManager.cleanStaging()
            val pointer = storageManager.getActivePointer()

            if (pointer != null) {
                val packFile = storageManager.resolvePackFile(pointer)
                val manifestFile = storageManager.resolveManifestFile(pointer)
                try {
                    val manifestText = manifestFile.readText(Charsets.UTF_8)
                    val importedRepo = OfflineSignRepository(
                        packFile = packFile,
                        manifestJson = manifestText,
                        dbFactory = dbFactory,
                        ioDispatcher = ioDispatcher,
                        syntheticOnly = syntheticOnly
                    )
                    val status = importedRepo.initialize()
                    if (status is PackStatus.Ready) {
                        val swapped = swapActiveRepo(importedRepo)
                        if (swapped) {
                            _activePackState.value = ActivePackState.Imported(pointer, status)
                            isInitialized = true
                            return@withLock status
                        } else {
                            importedRepo.close()
                            return@withLock PackStatus.Unavailable("Quản lý gói đã bị đóng")
                        }
                    } else {
                        importedRepo.close()
                    }
                } catch (_: Exception) {}
            }

            // Fallback to bundled pack
            val bundledRepo = bundledPackProvider()
            val bundledStatus = bundledRepo.initialize()
            val swapped = swapActiveRepo(bundledRepo)
            if (!swapped) {
                bundledRepo.close()
                return@withLock PackStatus.Unavailable("Quản lý gói đã bị đóng")
            }

            if (bundledStatus is PackStatus.Ready) {
                _activePackState.value = ActivePackState.Bundled(bundledStatus)
            } else {
                _activePackState.value = ActivePackState.Unavailable(
                    reason = (bundledStatus as? PackStatus.Unavailable)?.reason ?: "Gói mặc định không khả dụng",
                    errors = (bundledStatus as? PackStatus.Unavailable)?.errors ?: emptyList()
                )
            }
            isInitialized = true
            bundledStatus
        }
    }

    /**
     * Imports a new pack and manifest pair from streams:
     * Full lifecycle is serialized under [lifecycleMutex].
     * If validation, pre-opening, promotion, candidate initialization, or pointer publication fails,
     * the prior pointer and generation are fully preserved byte-identically.
     */
    suspend fun importPackFromStreams(
        packStreamProvider: () -> InputStream,
        manifestStreamProvider: () -> InputStream
    ): PackImportResult = withContext(ioDispatcher) {
        lifecycleMutex.withLock {
            synchronized(repoSwapLock) {
                if (isManagerClosed) {
                    return@withLock PackImportResult.Failure(
                        reason = "Quản lý gói đã bị đóng",
                        errors = listOf("MANAGER_CLOSED"),
                        errorCode = PackImportResult.Failure.ErrorCode.CANCELLED
                    )
                }
            }

            val priorPointer = storageManager.getActivePointer()

            val stageResult = storageManager.stageAndValidate(packStreamProvider, manifestStreamProvider)
            if (stageResult is OfflinePackStorageManager.StageResult.Rejected) {
                _lastImportResult.value = stageResult.failure
                return@withLock stageResult.failure
            }

            val ready = stageResult as OfflinePackStorageManager.StageResult.Ready

            // Pre-verify candidate database can be opened
            val preOpenRepo = OfflineSignRepository(
                packFile = ready.stagedPack,
                manifestJson = ready.manifestJson,
                dbFactory = dbFactory,
                ioDispatcher = ioDispatcher,
                syntheticOnly = syntheticOnly
            )
            val preStatus = preOpenRepo.initialize()
            preOpenRepo.close()

            if (preStatus !is PackStatus.Ready) {
                ready.stageDir.deleteRecursively()
                val failure = PackImportResult.Failure(
                    reason = "Không thể mở cơ sở dữ liệu đã staging",
                    errors = (preStatus as? PackStatus.Unavailable)?.errors ?: listOf("PRE_OPEN_FAILED"),
                    errorCode = PackImportResult.Failure.ErrorCode.VALIDATION_FAILED
                )
                _lastImportResult.value = failure
                return@withLock failure
            }

            // Promote directory to immutable generations/<id> (wrapped in failure transaction)
            val targetGenDir = try {
                storageManager.promoteStaging(ready)
            } catch (e: Exception) {
                ready.stageDir.deleteRecursively()
                val failure = PackImportResult.Failure(
                    reason = "Lỗi chuyển đổi thư mục thế hệ: ${e.message}",
                    errors = listOf("PROMOTE_FAILED:${e.message}"),
                    errorCode = PackImportResult.Failure.ErrorCode.COMMIT_FAILED
                )
                _lastImportResult.value = failure
                return@withLock failure
            }

            val pointer = ActivePackPointer(
                generationId = ready.generationId,
                packRelativePath = "${OfflinePackStorageManager.GENERATIONS_DIR_NAME}/${ready.generationId}/${OfflinePackStorageManager.PACK_FILE_NAME}",
                manifestRelativePath = "${OfflinePackStorageManager.GENERATIONS_DIR_NAME}/${ready.generationId}/${OfflinePackStorageManager.MANIFEST_FILE_NAME}",
                sha256 = ready.metadata.sha256,
                version = ready.metadata.version,
                source = ready.metadata.source,
                license = ready.metadata.license,
                isSynthetic = ready.metadata.isSynthetic,
                pointCount = ready.metadata.pointCount,
                edgeCount = ready.metadata.edgeCount,
                importedAtMs = System.currentTimeMillis()
            )

            fun rollbackAndFail(
                primaryReason: String,
                primaryCode: PackImportResult.Failure.ErrorCode,
                primaryErrors: List<String>
            ): PackImportResult.Failure {
                val errors = mutableListOf<String>()
                errors.addAll(primaryErrors)
                var combinedReason = primaryReason
                try {
                    storageManager.rollbackPromotion(pointer.generationId, priorPointer)
                } catch (rbEx: Exception) {
                    errors.add("ROLLBACK_RESTORE_FAILED:${rbEx.message}")
                    combinedReason = "$primaryReason (Khôi phục thế hệ trước thất bại: ${rbEx.message})"
                }
                val failure = PackImportResult.Failure(
                    reason = combinedReason,
                    errors = errors,
                    errorCode = primaryCode
                )
                _lastImportResult.value = failure
                return failure
            }

            // Open candidate live repository on the promoted generation
            val candidatePack = storageManager.resolvePackFile(pointer)
            val candidateManifest = storageManager.resolveManifestFile(pointer)
            val candidateRepo = try {
                val manifestText = candidateManifest.readText(Charsets.UTF_8)
                OfflineSignRepository(
                    packFile = candidatePack,
                    manifestJson = manifestText,
                    dbFactory = dbFactory,
                    ioDispatcher = ioDispatcher,
                    syntheticOnly = syntheticOnly
                )
            } catch (e: Exception) {
                return@withLock rollbackAndFail(
                    primaryReason = "Lỗi đọc tệp manifest sau khi chuyển đổi: ${e.message}",
                    primaryCode = PackImportResult.Failure.ErrorCode.COMMIT_FAILED,
                    primaryErrors = listOf("POST_PROMOTE_READ_ERROR:${e.message}")
                )
            }

            val candidateStatus = candidateRepo.initialize()
            if (candidateStatus !is PackStatus.Ready) {
                candidateRepo.close()
                return@withLock rollbackAndFail(
                    primaryReason = "Không thể mở cơ sở dữ liệu sau khi chuyển đổi thư mục thế hệ",
                    primaryCode = PackImportResult.Failure.ErrorCode.COMMIT_FAILED,
                    primaryErrors = (candidateStatus as? PackStatus.Unavailable)?.errors ?: listOf("POST_PROMOTE_OPEN_FAILED")
                )
            }

            // Atomically publish pointer file
            try {
                storageManager.publishPointer(pointer)
            } catch (e: Exception) {
                candidateRepo.close()
                return@withLock rollbackAndFail(
                    primaryReason = "Lỗi ghi nhận thế hệ gói mới: ${e.message}",
                    primaryCode = PackImportResult.Failure.ErrorCode.COMMIT_FAILED,
                    primaryErrors = listOf("COMMIT_IO_ERROR:${e.message}")
                )
            }

            // Live activation: atomic swap leased repository
            val swapped = swapActiveRepo(candidateRepo)
            if (!swapped) {
                candidateRepo.close()
                return@withLock rollbackAndFail(
                    primaryReason = "Quản lý gói đã bị đóng trong khi kích hoạt",
                    primaryCode = PackImportResult.Failure.ErrorCode.CANCELLED,
                    primaryErrors = listOf("MANAGER_CLOSED")
                )
            }

            _activePackState.value = ActivePackState.Imported(pointer, candidateStatus)
            isInitialized = true

            storageManager.finalizeCommit(pointer.generationId)

            val success = PackImportResult.Success(
                generationId = pointer.generationId,
                metadata = candidateStatus,
                importedAtMs = pointer.importedAtMs
            )
            _lastImportResult.value = success
            success
        }
    }

    /**
     * Resets active pack to the bundled fallback pack under [lifecycleMutex].
     * Verifies bundled candidate can be initialized and storage is cleanly reset
     * before finalizing bundled state. Rolls back on any storage reset failure.
     *
     * Terminal closure wins over every reset outcome: once [close] has run, the
     * reset publishes no Imported/Bundled state, reports a typed CANCELLED
     * failure through [lastImportResult], and returns false — even when the
     * durable pointer had already been restored or cleared.
     */
    suspend fun resetToBundled(): Boolean = withContext(ioDispatcher) {
        lifecycleMutex.withLock {
            synchronized(repoSwapLock) {
                if (isManagerClosed) return@withLock false
            }

            // 1. Initialize and verify bundled candidate FIRST before deleting active generation
            val bundledCandidate = try {
                bundledPackProvider()
            } catch (_: Exception) {
                return@withLock false
            }

            val bundledStatus = try {
                bundledCandidate.initialize()
            } catch (_: Exception) {
                bundledCandidate.close()
                return@withLock false
            }

            if (bundledStatus !is PackStatus.Ready) {
                bundledCandidate.close()
                return@withLock false
            }

            val priorPointer = storageManager.getActivePointer()

            // 2. Install bundledCandidate so old repo's SQLite handle is released (required on Windows)
            val oldLeased = synchronized(repoSwapLock) {
                if (isManagerClosed) {
                    bundledCandidate.close()
                    return@withLock false
                }
                val old = activeLeasedRepo
                activeLeasedRepo = LeasedRepository(bundledCandidate)
                old
            }
            oldLeased?.closeWhenIdle()

            // 3. Perform storage reset and verify success
            val storageResetOk = try {
                storageManager.resetToBundled()
            } catch (_: Exception) {
                false
            }

            if (!storageResetOk) {
                // Storage reset failed: roll back pointer and repository
                var pointerRestored = false
                var restoredToPrior = false
                if (priorPointer != null) {
                    // P2: only trust rollback as successful when pointer restore itself succeeded
                    pointerRestored = try {
                        storageManager.restorePointer(priorPointer)
                        true
                    } catch (_: Exception) {
                        false
                    }

                    if (pointerRestored) {
                        try {
                            val packFile = storageManager.resolvePackFile(priorPointer)
                            val manifestFile = storageManager.resolveManifestFile(priorPointer)
                            if (packFile.exists() && manifestFile.exists()) {
                                val restoredRepo = OfflineSignRepository(
                                    packFile = packFile,
                                    manifestJson = manifestFile.readText(Charsets.UTF_8),
                                    dbFactory = dbFactory,
                                    ioDispatcher = ioDispatcher,
                                    syntheticOnly = syntheticOnly
                                )
                                // Ownership: the replacement is closed exactly once — by this
                                // block while it never reaches the swap, or by swapActiveRepo
                                // when the swap rejects it because terminal close won first.
                                var ownershipHeld = true
                                try {
                                    val restoredStatus = restoredRepo.initialize()
                                    if (restoredStatus is PackStatus.Ready) {
                                        if (swapActiveRepo(restoredRepo)) {
                                            ownershipHeld = false
                                            // Refuses to publish once close has run, so an
                                            // Imported state can never outlive terminal closure.
                                            restoredToPrior = publishStateIfOpen(
                                                ActivePackState.Imported(priorPointer, restoredStatus)
                                            )
                                        } else {
                                            // Rejected by terminal closure: already closed once.
                                            ownershipHeld = false
                                        }
                                    }
                                } finally {
                                    if (ownershipHeld) restoredRepo.close()
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }

                if (restoredToPrior) {
                    // The prior generation is live again and the durable pointer names it.
                    _lastImportResult.value = PackImportResult.Failure(
                        reason = "Reset thất bại; đã khôi phục gói trước đó nguyên vẹn",
                        errors = listOf("RESET_FAILED_PRIOR_RESTORED"),
                        errorCode = PackImportResult.Failure.ErrorCode.RESET_FAILED_PRIOR_RESTORED
                    )
                    return@withLock false
                }

                // P1: publish the state that is actually serving — bundledCandidate is live
                // and restart falls back to bundled, so report Bundled (not Unavailable).
                // The publication is refused once close has run, so terminal closure is
                // never followed by a Bundled state that no serving repository backs.
                if (!publishStateIfOpen(ActivePackState.Bundled(bundledStatus))) {
                    _lastImportResult.value = resetCancelledFailure()
                    return@withLock false
                }
                _lastImportResult.value = if (priorPointer == null) {
                    // No prior imported generation: bundled candidate is what serves and
                    // what restart falls back to.
                    PackImportResult.Failure(
                        reason = "Reset thất bại trước khi có thế hệ gói nhập nào",
                        errors = listOf("RESET_FAILED_NO_PRIOR"),
                        errorCode = PackImportResult.Failure.ErrorCode.RESET_FAILED_NO_PRIOR
                    )
                } else {
                    PackImportResult.Failure(
                        reason = if (pointerRestored) {
                            "Reset thất bại; khôi phục gói trước đó không thành công, đang dùng gói mặc định"
                        } else {
                            "Reset thất bại; không ghi lại được thế hệ gói trước đó (lỗi ghi pointer), đang dùng gói mặc định"
                        },
                        errors = listOf("RESET_ROLLBACK_FAILED"),
                        errorCode = PackImportResult.Failure.ErrorCode.RESET_FAILED_ROLLBACK_INCOMPLETE
                    )
                }
                return@withLock false
            }

            if (!publishStateIfOpen(ActivePackState.Bundled(bundledStatus))) {
                // Storage reset succeeded durably, but terminal closure interrupted the
                // live transition: report the cancellation instead of a Bundled state.
                _lastImportResult.value = resetCancelledFailure()
                return@withLock false
            }
            _lastImportResult.value = null
            true
        }
    }

    /**
     * Failure reported when terminal closure interrupted a reset before its outcome could
     * be published. The durable pointer may legitimately have been restored or cleared by
     * then; the manager itself is gone, so the reset must not report success.
     */
    private fun resetCancelledFailure(): PackImportResult.Failure = PackImportResult.Failure(
        reason = "Khôi phục gói mặc định đã dừng vì quản lý gói đã bị đóng",
        errors = listOf("RESET_CANCELLED"),
        errorCode = PackImportResult.Failure.ErrorCode.CANCELLED
    )

    /**
     * Publishes [state] as the active pack state only while the manager is still open,
     * atomically with respect to [close].
     *
     * Returns false when the manager was closed first: a terminal closure must never be
     * followed by a fresh Imported/Bundled publication that no serving repository backs.
     */
    private fun publishStateIfOpen(state: ActivePackState): Boolean {
        synchronized(repoSwapLock) {
            if (isManagerClosed) return false
            _activePackState.value = state
            isInitialized = true
            return true
        }
    }

    /**
     * Swaps the active repository and closes the old repository only when idle.
     * Returns false if the manager has already been closed.
     *
     * When the swap is rejected because [close] already ran, [newRepo] is closed by this
     * method before returning: callers must not treat a rejected candidate as their own
     * to close again.
     */
    private fun swapActiveRepo(newRepo: OfflineSignRepository): Boolean {
        val oldLeased = synchronized(repoSwapLock) {
            if (isManagerClosed) {
                newRepo.close()
                return false
            }
            val old = activeLeasedRepo
            activeLeasedRepo = LeasedRepository(newRepo)
            old
        }
        oldLeased?.closeWhenIdle()
        return true
    }

    /**
     * Atomically selects active repository and acquires a reader lease while holding [repoSwapLock].
     * Prevents race conditions where a concurrent swap marks the repository closed before acquire runs.
     */
    private fun acquireLease(): LeasedRepository? {
        synchronized(repoSwapLock) {
            if (isManagerClosed) return null
            val leased = activeLeasedRepo ?: return null
            if (leased.acquire()) {
                return leased
            }
            return null
        }
    }

    private suspend fun <T> withActiveRepo(block: suspend (OfflineSignRepository) -> T): T? {
        val closed = synchronized(repoSwapLock) { isManagerClosed }
        if (closed) return null

        if (!isInitialized) initialize()

        val leased = acquireLease() ?: return null
        return try {
            block(leased.repo)
        } finally {
            leased.release()
        }
    }

    override suspend fun packStatus(): PackStatus = withContext(ioDispatcher) {
        val closed = synchronized(repoSwapLock) { isManagerClosed }
        if (closed) return@withContext PackStatus.Unavailable("Quản lý gói đã bị đóng")
        withActiveRepo { it.packStatus() } ?: PackStatus.Unavailable("Chưa khởi tạo")
    }

    override suspend fun nearby(area: BoundingBox, limit: Int): List<PhysicalSign> = withContext(ioDispatcher) {
        withActiveRepo { it.nearby(area, limit) } ?: emptyList()
    }

    override suspend fun nearby(point: GeoPoint, radiusMeters: Double, limit: Int): List<PhysicalSign> = withContext(ioDispatcher) {
        withActiveRepo { it.nearby(point, radiusMeters, limit) } ?: emptyList()
    }

    override suspend fun getEdge(edgeId: String): RoadEdge? = withContext(ioDispatcher) {
        withActiveRepo { it.getEdge(edgeId) }
    }

    override suspend fun nearbyEdges(area: BoundingBox, limit: Int): List<RoadEdge> = withContext(ioDispatcher) {
        withActiveRepo { it.nearbyEdges(area, limit) } ?: emptyList()
    }

    override fun close() {
        val leasedToClose = synchronized(repoSwapLock) {
            isManagerClosed = true
            val leased = activeLeasedRepo
            activeLeasedRepo = null
            leased
        }
        leasedToClose?.closeWhenIdle()
    }
}
