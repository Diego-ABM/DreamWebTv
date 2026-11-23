package com.example.dreamweb.engine

import java.net.URI
import java.net.URISyntaxException

/**
 * Heurística compartida de detección de URLs de video (FR-007).
 * Extraída de CustomWebClient.shouldInterceptRequest: mismos patrones de
 * formato, mismas exclusiones de segmentos y de ads/trackers, MÁS validación
 * de esquema — solo http/https. Es la frontera única usada tanto por el
 * sniffer de red como por el bridge JavaScript.
 */
object VideoUrlValidator {

    fun isVideoUrl(url: String): Boolean {
        if (url.isBlank()) return false

        val urlLower = url.lowercase()

        // Solo esquemas http/https: bloquea javascript:, data:, file:, blob:,
        // intent:, vbscript:, texto plano y cualquier cosa sin esquema.
        if (!urlLower.startsWith("http://") && !urlLower.startsWith("https://")) {
            return false
        }

        // La URL debe parsear y tener host no vacío (rechaza malformadas).
        val uri = try {
            URI(url)
        } catch (e: URISyntaxException) {
            return false
        }
        if (uri.host.isNullOrBlank()) return false

        // Extensiones y patrones de video comunes (verbatim del sniffer)
        val isVideoFormat = urlLower.contains(".m3u8") ||
                            urlLower.contains(".mp4") ||
                            urlLower.contains(".mpd") ||
                            urlLower.contains(".mkv") ||
                            urlLower.contains(".webm") ||
                            urlLower.contains("/manifest") ||
                            urlLower.contains(".m3u8?") ||
                            urlLower.contains("playlist.m3u8")

        // Filtrado de segmentos (no queremos capturar cada .ts individual)
        val isSegment = urlLower.contains(".ts") ||
                        urlLower.contains("/segment") ||
                        urlLower.contains("range=")

        // Filtrado de publicidad común en reproductores
        val isAdOrTracker = urlLower.contains("adsystem") ||
                           urlLower.contains("adserver") ||
                           urlLower.contains("analytics") ||
                           urlLower.contains("doubleclick") ||
                           urlLower.contains("pixel") ||
                           urlLower.contains("googlesyndication") ||
                           urlLower.contains("/ads/")

        return isVideoFormat && !isSegment && !isAdOrTracker
    }
}
