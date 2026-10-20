package vn.vumap.feature.signs

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Helper for Storage Access Framework (SAF) companion-pair document selection and importing.
 */
object SafPackImportHelper {

    val SQLITE_HEADER = byteArrayOf(
        0x53, 0x51, 0x4c, 0x69, 0x74, 0x65, 0x20, 0x66, // "SQLite f"
        0x6f, 0x72, 0x6d, 0x61, 0x74, 0x20, 0x33, 0x00  // "ormat 3\0"
    )

    fun isSqliteHeader(header: ByteArray): Boolean {
        if (header.size < 16) return false
        for (i in 0 until 16) {
            if (header[i] != SQLITE_HEADER[i]) return false
        }
        return true
    }

    fun isJsonHeader(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        val str = String(bytes, Charsets.UTF_8).trimStart()
        return str.startsWith("{")
    }

    /**
     * Resolves companion pair indices (packIndex, manifestIndex) based on file names.
     */
    fun resolveByName(name1: String, name2: String): Pair<Int, Int>? {
        val n1 = name1.lowercase()
        val n2 = name2.lowercase()
        if (n1.endsWith(".json") && (n2.endsWith(".vupack") || !n2.endsWith(".json"))) {
            return Pair(1, 0)
        }
        if (n2.endsWith(".json") && (n1.endsWith(".vupack") || !n1.endsWith(".json"))) {
            return Pair(0, 1)
        }
        return null
    }

    /**
     * Resolves companion pair indices (packIndex, manifestIndex) based on probe header bytes.
     */
    fun resolveByHeaders(header1: ByteArray, header2: ByteArray): Pair<Int, Int>? {
        val is1Sqlite = isSqliteHeader(header1)
        val is2Sqlite = isSqliteHeader(header2)
        val is1Json = isJsonHeader(header1)
        val is2Json = isJsonHeader(header2)

        if (is1Sqlite && is2Json) return Pair(0, 1)
        if (is2Sqlite && is1Json) return Pair(1, 0)
        return null
    }

    /**
     * Resolves which URI is the .vupack SQLite database and which is the companion JSON manifest.
     * Returns Pair(packUri, manifestUri) or null if resolution fails.
     */
    fun resolveCompanionPair(contentResolver: ContentResolver, uris: List<Uri>): Pair<Uri, Uri>? {
        if (uris.size != 2) return null
        val u1 = uris[0]
        val u2 = uris[1]

        val name1 = queryDisplayName(contentResolver, u1) ?: ""
        val name2 = queryDisplayName(contentResolver, u2) ?: ""

        val byName = resolveByName(name1, name2)
        if (byName != null) {
            return Pair(uris[byName.first], uris[byName.second])
        }

        // Fallback: probe headers directly
        val h1 = readProbeBytes(contentResolver, u1)
        val h2 = readProbeBytes(contentResolver, u2)
        val byHeader = resolveByHeaders(h1, h2)
        if (byHeader != null) {
            return Pair(uris[byHeader.first], uris[byHeader.second])
        }

        return null
    }

    /**
     * Imports companion pack and manifest URIs using SAF stream access.
     * All ContentResolver metadata queries, stream probe reading, and copying
     * are executed under [ioDispatcher] to protect the UI thread.
     */
    suspend fun importPackFromUris(
        context: Context,
        manager: ActiveSignPackManager,
        uris: List<Uri>,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    ): PackImportResult = withContext(ioDispatcher) {
        if (uris.isEmpty()) {
            return@withContext PackImportResult.Failure(
                reason = "Người dùng đã hủy chọn tệp",
                errors = listOf("SELECTION_CANCELLED"),
                errorCode = PackImportResult.Failure.ErrorCode.CANCELLED
            )
        }
        if (uris.size != 2) {
            return@withContext PackImportResult.Failure(
                reason = "Cần chọn đúng 2 tệp: một tệp gói .vupack và một tệp .manifest.json (đã chọn ${uris.size} tệp)",
                errors = listOf("SELECTION_COUNT_INVALID:${uris.size}"),
                errorCode = PackImportResult.Failure.ErrorCode.SELECTION_INVALID
            )
        }

        val pair = resolveCompanionPair(context.contentResolver, uris)
            ?: return@withContext PackImportResult.Failure(
                reason = "Không nhận diện được cặp tệp hợp lệ (cần 1 tệp SQLite/vupack và 1 tệp manifest JSON)",
                errors = listOf("COMPANION_PAIR_UNRECOGNIZED"),
                errorCode = PackImportResult.Failure.ErrorCode.SELECTION_INVALID
            )

        val (packUri, manifestUri) = pair

        manager.importPackFromStreams(
            packStreamProvider = {
                context.contentResolver.openInputStream(packUri)
                    ?: throw IOException("Không thể mở luồng đọc tệp gói từ URI")
            },
            manifestStreamProvider = {
                context.contentResolver.openInputStream(manifestUri)
                    ?: throw IOException("Không thể mở luồng đọc manifest từ URI")
            }
        )
    }

    private fun queryDisplayName(contentResolver: ContentResolver, uri: Uri): String? {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun readProbeBytes(contentResolver: ContentResolver, uri: Uri): ByteArray {
        return try {
            contentResolver.openInputStream(uri)?.use { stream ->
                val buf = ByteArray(64)
                val read = stream.read(buf)
                if (read > 0) buf.copyOf(read) else ByteArray(0)
            } ?: ByteArray(0)
        } catch (_: Exception) {
            ByteArray(0)
        }
    }
}
