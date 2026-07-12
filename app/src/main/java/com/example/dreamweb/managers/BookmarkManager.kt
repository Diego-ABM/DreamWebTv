package com.example.dreamweb.managers

import android.content.ContentValues
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BookmarkManager(context: Context) {
    private val dbHelper = DbHelper(context)

    suspend fun addBookmark(url: String, title: String?) = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put(DbHelper.COLUMN_URL, url)
            put(DbHelper.COLUMN_TITLE, title)
            put(DbHelper.COLUMN_TIMESTAMP, System.currentTimeMillis())
        }
        db.insert(DbHelper.TABLE_BOOKMARKS, null, values)
    }

    suspend fun getAllBookmarks() = withContext(Dispatchers.IO) {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            DbHelper.TABLE_BOOKMARKS,
            arrayOf(DbHelper.COLUMN_URL, DbHelper.COLUMN_TITLE),
            null, null, null, null,
            "${DbHelper.COLUMN_TITLE} ASC"
        )
        val bookmarks = mutableListOf<Pair<String, String>>()
        while (cursor.moveToNext()) {
            val url = cursor.getString(0)
            val title = cursor.getString(1) ?: ""
            bookmarks.add(url to title)
        }
        cursor.close()
        bookmarks
    }
}
