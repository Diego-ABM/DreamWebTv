package com.example.dreamweb

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Looper

class BrowserApp : Application() {
    lateinit var container: AppContainer

    // Contador de activities resumidas: 0 = app en background (FR-005, R5)
    private var resumedActivities = 0

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.adBlockManager.init()

        // El monitor solo existe en DEBUG: en release nunca arranca
        // (0 logs periódicos, 0 frame callbacks).
        if (BuildConfig.DEBUG) {
            container.performanceMonitor.start()
        }

        // Apagado en background incluso en DEBUG: a 0 activities resumidas se
        // libera todo; al volver a foreground se rearma (start es idempotente).
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                resumedActivities++
                if (BuildConfig.DEBUG) {
                    container.performanceMonitor.start()
                }
            }

            override fun onActivityPaused(activity: Activity) {
                resumedActivities--
                if (resumedActivities <= 0) {
                    resumedActivities = 0
                    container.performanceMonitor.stop()
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })

        Looper.myQueue().addIdleHandler {
            container.webViewPool.preload()
            false // Run once
        }
    }
}
