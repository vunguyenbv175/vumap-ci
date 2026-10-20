package vn.vumap.feature.signs

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import vn.vumap.feature.signs.db.AndroidSignPackDbFactory
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale

/**
 * Loads the bundled offline sign pack packaged in the APK assets.
 *
 * Copies the read-only `.vupack` asset to app-internal storage on demand
 * if missing, corrupted, or stale (verified by SHA-256 and byte length), and
 * provides a ready [OfflineSignRepository].
 *
 * Full SAF import ([android.content.Intent.ACTION_OPEN_DOCUMENT]) and atomic multi-pack
 * updates are explicitly deferred to future slices.
 */
object BundledOfflinePackLoader {

    const val BUNDLED_PACK_ASSET = "signs/synthetic_vn_signs_p1.vupack"
    const val BUNDLED_MANIFEST_ASSET = "signs/synthetic_vn_signs_p1.vupack.manifest.json"

    suspend fun loadBundledPack(
        context: Context,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    ): OfflineSignRepository = withContext(ioDispatcher) {
        val manifestJson = try {
            context.assets.open(BUNDLED_MANIFEST_ASSET).use { input ->
                input.bufferedReader(Charsets.UTF_8).readText()
            }
        } catch (_: Exception) {
            null
        }

        val destDir = File(context.filesDir, "signs").apply { mkdirs() }
        val destFile = File(destDir, "synthetic_vn_signs_p1.vupack")

        val expectedSha = try {
            manifestJson?.let { JSONObject(it).optString("sha256", "") } ?: ""
        } catch (_: Exception) {
            ""
        }
        val expectedBytes = try {
            manifestJson?.let { JSONObject(it).optLong("packBytes", -1L) } ?: -1L
        } catch (_: Exception) {
            -1L
        }

        try {
            syncAssetToFile(
                assetStreamProvider = { context.assets.open(BUNDLED_PACK_ASSET) },
                destFile = destFile,
                expectedSha256 = expectedSha,
                expectedBytes = expectedBytes
            )
        } catch (_: Exception) {
            // If asset sync fails, repository initialization will safely report Unavailable
        }

        val repository = OfflineSignRepository(
            packFile = destFile,
            manifestJson = manifestJson,
            dbFactory = AndroidSignPackDbFactory(),
            ioDispatcher = ioDispatcher,
            syntheticOnly = true
        )
        repository.initialize()
        repository
    }

    /**
     * Creates an [ActiveSignPackManager] wired to app storage and Android SQLite DB factory.
     */
    fun createActiveSignPackManager(
        context: Context,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    ): ActiveSignPackManager {
        val appContext = context.applicationContext
        val signsDir = File(appContext.filesDir, "signs")
        val storageManager = OfflinePackStorageManager(
            baseDir = signsDir,
            dbFactory = AndroidSignPackDbFactory(),
            syntheticOnly = true
        )
        return ActiveSignPackManager(
            storageManager = storageManager,
            dbFactory = AndroidSignPackDbFactory(),
            bundledPackProvider = { loadBundledPack(appContext, ioDispatcher) },
            ioDispatcher = ioDispatcher,
            syntheticOnly = true
        )
    }

    /**
     * Determines whether [destFile] is missing, corrupted, or stale relative to
     * [expectedSha256] and [expectedBytes].
     */
    fun shouldRefresh(destFile: File, expectedSha256: String, expectedBytes: Long = -1L): Boolean {
        if (!destFile.exists() || !destFile.isFile) return true
        val length = destFile.length()
        if (length <= 0) return true
        if (expectedBytes > 0 && length != expectedBytes) return true
        if (expectedSha256.isNotBlank()) {
            try {
                val actualSha = computeSha256(destFile)
                if (!actualSha.equals(expectedSha256, ignoreCase = true)) {
                    return true
                }
            } catch (_: Exception) {
                return true
            }
        }
        return false
    }

    /**
     * Copies the asset stream to [destFile] atomically via a temporary file if
     * [shouldRefresh] returns true.
     *
     * Returns true if a refresh was performed, false if the file was already up-to-date.
     */
    fun syncAssetToFile(
        assetStreamProvider: () -> InputStream,
        destFile: File,
        expectedSha256: String,
        expectedBytes: Long = -1L
    ): Boolean {
        if (shouldRefresh(destFile, expectedSha256, expectedBytes)) {
            val parent = destFile.parentFile
            if (parent != null && !parent.exists()) {
                parent.mkdirs()
            }
            assetStreamProvider().use { input ->
                copyStreamToFile(input, destFile)
            }
            return true
        }
        return false
    }

    fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(32768)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        val hash = digest.digest()
        val sb = StringBuilder(hash.size * 2)
        for (b in hash) {
            sb.append(String.format(Locale.ROOT, "%02x", b))
        }
        return sb.toString()
    }

    fun copyStreamToFile(input: InputStream, target: File) {
        val temp = File(target.parentFile, target.name + ".tmp")
        try {
            FileOutputStream(temp).use { output ->
                val buffer = ByteArray(32768)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                }
                output.flush()
            }
            if (target.exists()) {
                target.delete()
            }
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
        } finally {
            if (temp.exists()) {
                temp.delete()
            }
        }
    }
}
