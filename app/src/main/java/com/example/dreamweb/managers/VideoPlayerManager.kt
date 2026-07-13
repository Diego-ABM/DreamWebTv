package com.example.dreamweb.managers

import android.content.Context
import android.content.Intent
import android.webkit.WebView
import com.example.dreamweb.ui.NativeVideoActivity

object VideoPlayerManager {
    private var isPlaying = false

    fun launchPlayer(webView: WebView?, videoUrl: String) {
        if (isPlaying) return
        isPlaying = true

        // Congelación absoluta del motor Chromium trasero para liberar RAM
        webView?.apply {
            onPause()
            pauseTimers() 
        }

        val context = webView?.context ?: return
        val intent = Intent(context, NativeVideoActivity::class.java).apply {
            putExtra("EXTRA_VIDEO_URL", videoUrl)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun releasePlayer(webView: WebView?) {
        isPlaying = false
        // Reactivación del motor Chromium al regresar
        webView?.apply {
            onResume()
            resumeTimers()
        }
    }
}
