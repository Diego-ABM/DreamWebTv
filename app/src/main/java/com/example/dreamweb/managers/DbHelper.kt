package com.example.dreamweb.managers

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class DbHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        const val DATABASE_NAME = "dreamweb.db"
        const val DATABASE_VERSION = 1

        const val TABLE_HISTORY = "history"
        const val COLUMN_ID = "id"
        const val COLUMN_URL = "url"
        const val COLUMN_TITLE = "title"
        const val COLUMN_TIMESTAMP = "timestamp"

        const val TABLE_BOOKMARKS = "bookmarks"
        // Same columns for simplicity
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createHistoryTable = """
            CREATE TABLE $TABLE_HISTORY (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_URL TEXT NOT NULL,
                $COLUMN_TITLE TEXT,
                $COLUMN_TIMESTAMP INTEGER NOT NULL
            )
        """.trimIndent()
        
        val createBookmarksTable = """
            CREATE TABLE $TABLE_BOOKMARKS (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_URL TEXT NOT NULL,
                $COLUMN_TITLE TEXT,
                $COLUMN_TIMESTAMP INTEGER NOT NULL
            )
        """.trimIndent()

        db.execSQL(createHistoryTable)
        db.execSQL(createBookmarksTable)
        db.execSQL("CREATE INDEX idx_history_url ON $TABLE_HISTORY($COLUMN_URL)")
        db.execSQL("CREATE INDEX idx_bookmarks_url ON $TABLE_BOOKMARKS($COLUMN_URL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_HISTORY")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_BOOKMARKS")
        onCreate(db)
    }
}
