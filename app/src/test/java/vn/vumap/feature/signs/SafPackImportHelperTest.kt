package vn.vumap.feature.signs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SafPackImportHelperTest {

    @Test
    fun `detects sqlite header correctly`() {
        val sqliteHeader = byteArrayOf(
            0x53, 0x51, 0x4c, 0x69, 0x74, 0x65, 0x20, 0x66,
            0x6f, 0x72, 0x6d, 0x61, 0x74, 0x20, 0x33, 0x00,
            0x10, 0x00
        )
        assertTrue(SafPackImportHelper.isSqliteHeader(sqliteHeader))

        val badHeader = byteArrayOf(0x00, 0x01, 0x02)
        assertFalse(SafPackImportHelper.isSqliteHeader(badHeader))
    }

    @Test
    fun `detects json header correctly`() {
        val json = "  \n  {\n\"format\": \"vupack\"\n}".toByteArray(Charsets.UTF_8)
        assertTrue(SafPackImportHelper.isJsonHeader(json))

        val nonJson = "BINARY DATA".toByteArray(Charsets.UTF_8)
        assertFalse(SafPackImportHelper.isJsonHeader(nonJson))
    }

    @Test
    fun `resolves pair by filename extension`() {
        // Pack first, manifest second
        val pair1 = SafPackImportHelper.resolveByName("custom.vupack", "custom.vupack.manifest.json")
        assertNotNull(pair1)
        assertEquals(0, pair1!!.first)
        assertEquals(1, pair1.second)

        // Manifest first, pack second
        val pair2 = SafPackImportHelper.resolveByName("custom.vupack.manifest.json", "custom.vupack")
        assertNotNull(pair2)
        assertEquals(1, pair2!!.first)
        assertEquals(0, pair2.second)

        // Two manifests or two non-manifests
        assertNull(SafPackImportHelper.resolveByName("a.json", "b.json"))
        assertNull(SafPackImportHelper.resolveByName("a.vupack", "b.vupack"))
    }

    @Test
    fun `resolves pair by probe headers`() {
        val sqlite = SafPackImportHelper.SQLITE_HEADER
        val json = "{\n  \"format\": \"vupack\"\n}".toByteArray(Charsets.UTF_8)

        // Index 0 sqlite, Index 1 json
        val pair1 = SafPackImportHelper.resolveByHeaders(sqlite, json)
        assertNotNull(pair1)
        assertEquals(0, pair1!!.first)
        assertEquals(1, pair1.second)

        // Index 0 json, Index 1 sqlite
        val pair2 = SafPackImportHelper.resolveByHeaders(json, sqlite)
        assertNotNull(pair2)
        assertEquals(1, pair2!!.first)
        assertEquals(0, pair2.second)

        // Two json files
        assertNull(SafPackImportHelper.resolveByHeaders(json, json))
        // Two sqlite files
        assertNull(SafPackImportHelper.resolveByHeaders(sqlite, sqlite))
    }
}
