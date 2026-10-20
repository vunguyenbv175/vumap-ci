package vn.vumap.feature.signs

/**
 * Result of an offline sign pack import operation.
 */
sealed interface PackImportResult {

    /**
     * Import succeeded, validated, and committed as the active generation.
     */
    data class Success(
        val generationId: String,
        val metadata: PackStatus.Ready,
        val importedAtMs: Long
    ) : PackImportResult

    /**
     * Import failed, was rejected, or was cancelled.
     * The prior active pack and manifest are preserved byte-identically.
     *
     * Also carries failed reset outcomes reported through
     * [ActiveSignPackManager.lastImportResult], using the dedicated
     * `RESET_FAILED_*` error codes for the exact kind of rollback that happened.
     */
    data class Failure(
        val reason: String,
        val errors: List<String> = emptyList(),
        val errorCode: ErrorCode = ErrorCode.UNKNOWN
    ) : PackImportResult {

        enum class ErrorCode {
            /** File selection was incomplete, missing, or invalid. */
            SELECTION_INVALID,

            /** Error reading from input stream or writing to private storage. */
            STREAM_IO_ERROR,

            /** Pack file exceeded the 5 MiB size cap. */
            PACK_TOO_LARGE,

            /** Manifest file exceeded the 1 MiB size cap. */
            MANIFEST_TOO_LARGE,

            /** Schema, SQLite integrity, coordinate bounds, or checksum validation failed. */
            VALIDATION_FAILED,

            /** Pack declared synthetic = false when synthetic-only mode is required. */
            NON_SYNTHETIC_REJECTED,

            /** Failed during generation directory promotion or pointer write. */
            COMMIT_FAILED,

            /** User cancelled or operation was interrupted. */
            CANCELLED,

            /**
             * Reset failed, but the prior imported generation was durably
             * restored and is serving again.
             */
            RESET_FAILED_PRIOR_RESTORED,

            /**
             * Reset failed and the prior imported generation could not be
             * restored; the bundled fallback pack is serving.
             */
            RESET_FAILED_ROLLBACK_INCOMPLETE,

            /** Reset failed before any imported generation had been committed. */
            RESET_FAILED_NO_PRIOR,

            /** Unknown or uncategorized failure. */
            UNKNOWN
        }
    }
}
