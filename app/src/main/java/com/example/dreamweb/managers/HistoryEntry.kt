package com.example.dreamweb.managers

data class HistoryEntry(
    val id: Long,          // rowid SQLite (COLUMN_ID, INTEGER PRIMARY KEY)
    val url: String,
    val title: String,     // puede quedar "" si la página no expone <title>
    val timestamp: Long    // epoch millis
)
