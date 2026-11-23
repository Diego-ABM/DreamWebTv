package com.example.dreamweb

import android.content.Context
import com.example.dreamweb.adblock.AdBlockManager
import com.example.dreamweb.adblock.AdBlockWhitelistStore
import com.example.dreamweb.adblock.PrivacyEngine
import com.example.dreamweb.engine.WebViewPool
import com.example.dreamweb.managers.BookmarkManager
import com.example.dreamweb.managers.DbHelper
import com.example.dreamweb.managers.DpadHandler
import com.example.dreamweb.managers.HistoryManager
import com.example.dreamweb.metrics.PerformanceMonitor
import com.example.dreamweb.security.TlsExceptionStore

class AppContainer(context: Context) {
    val webViewPool = WebViewPool(context)
    val adBlockWhitelistStore = AdBlockWhitelistStore(context)
    val adBlockManager = AdBlockManager(context, adBlockWhitelistStore)
    val privacyEngine = PrivacyEngine()
    // Un solo DbHelper = una sola conexión SQLite compartida (FR-009, R8)
    val dbHelper = DbHelper(context)
    val historyManager = HistoryManager(dbHelper)
    val bookmarkManager = BookmarkManager(dbHelper)
    val performanceMonitor = PerformanceMonitor(context)
    val dpadHandler = DpadHandler()
    val tlsExceptionStore = TlsExceptionStore(context)
}
