package com.example.dreamweb

import android.content.Context
import com.example.dreamweb.adblock.AdBlockManager
import com.example.dreamweb.adblock.PrivacyEngine
import com.example.dreamweb.engine.WebViewPool
import com.example.dreamweb.managers.BookmarkManager
import com.example.dreamweb.managers.DpadHandler
import com.example.dreamweb.managers.HistoryManager
import com.example.dreamweb.metrics.PerformanceMonitor

class AppContainer(context: Context) {
    val webViewPool = WebViewPool(context)
    val adBlockManager = AdBlockManager(context)
    val privacyEngine = PrivacyEngine()
    val historyManager = HistoryManager(context)
    val bookmarkManager = BookmarkManager(context)
    val performanceMonitor = PerformanceMonitor(context)
    val dpadHandler = DpadHandler()
}
