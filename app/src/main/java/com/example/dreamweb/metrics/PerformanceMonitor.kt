package com.example.dreamweb.metrics

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.util.Log
import android.view.Choreographer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PerformanceMonitor(private val context: Context) {
    private var scope = CoroutineScope(Dispatchers.Default)
    private var lastFrameTimeNanos: Long = 0
    private var running = false
    private val TAG = "PerformanceMonitor"

    // Callback guardado en campo para poder retirarlo en stop() (FR-005, R5)
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            if (lastFrameTimeNanos != 0L) {
                val frameTimeMs = (frameTimeNanos - lastFrameTimeNanos) / 1_000_000
                if (frameTimeMs > 17) { // 16.6ms is the budget for 60fps
                    Log.w(TAG, "Jank detected: $frameTimeMs ms")
                }
            }
            lastFrameTimeNanos = frameTimeNanos
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /** Idempotente: no duplica frame callbacks ni corrutinas. */
    @Synchronized
    fun start() {
        if (running) return
        running = true
        if (scope.coroutineContext[Job]?.isActive != true) {
            scope = CoroutineScope(Dispatchers.Default)
        }
        Choreographer.getInstance().postFrameCallback(frameCallback)
        startMemoryMonitoring()
    }

    /** Apagado simétrico: libera el FrameCallback y cancela el scope (FR-005). */
    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        lastFrameTimeNanos = 0
        scope.cancel()
    }

    private fun startMemoryMonitoring() {
        scope.launch {
            while (isActive) {
                val memoryInfo = ActivityManager.MemoryInfo()
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                am.getMemoryInfo(memoryInfo)

                val usedMem = (memoryInfo.totalMem - memoryInfo.availMem) / 1024 / 1024
                val appMem = Debug.getPss() / 1024

                Log.d(TAG, "System RAM: $usedMem MB used / App RAM: $appMem MB")

                delay(5000)
            }
        }
    }
}
