package vn.vumap.feature.signs.db

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * Android implementation of [SignPackDb] using built-in [SQLiteDatabase].
 *
 * Always opens databases with [SQLiteDatabase.OPEN_READONLY] to guarantee
 * zero modification of offline pack files.
 */
class AndroidSignPackDb(private val db: SQLiteDatabase) : SignPackDb {

    override fun rawQuery(sql: String, args: Array<String>): SignPackCursor {
        val cursor = db.rawQuery(sql, args)
        return AndroidSignPackCursor(cursor)
    }

    override fun pragma(statement: String): String? {
        val cursor = db.rawQuery("PRAGMA $statement", null)
        cursor.use {
            return if (it.moveToFirst() && !it.isNull(0)) {
                it.getString(0)
            } else {
                null
            }
        }
    }

    override fun userVersion(): Int = db.version

    override fun isOpen(): Boolean = db.isOpen

    override fun close() {
        if (db.isOpen) {
            db.close()
        }
    }
}

class AndroidSignPackCursor(private val cursor: Cursor) : SignPackCursor {
    override fun moveToNext(): Boolean = cursor.moveToNext()

    /**
     * Maps [Cursor.getType] (the value's storage class, evaluated by the
     * SQLite engine) onto the shared [SignPackStorageType] contract. This is
     * consulted BEFORE any numeric getter so malformed storage classes are
     * rejected instead of silently coerced (60.5 -> 60, 'east' -> 0.0).
     */
    override fun getStorageType(columnIndex: Int): SignPackStorageType = when (cursor.getType(columnIndex)) {
        Cursor.FIELD_TYPE_NULL -> SignPackStorageType.NULL
        Cursor.FIELD_TYPE_INTEGER -> SignPackStorageType.INTEGER
        Cursor.FIELD_TYPE_FLOAT -> SignPackStorageType.REAL
        Cursor.FIELD_TYPE_STRING -> SignPackStorageType.TEXT
        Cursor.FIELD_TYPE_BLOB -> SignPackStorageType.BLOB
        else -> SignPackStorageType.UNKNOWN
    }

    override fun getString(columnIndex: Int): String? = if (cursor.isNull(columnIndex)) null else cursor.getString(columnIndex)
    override fun getInt(columnIndex: Int): Int? = if (cursor.isNull(columnIndex)) null else cursor.getInt(columnIndex)
    override fun getDouble(columnIndex: Int): Double? = if (cursor.isNull(columnIndex)) null else cursor.getDouble(columnIndex)
    override fun getLong(columnIndex: Int): Long? = if (cursor.isNull(columnIndex)) null else cursor.getLong(columnIndex)
    override fun isNull(columnIndex: Int): Boolean = cursor.isNull(columnIndex)
    override fun getColumnIndex(name: String): Int = cursor.getColumnIndex(name)
    override fun close() = cursor.close()
}

/**
 * Standard Android factory opening offline pack files via [SQLiteDatabase.OPEN_READONLY].
 */
class AndroidSignPackDbFactory : SignPackDbFactory {
    override fun openReadOnly(databaseFile: File): SignPackDb {
        val db = SQLiteDatabase.openDatabase(
            databaseFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY
        )
        return AndroidSignPackDb(db)
    }
}
