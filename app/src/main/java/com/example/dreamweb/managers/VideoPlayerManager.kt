package com.example.dreamweb.managers

import android.app.Activity
import android.content.Intent
import android.webkit.WebView
import android.widget.Toast
import com.example.dreamweb.ui.NativeVideoActivity

object VideoPlayerManager {
    private var isPlaying = false

    fun launchPlayer(webView: WebView?, videoUrl: String) {
        if (isPlaying) return

        // Validar ANTES de marcar el latch (FR-003, R4): contexto nulo o
        // activity muriendo → descarte limpio, isPlaying nunca queda latcheado.
        val context = webView?.context ?: return
        (context as? Activity)?.let {
            if (it.isFinishing || it.isDestroyed) return
        }

        // Congelación absoluta del motor Chromium trasero para liberar RAM
        webView.apply {
            onPause()
            pauseTimers()
        }
        isPlaying = true

        val intent = Intent(context, NativeVideoActivity::class.java).apply {
            putExtra("EXTRA_VIDEO_URL", videoUrl)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            // Revertir el offloading si el lanzamiento falla
            isPlaying = false
            webView.apply {
                onResume()
                resumeTimers()
            }
            Toast.makeText(context, "No se pudo abrir el reproductor", Toast.LENGTH_SHORT).show()
        }
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
