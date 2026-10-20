package vn.vumap.feature.signs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Locale

class BundledOfflinePackLoaderTest {

    @Test
    fun refreshesStalePackWhenByteLengthIsIdentical() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "vumap_test_loader_" + System.nanoTime()).apply { mkdirs() }
        val destFile = File(tempDir, "synthetic_vn_signs_p1.vupack")

        try {
            // Pack A and Pack B have the exact same size (28672 bytes) but different bytes/checksum
            val size = 28672
            val packA = ByteArray(size) { 0x11.toByte() }
            val packB = ByteArray(size) { 0x22.toByte() }

            val shaA = computeSha256Bytes(packA)
            val shaB = computeSha256Bytes(packB)
            assertFalse("Packs must have distinct SHA-256", shaA.equals(shaB, ignoreCase = true))

            // Initial state: destFile contains pack A
            destFile.writeBytes(packA)
            assertEquals(size.toLong(), destFile.length())
            assertEquals(shaA, BundledOfflinePackLoader.computeSha256(destFile))

            // Bundled asset has been updated to Pack B (same packBytes, new sha256)
            assertTrue(
                "shouldRefresh must detect checksum mismatch even when length is identical",
                BundledOfflinePackLoader.shouldRefresh(destFile, expectedSha256 = shaB, expectedBytes = size.toLong())
            )

            val refreshed = BundledOfflinePackLoader.syncAssetToFile(
                assetStreamProvider = { ByteArrayInputStream(packB) },
                destFile = destFile,
                expectedSha256 = shaB,
                expectedBytes = size.toLong()
            )

            assertTrue("syncAssetToFile must report that a refresh occurred", refreshed)
            assertEquals("destFile must now match pack B length", size.toLong(), destFile.length())
            assertEquals("destFile must now match pack B checksum", shaB, BundledOfflinePackLoader.computeSha256(destFile))

            // Calling sync again when up-to-date must not rewrite the file
            assertFalse(
                "shouldRefresh must return false when checksum already matches",
                BundledOfflinePackLoader.shouldRefresh(destFile, expectedSha256 = shaB, expectedBytes = size.toLong())
            )
            assertFalse(
                "syncAssetToFile must return false when file is already up to date",
                BundledOfflinePackLoader.syncAssetToFile(
                    assetStreamProvider = { ByteArrayInputStream(packB) },
                    destFile = destFile,
                    expectedSha256 = shaB,
                    expectedBytes = size.toLong()
                )
            )
        } finally {
            destFile.delete()
            tempDir.delete()
        }
    }

    @Test
    fun refreshesCorruptedPackWithSameByteLength() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "vumap_test_loader_corrupt_" + System.nanoTime()).apply { mkdirs() }
        val destFile = File(tempDir, "synthetic_vn_signs_p1.vupack")

        try {
            val size = 28672
            val original = ByteArray(size) { (it % 256).toByte() }
            val expectedSha = computeSha256Bytes(original)

            destFile.writeBytes(original)
            assertEquals(expectedSha, BundledOfflinePackLoader.computeSha256(destFile))

            // Corrupt 1 byte in-place without changing byte length
            RandomAccessFile(destFile, "rw").use { raf ->
                raf.seek(500)
                raf.writeByte(0xFF)
            }
            assertEquals(size.toLong(), destFile.length())
            assertFalse(expectedSha.equals(BundledOfflinePackLoader.computeSha256(destFile), ignoreCase = true))

            assertTrue(
                "shouldRefresh must detect same-length corrupted file",
                BundledOfflinePackLoader.shouldRefresh(destFile, expectedSha256 = expectedSha, expectedBytes = size.toLong())
            )

            val refreshed = BundledOfflinePackLoader.syncAssetToFile(
                assetStreamProvider = { ByteArrayInputStream(original) },
                destFile = destFile,
                expectedSha256 = expectedSha,
                expectedBytes = size.toLong()
            )

            assertTrue("syncAssetToFile must repair the corrupted file", refreshed)
            assertEquals(expectedSha, BundledOfflinePackLoader.computeSha256(destFile))
        } finally {
            destFile.delete()
            tempDir.delete()
        }
    }

    @Test
    fun shouldRefreshReturnsTrueWhenMissingOrEmpty() {
        val missing = File(System.getProperty("java.io.tmpdir"), "missing_" + System.nanoTime())
        assertTrue("Missing file must refresh", BundledOfflinePackLoader.shouldRefresh(missing, "abc", 100))

        val empty = File.createTempFile("empty_pack", ".vupack")
        try {
            assertTrue("Empty file must refresh", BundledOfflinePackLoader.shouldRefresh(empty, "abc", 100))
        } finally {
            empty.delete()
        }
    }

    private fun computeSha256Bytes(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(bytes)
        val sb = StringBuilder(hash.size * 2)
        for (b in hash) {
            sb.append(String.format(Locale.ROOT, "%02x", b))
        }
        return sb.toString()
    }
}
