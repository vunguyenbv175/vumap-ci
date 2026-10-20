package vn.vumap.feature.signs

import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.sql.DriverManager
import java.util.Locale

data class TestEdge(
    val edgeId: String,
    val carriageway: String = "DUAL_DIVIDED_OR_ONEWAY_2PLUS",
    val level: Int = 0,
    val roadClass: String = "primary",
    val lanes: Int = 2,
    val headingDeg: Double = 90.0
)

data class TestPoint(
    val pointId: String,
    val type: String, // "R420", "R421", "P127"
    val lat: Double,
    val lon: Double,
    val headingDeg: Double? = 90.0,
    val valueKmh: Int? = null,
    val edgeId: String,
    val confidence: Double = 0.95,
    val source: String = "test-builder"
)

data class TestRoadLimit(
    val roadLimitId: String,
    val edgeId: String,
    val direction: String = "FORWARD",
    val valueKmh: Int? = null,
    val applicability: String = "SUPPORTED",
    val confidence: Double = 0.85,
    val provenance: String = "test-builder"
)

object TestPackBuilder {

    private const val DDL = """
        CREATE TABLE pack_manifest (
            key   TEXT PRIMARY KEY,
            value TEXT NOT NULL
        );
        CREATE TABLE edges (
            edge_id     TEXT PRIMARY KEY,
            carriageway TEXT NOT NULL,
            level       INTEGER NOT NULL,
            road_class  TEXT,
            lanes       INTEGER,
            heading_deg REAL
        );
        CREATE TABLE points (
            point_id    TEXT PRIMARY KEY,
            type        TEXT NOT NULL CHECK (type IN ('R420', 'R421', 'P127')),
            lat         REAL NOT NULL,
            lon         REAL NOT NULL,
            heading_deg REAL,
            value_kmh   INTEGER,
            edge_id     TEXT NOT NULL REFERENCES edges(edge_id),
            confidence  REAL NOT NULL,
            source      TEXT NOT NULL
        );
    """

    private const val ROAD_LIMITS_DDL = """
        CREATE TABLE road_limits (
            road_limit_id TEXT PRIMARY KEY,
            edge_id       TEXT NOT NULL REFERENCES edges(edge_id),
            direction     TEXT NOT NULL CHECK (direction IN ('FORWARD', 'BACKWARD', 'BOTH', 'UNKNOWN')),
            value_kmh     INTEGER,
            applicability TEXT NOT NULL CHECK (applicability IN ('SUPPORTED', 'CONDITIONAL_UNSUPPORTED', 'LANE_UNSUPPORTED', 'VEHICLE_UNSUPPORTED')),
            confidence    REAL NOT NULL,
            provenance    TEXT NOT NULL
        );
    """

    fun buildPack(
        packFile: File,
        manifestFile: File,
        version: String = "1.0-test",
        source: String = "synthetic-test",
        license: String = "ODbL-1.0",
        isSynthetic: Boolean = true,
        edges: List<TestEdge> = listOf(TestEdge(edgeId = "E01")),
        points: List<TestPoint> = listOf(
            TestPoint(pointId = "P01", type = "R420", lat = 10.823, lon = 106.630, edgeId = "E01")
        ),
        roadLimits: List<TestRoadLimit> = emptyList(),
        schemaVersion: Int = if (roadLimits.isEmpty()) 1 else 2,
        formatVersion: Int = 1,
        userVersion: Int = schemaVersion
    ): Pair<File, File> {
        if (packFile.exists()) packFile.delete()
        if (manifestFile.exists()) manifestFile.delete()
        packFile.parentFile?.mkdirs()
        manifestFile.parentFile?.mkdirs()

        val url = "jdbc:sqlite:" + packFile.absolutePath
        DriverManager.getConnection(url).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("PRAGMA page_size = 4096")
                for (statement in DDL.trim().split(";")) {
                    val sql = statement.trim()
                    if (sql.isNotEmpty()) {
                        stmt.execute(sql)
                    }
                }
                if (schemaVersion >= 2) {
                    for (statement in ROAD_LIMITS_DDL.trim().split(";")) {
                        val sql = statement.trim()
                        if (sql.isNotEmpty()) {
                            stmt.execute(sql)
                        }
                    }
                }
                stmt.execute("PRAGMA user_version = $userVersion")
            }

            val manifestRows = mutableListOf(
                "format" to "vupack",
                "formatVersion" to formatVersion.toString(),
                "schemaVersion" to schemaVersion.toString(),
                "source" to source,
                "license" to license,
                "version" to version,
                "synthetic" to if (isSynthetic) "true" else "false",
                "edgeCount" to edges.size.toString(),
                "pointCount" to points.size.toString(),
                "contentSha256" to "0000000000000000000000000000000000000000000000000000000000000000"
            )
            if (schemaVersion >= 2) {
                manifestRows.add("roadLimitCount" to roadLimits.size.toString())
            }

            conn.prepareStatement("INSERT INTO pack_manifest (key, value) VALUES (?, ?)").use { ps ->
                for ((k, v) in manifestRows) {
                    ps.setString(1, k)
                    ps.setString(2, v)
                    ps.executeUpdate()
                }
            }

            conn.prepareStatement(
                "INSERT INTO edges (edge_id, carriageway, level, road_class, lanes, heading_deg) VALUES (?, ?, ?, ?, ?, ?)"
            ).use { ps ->
                for (e in edges) {
                    ps.setString(1, e.edgeId)
                    ps.setString(2, e.carriageway)
                    ps.setInt(3, e.level)
                    ps.setString(4, e.roadClass)
                    ps.setInt(5, e.lanes)
                    ps.setDouble(6, e.headingDeg)
                    ps.executeUpdate()
                }
            }

            conn.prepareStatement(
                "INSERT INTO points (point_id, type, lat, lon, heading_deg, value_kmh, edge_id, confidence, source) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
            ).use { ps ->
                for (p in points) {
                    ps.setString(1, p.pointId)
                    ps.setString(2, p.type)
                    ps.setDouble(3, p.lat)
                    ps.setDouble(4, p.lon)
                    if (p.headingDeg != null) ps.setDouble(5, p.headingDeg) else ps.setNull(5, java.sql.Types.REAL)
                    if (p.valueKmh != null) ps.setInt(6, p.valueKmh) else ps.setNull(6, java.sql.Types.INTEGER)
                    ps.setString(7, p.edgeId)
                    ps.setDouble(8, p.confidence)
                    ps.setString(9, p.source)
                    ps.executeUpdate()
                }
            }

            if (schemaVersion >= 2) {
                conn.prepareStatement(
                    "INSERT INTO road_limits (road_limit_id, edge_id, direction, value_kmh, applicability, confidence, provenance) VALUES (?, ?, ?, ?, ?, ?, ?)"
                ).use { ps ->
                    for (limit in roadLimits) {
                        ps.setString(1, limit.roadLimitId)
                        ps.setString(2, limit.edgeId)
                        ps.setString(3, limit.direction)
                        if (limit.valueKmh != null) ps.setInt(4, limit.valueKmh) else ps.setNull(4, java.sql.Types.INTEGER)
                        ps.setString(5, limit.applicability)
                        ps.setDouble(6, limit.confidence)
                        ps.setString(7, limit.provenance)
                        ps.executeUpdate()
                    }
                }
            }
        }

        val sha = computeSha256(packFile)
        val manifestObj = JSONObject().apply {
            put("format", "vupack")
            put("formatVersion", formatVersion)
            put("schemaVersion", schemaVersion)
            put("source", source)
            put("license", license)
            put("version", version)
            put("synthetic", isSynthetic)
            put("edgeCount", edges.size)
            put("pointCount", points.size)
            if (schemaVersion >= 2) {
                put("roadLimitCount", roadLimits.size)
            }
            put("packBytes", packFile.length())
            put("sha256", sha)
            put("contentSha256", "0000000000000000000000000000000000000000000000000000000000000000")
        }

        manifestFile.writeText(manifestObj.toString(2), Charsets.UTF_8)
        return Pair(packFile, manifestFile)
    }

    fun computeSha256(file: File): String {
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
}
