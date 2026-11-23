package com.example.dreamweb.managers

import android.content.ContentValues
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BookmarkManager(private val dbHelper: DbHelper) {

    suspend fun addBookmark(url: String, title: String?) = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put(DbHelper.COLUMN_URL, url)
            put(DbHelper.COLUMN_TITLE, title)
            put(DbHelper.COLUMN_TIMESTAMP, System.currentTimeMillis())
        }
        db.insert(DbHelper.TABLE_BOOKMARKS, null, values)
    }

    /** Borrado individual por id (FR-010). */
    suspend fun deleteById(id: Long) = withContext(Dispatchers.IO) {
        dbHelper.writableDatabase.delete(
            DbHelper.TABLE_BOOKMARKS,
            "${DbHelper.COLUMN_ID} = ?",
            arrayOf(id.toString())
        )
    }

    suspend fun getAllBookmarks() = withContext(Dispatchers.IO) {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            DbHelper.TABLE_BOOKMARKS,
            arrayOf(
                DbHelper.COLUMN_ID, DbHelper.COLUMN_URL,
                DbHelper.COLUMN_TITLE, DbHelper.COLUMN_TIMESTAMP
            ),
            null, null, null, null,
            "${DbHelper.COLUMN_TITLE} ASC"
        )
        val bookmarks = mutableListOf<Bookmark>()
        while (cursor.moveToNext()) {
            bookmarks.add(
                Bookmark(
                    id = cursor.getLong(0),
                    url = cursor.getString(1),
                    title = cursor.getString(2) ?: "",
                    timestamp = cursor.getLong(3)
                )
            )
        }
        cursor.close()
        bookmarks
    }
}
