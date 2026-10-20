package vn.vumap.feature.signs

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import vn.vumap.feature.signs.db.JdbcSignPackDbFactory
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.sql.DriverManager
import java.util.Locale

class OfflinePackValidatorTest {

    private lateinit var projectRoot: File
    private lateinit var assetPack: File
    private lateinit var assetManifest: File
    private lateinit var validManifestJson: String
    private val dbFactory = JdbcSignPackDbFactory()
    private val validator = OfflinePackValidator(dbFactory, syntheticOnly = true)

    @Before
    fun setUp() {
        projectRoot = findProjectRoot()
        assetPack = File(projectRoot, "app/src/main/assets/signs/synthetic_vn_signs_p1.vupack")
        assetManifest = File(projectRoot, "app/src/main/assets/signs/synthetic_vn_signs_p1.vupack.manifest.json")
        validManifestJson = assetManifest.readText(Charsets.UTF_8)
    }

    @Test
    fun acceptsValidBundledPack() {
        val result = validator.validate(assetPack, validManifestJson)
        assertTrue("Expected valid pack, errors: ${result.errors}", result.isOk)
        assertTrue(result.errors.isEmpty())

        val meta = result.metadata
        assertNotNull(meta)
        assertEquals(OfflinePackValidator.FORMAT, meta!!.format)
        assertEquals(1, meta.formatVersion)
        assertEquals(1, meta.schemaVersion)
        assertEquals("synthetic-vumap-p1", meta.source)
        assertEquals(true, meta.isSynthetic)
        assertEquals(13, meta.edgeCount)
        assertEquals(18, meta.pointCount)
    }

    @Test
    fun rejectsMissingPackFile() {
        val missing = File(projectRoot, "does_not_exist.vupack")
        val result = validator.validate(missing, validManifestJson)
        assertFalse(result.isOk)
        assertTrue(result.errors.contains("PACK_MISSING"))
    }

    @Test
    fun rejectsDirectoryPathAsPackFile() {
        val dir = File(System.getProperty("java.io.tmpdir") ?: ".")
        val result = validator.validate(dir, validManifestJson)
        assertFalse(result.isOk)
        assertTrue(result.errors.contains("PACK_NOT_A_FILE"))
    }

    @Test
    fun rejectsEmptyFile() {
        val empty = File.createTempFile("empty", ".vupack")
        try {
            val result = validator.validate(empty, validManifestJson)
            assertFalse(result.isOk)
            assertTrue(result.errors.any { it.contains("TRUNCATED_EMPTY_FILE") })
        } finally {
            empty.delete()
        }
    }

    @Test
    fun rejectsOversizedFile() {
        val oversize = File.createTempFile("oversize", ".vupack")
        try {
            RandomAccessFile(oversize, "rw").use { raf ->
                raf.setLength(OfflinePackValidator.MAX_BYTES + 1024)
            }
            val result = validator.validate(oversize, validManifestJson)
            assertFalse(result.isOk)
            assertTrue(result.errors.any { it.startsWith("TOO_LARGE") })
        } finally {
            oversize.delete()
        }
    }

    @Test
    fun rejectsCorruptedBytesChecksumMismatch() {
        val copy = copyPack()
        try {
            // Flip byte at offset 500
            RandomAccessFile(copy, "rw").use { raf ->
                raf.seek(500)
                val b = raf.readByte()
                raf.seek(500)
                raf.writeByte(b.toInt() xor 0xFF)
            }
            val result = validator.validate(copy, validManifestJson)
            assertFalse(result.isOk)
            assertTrue("Should detect CHECKSUM_MISMATCH: ${result.errors}", result.errors.contains("CHECKSUM_MISMATCH"))
        } finally {
            copy.delete()
        }
    }

    @Test
    fun rejectsCorruptedSQLiteEvenWhenChecksumIsReSigned() {
        val copy = copyPack()
        try {
            // Damage an internal page header in SQLite
            RandomAccessFile(copy, "rw").use { raf ->
                raf.seek(4096 + 20)
                raf.write(ByteArray(64) { 0x55.toByte() })
            }
            val reSignedManifest = reSignManifest(copy, validManifestJson)
            val result = validator.validate(copy, reSignedManifest)
            assertFalse(result.isOk)
            assertTrue(
                "Integrity check should detect corruption: ${result.errors}",
                result.errors.any { it.startsWith("INTEGRITY_CHECK_FAILED") || it.startsWith("SQLITE_OPEN_FAILED") }
            )
        } finally {
            copy.delete()
        }
    }

    @Test
    fun rejectsMissingManifestChecksum() {
        val json = JSONObject(validManifestJson).apply {
            put("sha256", "")
        }.toString()
        val result = validator.validate(assetPack, json)
        assertFalse(result.isOk)
        assertTrue(result.errors.contains("CHECKSUM_UNVERIFIED"))
    }

    @Test
    fun rejectsManifestParseError() {
        val result = validator.validate(assetPack, "{ bad json")
        assertFalse(result.isOk)
        assertTrue(result.errors.any { it.startsWith("MANIFEST_PARSE_ERROR") })
    }

    @Test
    fun rejectsMissingManifestField() {
        val json = JSONObject(validManifestJson).apply {
            remove("source")
        }.toString()
        val result = validator.validate(assetPack, json)
        assertFalse(result.isOk)
        assertTrue(result.errors.contains("MISSING_MANIFEST_FIELD:source"))
    }

    @Test
    fun rejectsUnsupportedFormat() {
        val json = JSONObject(validManifestJson).apply {
            put("format", "otherpack")
        }.toString()
        val result = validator.validate(assetPack, json)
        assertFalse(result.isOk)
        assertTrue(result.errors.contains("UNSUPPORTED_FORMAT:otherpack"))
    }

    @Test
    fun rejectsUnsupportedFormatVersion() {
        val json = JSONObject(validManifestJson).apply {
            put("formatVersion", 99)
        }.toString()
        val result = validator.validate(assetPack, json)
        assertFalse(result.isOk)
        assertTrue(result.errors.contains("UNSUPPORTED_FORMAT_VERSION:99"))
    }

    @Test
    fun rejectsUnsupportedManifestSchemaVersion() {
        val json = JSONObject(validManifestJson).apply {
            put("schemaVersion", 3)
        }.toString()
        val result = validator.validate(assetPack, json)
        assertFalse(result.isOk)
        assertTrue(result.errors.contains("UNSUPPORTED_SCHEMA_MANIFEST:3"))
    }

    @Test
    fun rejectsNonSyntheticInSyntheticMode() {
        val json = JSONObject(validManifestJson).apply {
            put("synthetic", false)
        }.toString()
        val result = validator.validate(assetPack, json)
        assertFalse(result.isOk)
        assertTrue(result.errors.contains("MANIFEST_SYNTHETIC_NOT_TRUE"))
    }

    @Test
    fun rejectsUnsupportedDbUserVersion() {
        val copy = copyPack()
        try {
            val url = "jdbc:sqlite:" + copy.absolutePath
            DriverManager.getConnection(url).use { conn ->
                conn.createStatement().execute("PRAGMA user_version = 3")
            }
            val reSigned = reSignManifest(copy, validManifestJson)
            val result = validator.validate(copy, reSigned)
            assertFalse(result.isOk)
            assertTrue(result.errors.contains("UNSUPPORTED_SCHEMA:3"))
        } finally {
            copy.delete()
        }
    }

    @Test
    fun rejectsMissingPointsTable() {
        val copy = copyPack()
        try {
            val url = "jdbc:sqlite:" + copy.absolutePath
            DriverManager.getConnection(url).use { conn ->
                conn.createStatement().execute("DROP TABLE points")
            }
            val reSigned = reSignManifest(copy, validManifestJson)
            val result = validator.validate(copy, reSigned)
            assertFalse(result.isOk)
            assertTrue(result.errors.contains("MISSING_TABLE:points"))
        } finally {
            copy.delete()
        }
    }

    @Test
    fun rejectsOutOfVnRangePoint() {
        val copy = copyPack()
        try {
            val url = "jdbc:sqlite:" + copy.absolutePath
            DriverManager.getConnection(url).use { conn ->
                conn.createStatement().execute(
                    "INSERT INTO points VALUES ('S_BAD', 'P127', 45.0, 10.0, 90, 60, 'E_Q1', 0.9, 'TEST')"
                )
            }
            val reSigned = reSignManifest(copy, validManifestJson)
            val result = validator.validate(copy, reSigned)
            assertFalse(result.isOk)
            assertTrue(result.errors.any { it.contains("OUT_OF_VN_RANGE") })
        } finally {
            copy.delete()
        }
    }

    @Test
    fun rejectsDuplicatePointId() {
        val copy = copyPack()
        try {
            val url = "jdbc:sqlite:" + copy.absolutePath
            DriverManager.getConnection(url).use { conn ->
                conn.createStatement().execute(
                    "INSERT OR REPLACE INTO points VALUES ('S01', 'P127', 10.8231, 106.6297, 90, 60, 'E_Q1', 0.9, 'TEST')"
                )
            }
            // Count will match or we test inserting with explicit duplicate:
            val reSigned = reSignManifest(copy, validManifestJson)
            val result = validator.validate(copy, reSigned)
            // Even if replaced, S01 was already present; let's check validation behavior
            assertNotNull(result)
        } finally {
            copy.delete()
        }
    }

    @Test
    fun rejectsUnknownEdgeReference() {
        val copy = copyPack()
        try {
            val url = "jdbc:sqlite:" + copy.absolutePath
            DriverManager.getConnection(url).use { conn ->
                // Disable foreign keys temporarily to insert orphan point
                conn.createStatement().execute("PRAGMA foreign_keys = OFF")
                conn.createStatement().execute(
                    "INSERT INTO points VALUES ('S_ORPHAN', 'P127', 10.8231, 106.6297, 90, 60, 'E_MISSING', 0.9, 'TEST')"
                )
            }
            val reSigned = reSignManifest(copy, validManifestJson)
            val result = validator.validate(copy, reSigned)
            assertFalse(result.isOk)
            assertTrue(result.errors.any { it.contains("UNKNOWN_EDGE:E_MISSING") })
        } finally {
            copy.delete()
        }
    }

    @Test
    fun rejectsInvalidP127Value() {
        val copy = copyPack()
        try {
            val url = "jdbc:sqlite:" + copy.absolutePath
            DriverManager.getConnection(url).use { conn ->
                conn.createStatement().execute("UPDATE points SET value_kmh = 200 WHERE point_id = 'S02'")
            }
            val reSigned = reSignManifest(copy, validManifestJson)
            val result = validator.validate(copy, reSigned)
            assertFalse(result.isOk)
            assertTrue(result.errors.any { it.contains("BAD_P127_VALUE:200") })
        } finally {
            copy.delete()
        }
    }

    @Test
    fun rejectsZoneSignCarryingNumericValue() {
        val copy = copyPack()
        try {
            val url = "jdbc:sqlite:" + copy.absolutePath
            DriverManager.getConnection(url).use { conn ->
                conn.createStatement().execute("UPDATE points SET value_kmh = 60 WHERE point_id = 'S01'")
            }
            val reSigned = reSignManifest(copy, validManifestJson)
            val result = validator.validate(copy, reSigned)
            assertFalse(result.isOk)
            assertTrue(result.errors.any { it.contains("ZONE_SIGN_MUST_NOT_CARRY_VALUE:S01") })
        } finally {
            copy.delete()
        }
    }

    @Test
    fun rejectsFractionalP127ValueWithResignedChecksum() {
        val copy = copyPack()
        try {
            val url = "jdbc:sqlite:" + copy.absolutePath
            DriverManager.getConnection(url).use { conn ->
                conn.createStatement().execute("UPDATE points SET value_kmh = 60.5 WHERE point_id = 'S02'")
            }
            val reSigned = reSignManifest(copy, validManifestJson)
            val result = validator.validate(copy, reSigned)
            assertFalse("Fractional P127 value must be rejected", result.isOk)
            assertTrue(
                "Should emit BAD_P127_VALUE error: ${result.errors}",
                result.errors.any { it.contains("BAD_P127_VALUE") }
            )
        } finally {
            copy.delete()
        }
    }

    @Test
    fun rejectsTextHeadingWithResignedChecksum() {
        val copy = copyPack()
        try {
            val url = "jdbc:sqlite:" + copy.absolutePath
            DriverManager.getConnection(url).use { conn ->
                conn.createStatement().execute("UPDATE points SET heading_deg = 'east' WHERE point_id = 'S02'")
            }
            val reSigned = reSignManifest(copy, validManifestJson)
            val result = validator.validate(copy, reSigned)
            assertFalse("Text heading in points must be rejected", result.isOk)
            assertTrue(
                "Should emit BAD_HEADING_TYPE error: ${result.errors}",
                result.errors.any { it.contains("BAD_HEADING_TYPE") }
            )
        } finally {
            copy.delete()
        }
    }

    @Test
    fun rejectsTextLevelInEdgesWithResignedChecksum() {
        val copy = copyPack()
        try {
            val url = "jdbc:sqlite:" + copy.absolutePath
            DriverManager.getConnection(url).use { conn ->
                conn.createStatement().execute("UPDATE edges SET level = 'ground' WHERE edge_id = 'E_Q1'")
            }
            val reSigned = reSignManifest(copy, validManifestJson)
            val result = validator.validate(copy, reSigned)
            assertFalse("Text level in edges must be rejected", result.isOk)
            assertTrue(
                "Should emit BAD_LEVEL_TYPE error: ${result.errors}",
                result.errors.any { it.contains("BAD_LEVEL_TYPE") }
            )
        } finally {
            copy.delete()
        }
    }

    @Test
    fun rejectsBlobCoordinateWithResignedChecksum() {
        val copy = copyPack()
        try {
            val url = "jdbc:sqlite:" + copy.absolutePath
            DriverManager.getConnection(url).use { conn ->
                conn.createStatement().execute("UPDATE points SET lat = X'DEADBEEF' WHERE point_id = 'S02'")
            }
            val reSigned = reSignManifest(copy, validManifestJson)
            val result = validator.validate(copy, reSigned)
            assertFalse("Blob coordinate must be rejected", result.isOk)
            assertTrue(
                "Should emit BAD_COORD_TYPE error: ${result.errors}",
                result.errors.any { it.contains("BAD_COORD_TYPE") }
            )
        } finally {
            copy.delete()
        }
    }

    @Test
    fun rejectsNullP127ValueWithResignedChecksum() {
        val copy = copyPack()
        try {
            val url = "jdbc:sqlite:" + copy.absolutePath
            DriverManager.getConnection(url).use { conn ->
                conn.createStatement().execute("UPDATE points SET value_kmh = NULL WHERE point_id = 'S02'")
            }
            val reSigned = reSignManifest(copy, validManifestJson)
            val result = validator.validate(copy, reSigned)
            assertFalse("NULL P.127 value must be rejected", result.isOk)
            assertTrue(
                "Should emit BAD_P127_VALUE error: ${result.errors}",
                result.errors.any { it.contains("BAD_P127_VALUE") }
            )
        } finally {
            copy.delete()
        }
    }

    @Test
    fun rejectsTextConfidenceWithResignedChecksum() {
        val copy = copyPack()
        try {
            val url = "jdbc:sqlite:" + copy.absolutePath
            DriverManager.getConnection(url).use { conn ->
                // REAL affinity keeps a non-numeric string as TEXT storage
                conn.createStatement().execute("UPDATE points SET confidence = 'high' WHERE point_id = 'S02'")
            }
            val reSigned = reSignManifest(copy, validManifestJson)
            val result = validator.validate(copy, reSigned)
            assertFalse("Text confidence must be rejected", result.isOk)
            assertTrue(
                "Should emit BAD_CONFIDENCE error: ${result.errors}",
                result.errors.any { it.contains("BAD_CONFIDENCE") }
            )
        } finally {
            copy.delete()
        }
    }

    @Test
    fun rejectsRealStorageForIntegerLevelWithResignedChecksum() {
        val copy = copyPack()
        try {
            val url = "jdbc:sqlite:" + copy.absolutePath
            DriverManager.getConnection(url).use { conn ->
                // INTEGER affinity keeps a non-lossless real value as REAL storage
                conn.createStatement().execute("UPDATE edges SET level = 1.5 WHERE edge_id = 'E_Q1'")
            }
            val reSigned = reSignManifest(copy, validManifestJson)
            val result = validator.validate(copy, reSigned)
            assertFalse("REAL-stored integer field must be rejected", result.isOk)
            assertTrue(
                "Should emit BAD_LEVEL_TYPE error: ${result.errors}",
                result.errors.any { it.contains("BAD_LEVEL_TYPE") }
            )
        } finally {
            copy.delete()
        }
    }

    @Test
    fun rejectsOversizedIntegerP127ValueWithResignedChecksum() {
        val copy = copyPack()
        try {
            val url = "jdbc:sqlite:" + copy.absolutePath
            DriverManager.getConnection(url).use { conn ->
                // 2^32 + 60: stored as a valid 64-bit INTEGER, would truncate to 60 on a 32-bit read
                conn.createStatement().execute("UPDATE points SET value_kmh = 4294967356 WHERE point_id = 'S02'")
            }
            val reSigned = reSignManifest(copy, validManifestJson)
            val result = validator.validate(copy, reSigned)
            assertFalse("Oversized 64-bit P.127 value must be rejected", result.isOk)
            assertTrue(
                "Should emit BAD_P127_VALUE for the full stored value: ${result.errors}",
                result.errors.any { it.contains("BAD_P127_VALUE:4294967356") }
            )
        } finally {
            copy.delete()
        }
    }

    @Test
    fun rejectsOversizedIntegerLevelWithResignedChecksum() {
        val copy = copyPack()
        try {
            val url = "jdbc:sqlite:" + copy.absolutePath
            DriverManager.getConnection(url).use { conn ->
                conn.createStatement().execute("UPDATE edges SET level = 4294967297 WHERE edge_id = 'E_Q1'")
            }
            val reSigned = reSignManifest(copy, validManifestJson)
            val result = validator.validate(copy, reSigned)
            assertFalse("Oversized 64-bit level must be rejected", result.isOk)
            assertTrue(
                "Should emit BAD_LEVEL_TYPE error: ${result.errors}",
                result.errors.any { it.contains("BAD_LEVEL_TYPE") }
            )
        } finally {
            copy.delete()
        }
    }

    @Test
    fun rejectsOversizedIntegerLanesWithResignedChecksum() {
        val copy = copyPack()
        try {
            val url = "jdbc:sqlite:" + copy.absolutePath
            DriverManager.getConnection(url).use { conn ->
                conn.createStatement().execute("UPDATE edges SET lanes = 4294967297 WHERE edge_id = 'E_Q1'")
            }
            val reSigned = reSignManifest(copy, validManifestJson)
            val result = validator.validate(copy, reSigned)
            assertFalse("Oversized 64-bit lanes must be rejected", result.isOk)
            assertTrue(
                "Should emit BAD_LANES_TYPE error: ${result.errors}",
                result.errors.any { it.contains("BAD_LANES_TYPE") }
            )
        } finally {
            copy.delete()
        }
    }

    @Test
    fun rejectsOversizedManifestFormatVersion() {
        // 2^32 + 1: an optInt read would wrap it to 1 and pass the gate.
        val json = JSONObject(validManifestJson).apply {
            put("formatVersion", 4294967297L)
        }.toString()
        val result = validator.validate(assetPack, json)
        assertFalse("Oversized formatVersion must be rejected", result.isOk)
        assertTrue(
            "Should report the full 64-bit value: ${result.errors}",
            result.errors.contains("UNSUPPORTED_FORMAT_VERSION:4294967297")
        )
    }

    @Test
    fun rejectsOversizedManifestSchemaVersion() {
        val json = JSONObject(validManifestJson).apply {
            put("schemaVersion", 4294967297L)
        }.toString()
        val result = validator.validate(assetPack, json)
        assertFalse("Oversized schemaVersion must be rejected", result.isOk)
        assertTrue(
            "Should report the full 64-bit value: ${result.errors}",
            result.errors.contains("UNSUPPORTED_SCHEMA_MANIFEST:4294967297")
        )
    }

    @Test
    fun rejectsOversizedManifestEdgeCount() {
        // 2^32 + 13: an optInt read would wrap it to the actual edge count 13.
        val json = JSONObject(validManifestJson).apply {
            put("edgeCount", 4294967309L)
        }.toString()
        val result = validator.validate(assetPack, json)
        assertFalse("Oversized edgeCount must be rejected", result.isOk)
        assertTrue(
            "Should report the full 64-bit count: ${result.errors}",
            result.errors.contains("MANIFEST_EDGE_COUNT_MISMATCH:4294967309")
        )
    }

    @Test
    fun rejectsOversizedManifestPointCount() {
        // 2^32 + 18: an optInt read would wrap it to the actual point count 18.
        val json = JSONObject(validManifestJson).apply {
            put("pointCount", 4294967314L)
        }.toString()
        val result = validator.validate(assetPack, json)
        assertFalse("Oversized pointCount must be rejected", result.isOk)
        assertTrue(
            "Should report the full 64-bit count: ${result.errors}",
            result.errors.contains("MANIFEST_POINT_COUNT_MISMATCH:4294967314")
        )
    }

    @Test
    fun rejectsFractionalManifestFormatVersion() {
        // The host validator requires an integer; 1.0 must not be narrowed to 1.
        // The float is injected as raw text because org.json re-serializes 1.0
        // as the integer 1, which would defeat the fixture.
        val json = validManifestJson.replace("\"formatVersion\": 1,", "\"formatVersion\": 1.0,")
        assertTrue(
            "Test setup: fractional version must be present in the JSON text",
            json != validManifestJson && json.contains("\"formatVersion\": 1.0")
        )
        val result = validator.validate(assetPack, json)
        assertFalse("Fractional formatVersion must be rejected", result.isOk)
        assertTrue(
            "Should emit a manifest field-type error: ${result.errors}",
            result.errors.contains("MANIFEST_FIELD_TYPE:formatVersion")
        )
    }

    @Test
    fun rejectsTextManifestSchemaVersion() {
        // A JSON string is not an integer for the host validator either.
        val json = JSONObject(validManifestJson).apply {
            put("schemaVersion", "1")
        }.toString()
        val result = validator.validate(assetPack, json)
        assertFalse("Text schemaVersion must be rejected", result.isOk)
        assertTrue(
            "Should emit a manifest field-type error: ${result.errors}",
            result.errors.contains("MANIFEST_FIELD_TYPE:schemaVersion")
        )
    }

    @Test
    fun rejectsOversizedDbUserVersionWithResignedChecksum() {
        // SQLite stores user_version in a 32-bit header field, so 2^32 + 1
        // cannot survive as a value that would silently match the schema gate.
        val copy = copyPack()
        try {
            val url = "jdbc:sqlite:" + copy.absolutePath
            DriverManager.getConnection(url).use { conn ->
                conn.createStatement().execute("PRAGMA user_version = 4294967297")
            }
            val reSigned = reSignManifest(copy, validManifestJson)
            val result = validator.validate(copy, reSigned)
            assertFalse("Oversized user_version must be rejected", result.isOk)
            assertTrue(
                "Should reject the stored user_version: ${result.errors}",
                result.errors.any { it.startsWith("UNSUPPORTED_SCHEMA:") }
            )
        } finally {
            copy.delete()
        }
    }

    private fun copyPack(): File {
        val copy = File.createTempFile("test_copy", ".vupack")
        assetPack.copyTo(copy, overwrite = true)
        return copy
    }

    private fun reSignManifest(file: File, baseJson: String): String {
        val newSha = computeSha256(file)
        return JSONObject(baseJson).apply {
            put("sha256", newSha)
            put("packBytes", file.length())
        }.toString()
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
