package vn.vumap.feature.signs

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.Locale

/**
 * Tests host pipeline determinism and byte consistency of the committed synthetic `.vupack`.
 */
class PackGenerationDeterminismTest {

    private val projectRoot: File = findProjectRoot()
    private val assetPackFile: File = File(projectRoot, "app/src/main/assets/signs/synthetic_vn_signs_p1.vupack")
    private val assetManifestFile: File = File(projectRoot, "app/src/main/assets/signs/synthetic_vn_signs_p1.vupack.manifest.json")
    private val fixtureFile: File = File(projectRoot, "feature/signs/fixtures/synthetic_vn_signs_p1.json")

    @Test
    fun committedAssetMatchesManifest() {
        assertTrue("Asset pack must exist: ${assetPackFile.absolutePath}", assetPackFile.exists())
        assertTrue("Asset manifest must exist: ${assetManifestFile.absolutePath}", assetManifestFile.exists())

        val manifestJson = assetManifestFile.readText(Charsets.UTF_8)
        val manifest = JSONObject(manifestJson)

        assertEquals("vupack", manifest.getString("format"))
        assertEquals(1, manifest.getInt("formatVersion"))
        assertEquals(1, manifest.getInt("schemaVersion"))
        assertEquals(true, manifest.getBoolean("synthetic"))
        assertEquals("synthetic-vumap-p1", manifest.getString("source"))

        val declaredBytes = manifest.getLong("packBytes")
        assertEquals(declaredBytes, assetPackFile.length())

        val declaredSha = manifest.getString("sha256")
        val actualSha = computeSha256(assetPackFile)
        assertEquals(declaredSha.lowercase(Locale.ROOT), actualSha.lowercase(Locale.ROOT))
    }

    @Test
    fun hostBuilderProducesByteIdenticalOutputWhenPythonAvailable() {
        val pythonCmd = findPythonCommand() ?: return // Skip if python is not on PATH in this environment

        val tempOut1 = File.createTempFile("rebuild_pack1", ".vupack")
        val tempManifest1 = File(tempOut1.parentFile, tempOut1.name + ".manifest.json")
        val tempOut2 = File.createTempFile("rebuild_pack2", ".vupack")
        val tempManifest2 = File(tempOut2.parentFile, tempOut2.name + ".manifest.json")
        try {
            val script = File(projectRoot, "feature/signs/tools/pack/pack_build.py")

            val pb1 = ProcessBuilder(
                pythonCmd,
                script.absolutePath,
                "--fixture", fixtureFile.absolutePath,
                "--out", tempOut1.absolutePath,
                "--manifest", tempManifest1.absolutePath
            ).directory(projectRoot)
            val exitCode1 = pb1.start().waitFor()
            assertEquals("pack_build.py (run 1) should exit with code 0", 0, exitCode1)

            val pb2 = ProcessBuilder(
                pythonCmd,
                script.absolutePath,
                "--fixture", fixtureFile.absolutePath,
                "--out", tempOut2.absolutePath,
                "--manifest", tempManifest2.absolutePath
            ).directory(projectRoot)
            val exitCode2 = pb2.start().waitFor()
            assertEquals("pack_build.py (run 2) should exit with code 0", 0, exitCode2)

            val sha1 = computeSha256(tempOut1)
            val sha2 = computeSha256(tempOut2)
            assertEquals("Repeated host pack builds must be bit-for-bit identical", sha1, sha2)
            assertEquals("Repeated host pack builds must have identical byte length", tempOut1.length(), tempOut2.length())

            val manifest1 = JSONObject(tempManifest1.readText(Charsets.UTF_8))
            val manifest2 = JSONObject(tempManifest2.readText(Charsets.UTF_8))
            assertEquals(manifest1.getString("sha256"), manifest2.getString("sha256"))
            assertEquals(manifest1.getString("contentSha256"), manifest2.getString("contentSha256"))

            // Verify canonical content digest matches committed pack content digest
            val committedManifest = JSONObject(assetManifestFile.readText(Charsets.UTF_8))
            assertEquals(
                "Canonical content digest must match across SQLite/library versions",
                committedManifest.getString("contentSha256"),
                manifest1.getString("contentSha256")
            )
        } finally {
            tempOut1.delete()
            tempManifest1.delete()
            tempOut2.delete()
            tempManifest2.delete()
        }
    }

    private fun computeSha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(16384)
            var read: Int
            while (input.read(buf).also { read = it } != -1) {
                md.update(buf, 0, read)
            }
        }
        val bytes = md.digest()
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(String.format(Locale.ROOT, "%02x", b))
        }
        return sb.toString()
    }

    private fun findPythonCommand(): String? {
        for (cmd in listOf("python", "python3")) {
            try {
                val proc = ProcessBuilder(cmd, "--version").start()
                if (proc.waitFor() == 0) return cmd
            } catch (_: Exception) {
            }
        }
        return null
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
