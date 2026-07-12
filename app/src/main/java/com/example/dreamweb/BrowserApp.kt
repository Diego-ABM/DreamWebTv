package com.example.dreamweb

import android.app.Application
import android.os.Looper

class BrowserApp : Application() {
    lateinit var container: AppContainer

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.adBlockManager.init()
        container.performanceMonitor.start()

        Looper.myQueue().addIdleHandler {
            container.webViewPool.preload()
            false // Run once
        }
    }
}
