package com.example.dreamweb.engine

import java.net.URI
import java.net.URISyntaxException

/**
 * Heurística compartida de detección de URLs de video (FR-007).
 * Extraída de CustomWebClient.shouldInterceptRequest: mismos patrones de
 * formato, mismas exclusiones de segmentos y de ads/trackers, MÁS validación
 * de esquema — solo http/https. Dos fronteras según el origen del candidato:
 * [isVideoUrl] para el canal de red (exige patrón de formato) e
 * [isDomMediaUrl] para el bridge JavaScript (el contexto del elemento ya es
 * evidencia de medio; los streams reales llegan a menudo sin extensión).
 */
object VideoUrlValidator {

    /**
     * Solo valida esquema http/https + host parseable — sin exigir patrón de
     * video. Usado para `pageUrl`, subtítulos y la URL del payload del player.
     */
    fun isHttpUrl(url: String): Boolean {
        if (url.isBlank()) return false
        val urlLower = url.lowercase()
        if (!urlLower.startsWith("http://") && !urlLower.startsWith("https://")) {
            return false
        }
        val uri = try {
            URI(url)
        } catch (e: URISyntaxException) {
            return false
        }
        return !uri.host.isNullOrBlank()
    }

    /**
     * Frontera del canal de RED (`shouldInterceptRequest`): la petición es
     * arbitraria, así que se exige evidencia de formato en la URL además de
     * los filtros de segmento/ads. Cubre tanto extensiones literales como la
     * forma sin extensión `/m3u8/<id>` que usan los proxies de streaming.
     */
    fun isVideoUrl(url: String): Boolean =
        isHttpUrl(url) && hasVideoFormatHint(url) && !isSegment(url) && !isAdOrTracker(url)

    /**
     * Frontera del canal DOM (bridge JS): el contexto ya es de medio — la URL
     * proviene de un `<video>/<source>` o de la playlist de un player
     * conocido—, por lo que no se exige patrón de formato en la URL (los
     * streams reales llegan muchas veces sin extensión). Siguen aplicando
     * http(s) y los filtros de segmento/ads/trackers.
     */
    fun isDomMediaUrl(url: String): Boolean =
        isHttpUrl(url) && !isSegment(url) && !isAdOrTracker(url)

    /**
     * Patrones de formato de video — evaluados SOLO sobre el path (sin query
     * ni fragment): beacons/analytics incrustan la URL del stream en sus
     * parámetros (p. ej. `…/ping.gif?mu=…%2Fm3u8%2F…` de jwplayer) y no deben
     * producir hallazgos espurios.
     */
    private fun hasVideoFormatHint(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return path.contains("m3u8") ||   // .m3u8, /m3u8/<id>
               path.contains(".m3u") ||
               path.contains(".mp4") ||
               path.contains(".mpd") ||
               path.contains(".mkv") ||
               path.contains(".webm") ||
               path.contains("/manifest")
    }

    /** Filtrado de segmentos (no queremos capturar cada .ts individual). */
    private fun isSegment(url: String): Boolean {
        val urlLower = url.lowercase()
        return urlLower.contains(".ts") ||
               urlLower.contains("/segment") ||
               urlLower.contains("/segs/") ||
               urlLower.contains("range=")
    }

    /** Filtrado de publicidad común en reproductores. */
    private fun isAdOrTracker(url: String): Boolean {
        val urlLower = url.lowercase()
        return urlLower.contains("adsystem") ||
               urlLower.contains("adserver") ||
               urlLower.contains("analytics") ||
               urlLower.contains("doubleclick") ||
               urlLower.contains("pixel") ||
               urlLower.contains("googlesyndication") ||
               urlLower.contains("/ads/")
    }
}
