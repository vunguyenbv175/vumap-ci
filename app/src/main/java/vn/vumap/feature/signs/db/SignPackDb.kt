package vn.vumap.feature.signs.db

import java.io.Closeable
import java.io.File

/**
 * SQLite storage class of a single value, as reported by the underlying driver
 * before any numeric/text coercion.
 *
 * Mirrors the values returned by the SQLite `typeof()` SQL function so the same
 * schema-v1 type rules apply on Android and on the JVM test driver.
 */
enum class SignPackStorageType(val sqliteName: String) {
    NULL("null"),
    INTEGER("integer"),
    REAL("real"),
    TEXT("text"),
    BLOB("blob"),

    /** The driver could not classify the stored value. Never valid for schema v1. */
    UNKNOWN("unknown");

    companion object {
        fun fromSqliteName(name: String?): SignPackStorageType =
            entries.firstOrNull { it.sqliteName == name } ?: UNKNOWN
    }
}

/**
 * Lightweight read-only SQLite database abstraction for offline pack consumption.
 *
 * Implemented via Android's built-in [android.database.sqlite.SQLiteDatabase]
 * in production, and via standard JDBC SQLite in JVM unit tests.
 */
interface SignPackDb : Closeable {
    /**
     * Executes a raw SQL query with bound positional arguments.
     */
    fun rawQuery(sql: String, args: Array<String> = emptyArray()): SignPackCursor

    /**
     * Executes a PRAGMA statement and returns the first column of the first row,
     * or null if the query returned no rows or a null value.
     */
    fun pragma(statement: String): String?

    /**
     * Returns the SQLite `user_version` PRAGMA value.
     */
    fun userVersion(): Int

    /**
     * Returns true if the database connection is currently open.
     */
    fun isOpen(): Boolean
}

/**
 * Read-only cursor interface over SQLite query results.
 */
interface SignPackCursor : Closeable {
    fun moveToNext(): Boolean

    /**
     * True storage class of the value at [columnIndex] in the current row,
     * resolved before any getInt/getDouble/getString coercion. Both adapters
     * must report the storage class actually written into the file (SQLite
     * tables here are not STRICT, so text/blob values can sit in numeric
     * columns).
     */
    fun getStorageType(columnIndex: Int): SignPackStorageType

    fun getString(columnIndex: Int): String?
    fun getInt(columnIndex: Int): Int?
    fun getDouble(columnIndex: Int): Double?
    fun getLong(columnIndex: Int): Long?
    fun isNull(columnIndex: Int): Boolean
    fun getColumnIndex(name: String): Int
}

/**
 * Factory for opening offline SQLite packs in read-only mode.
 */
fun interface SignPackDbFactory {
    fun openReadOnly(databaseFile: File): SignPackDb
}
