package com.example.dreamweb.managers

import android.content.ContentValues
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class HistoryManager(private val dbHelper: DbHelper) {

    /**
     * Upsert idempotente con deduplicación consecutiva (FR-001, research R1):
     * si la fila más reciente (timestamp DESC, id DESC) tiene la misma url →
     * UPDATE de title y timestamp sobre esa fila; si difiere → INSERT.
     * Tras cada escritura, rotación FIFO a MAX_ENTRIES (FR-011, R9).
     */
    suspend fun addOrUpdateLatest(url: String, title: String?) = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        val now = System.currentTimeMillis()

        val latest = db.query(
            DbHelper.TABLE_HISTORY,
            arrayOf(DbHelper.COLUMN_ID, DbHelper.COLUMN_URL),
            null, null, null, null,
            "${DbHelper.COLUMN_TIMESTAMP} DESC, ${DbHelper.COLUMN_ID} DESC",
            "1"
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) to cursor.getString(1) else null
        }

        if (latest != null && latest.second == url) {
            val values = ContentValues().apply {
                put(DbHelper.COLUMN_TITLE, title)
                put(DbHelper.COLUMN_TIMESTAMP, now)
            }
            db.update(
                DbHelper.TABLE_HISTORY, values,
                "${DbHelper.COLUMN_ID} = ?", arrayOf(latest.first.toString())
            )
        } else {
            val values = ContentValues().apply {
                put(DbHelper.COLUMN_URL, url)
                put(DbHelper.COLUMN_TITLE, title)
                put(DbHelper.COLUMN_TIMESTAMP, now)
            }
            db.insert(DbHelper.TABLE_HISTORY, null, values)
        }

        // Rotación FIFO: conserva las 500 entradas más recientes por timestamp
        db.execSQL(
            "DELETE FROM ${DbHelper.TABLE_HISTORY} WHERE ${DbHelper.COLUMN_ID} NOT IN (" +
                "SELECT ${DbHelper.COLUMN_ID} FROM ${DbHelper.TABLE_HISTORY} " +
                "ORDER BY ${DbHelper.COLUMN_TIMESTAMP} DESC, ${DbHelper.COLUMN_ID} DESC " +
                "LIMIT $MAX_ENTRIES)"
        )
    }

    /** Borrado individual por id (FR-010): una URL puede existir en varias filas no consecutivas. */
    suspend fun deleteById(id: Long) = withContext(Dispatchers.IO) {
        dbHelper.writableDatabase.delete(
            DbHelper.TABLE_HISTORY,
            "${DbHelper.COLUMN_ID} = ?",
            arrayOf(id.toString())
        )
    }

    suspend fun getRecentHistory(limit: Int = 20) = withContext(Dispatchers.IO) {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            DbHelper.TABLE_HISTORY,
            arrayOf(
                DbHelper.COLUMN_ID, DbHelper.COLUMN_URL,
                DbHelper.COLUMN_TITLE, DbHelper.COLUMN_TIMESTAMP
            ),
            null, null, null, null,
            "${DbHelper.COLUMN_TIMESTAMP} DESC, ${DbHelper.COLUMN_ID} DESC",
            limit.toString()
        )
        val history = mutableListOf<HistoryEntry>()
        while (cursor.moveToNext()) {
            history.add(
                HistoryEntry(
                    id = cursor.getLong(0),
                    url = cursor.getString(1),
                    title = cursor.getString(2) ?: "",
                    timestamp = cursor.getLong(3)
                )
            )
        }
        cursor.close()
        history
    }

    companion object {
        const val MAX_ENTRIES = 500
    }
}
