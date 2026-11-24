package com.example.dreamweb.managers

import android.app.Activity
import android.content.Intent
import android.webkit.WebView
import android.widget.Toast
import com.example.dreamweb.engine.VideoUrlValidator
import com.example.dreamweb.ui.NativeVideoActivity

object VideoPlayerManager {
    private var isPlaying = false

    /**
     * Lanza el player nativo con el payload JSON v2 (contrato §2 de
     * playback-context-payload). Valida ANTES de congelar: contexto vivo,
     * latch libre y payload parseable con url http(s) — un lanzamiento
     * inválido nunca deja el WebView suspendido ni el latch tomado
     * (escenario "fallo al lanzar" del spec).
     */
    fun launchPlayer(webView: WebView?, payloadJson: String) {
        if (isPlaying) return

        val context = webView?.context ?: return
        (context as? Activity)?.let {
            if (it.isFinishing || it.isDestroyed) return
        }

        val payload = VideoLaunchPayload.parse(payloadJson)
        if (payload == null || !VideoUrlValidator.isHttpUrl(payload.url)) {
            Toast.makeText(
                context,
                "No se pudo lanzar el video (datos inválidos)",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        // Congelación absoluta del motor Chromium trasero para liberar RAM
        webView.apply {
            onPause()
            pauseTimers()
        }
        isPlaying = true

        val intent = Intent(context, NativeVideoActivity::class.java).apply {
            putExtra("EXTRA_VIDEO_URL", payloadJson)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            // Revertir el offloading si el lanzamiento falla (vigente)
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
