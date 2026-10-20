package vn.vumap.feature.signs.db

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.Statement

/**
 * JVM test implementation of [SignPackDb] using standard SQLite JDBC.
 *
 * Allows JVM unit and integration tests to run queries and verify integrity
 * against real SQLite bytes without Android runtime emulator dependencies.
 */
class JdbcSignPackDb(private val connection: Connection) : SignPackDb {

    override fun rawQuery(sql: String, args: Array<String>): SignPackCursor {
        val stmt = connection.prepareStatement(sql)
        for (i in args.indices) {
            stmt.setString(i + 1, args[i])
        }
        val rs = stmt.executeQuery()
        return JdbcSignPackCursor(stmt, rs)
    }

    override fun pragma(statement: String): String? {
        connection.createStatement().use { stmt ->
            stmt.executeQuery("PRAGMA $statement").use { rs ->
                return if (rs.next()) {
                    rs.getString(1)
                } else {
                    null
                }
            }
        }
    }

    override fun userVersion(): Int {
        val ver = pragma("user_version")
        return ver?.toIntOrNull() ?: 0
    }

    override fun isOpen(): Boolean = !connection.isClosed

    override fun close() {
        if (!connection.isClosed) {
            connection.close()
        }
    }
}

class JdbcSignPackCursor(
    private val statement: Statement,
    private val rs: ResultSet
) : SignPackCursor {

    private val metaData = rs.metaData

    override fun moveToNext(): Boolean = rs.next()

    /**
     * Storage class of the actual stored value (not the declared column type),
     * derived from the JDBC object identity: Long/Integer -> INTEGER,
     * Double/Float/BigDecimal -> REAL, String -> TEXT, ByteArray -> BLOB,
     * null -> NULL. Keeps the JVM test driver honest about what SQLite wrote
     * instead of hiding coercion behind typed getters.
     */
    override fun getStorageType(columnIndex: Int): SignPackStorageType {
        return when (val value = rs.getObject(columnIndex + 1)) {
            null -> SignPackStorageType.NULL
            is Long, is Int, is Short, is Byte -> SignPackStorageType.INTEGER
            is Double, is Float, is java.math.BigDecimal -> SignPackStorageType.REAL
            is String -> SignPackStorageType.TEXT
            is ByteArray -> SignPackStorageType.BLOB
            else -> SignPackStorageType.UNKNOWN
        }
    }

    override fun getString(columnIndex: Int): String? = rs.getString(columnIndex + 1)

    override fun getInt(columnIndex: Int): Int? {
        val v = rs.getInt(columnIndex + 1)
        return if (rs.wasNull()) null else v
    }

    override fun getDouble(columnIndex: Int): Double? {
        val v = rs.getDouble(columnIndex + 1)
        return if (rs.wasNull()) null else v
    }

    override fun getLong(columnIndex: Int): Long? {
        val v = rs.getLong(columnIndex + 1)
        return if (rs.wasNull()) null else v
    }

    override fun isNull(columnIndex: Int): Boolean {
        rs.getObject(columnIndex + 1)
        return rs.wasNull()
    }

    override fun getColumnIndex(name: String): Int {
        val count = metaData.columnCount
        for (i in 1..count) {
            if (metaData.getColumnLabel(i).equals(name, ignoreCase = true) ||
                metaData.getColumnName(i).equals(name, ignoreCase = true)
            ) {
                return i - 1
            }
        }
        return -1
    }

    override fun close() {
        try {
            rs.close()
        } finally {
            statement.close()
        }
    }
}

class JdbcSignPackDbFactory : SignPackDbFactory {
    override fun openReadOnly(databaseFile: File): SignPackDb {
        // Enforce SQLite open mode read-only
        val url = "jdbc:sqlite:" + databaseFile.absolutePath
        val props = java.util.Properties().apply {
            setProperty("open_mode", "1") // 1 = OPEN_READONLY
        }
        val conn = DriverManager.getConnection(url, props)
        return JdbcSignPackDb(conn)
    }
}
