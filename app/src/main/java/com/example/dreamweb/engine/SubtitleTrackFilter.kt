package com.example.dreamweb.engine

/**
 * Filtro de pistas de subtítulos soportadas por Media3 (FR-020).
 * Conserva solo URLs http(s) con extensión `.vtt`/`.srt`/`.ttml`/`.dfxp`;
 * el resto se omite en silencio, sin afectar al video ni al resto de pistas.
 *
 * Kotlin puro (JVM-testeable): los mimeTypes son literales equivalentes a
 * `MimeTypes.TEXT_VTT`, `MimeTypes.APPLICATION_SUBRIP` y
 * `MimeTypes.APPLICATION_TTML` de Media3.
 */
object SubtitleTrackFilter {

    const val MIME_TEXT_VTT = "text/vtt"
    const val MIME_SUBRIP = "application/x-subrip"
    const val MIME_TTML = "application/ttml+xml"

    private val SUPPORTED = mapOf(
        "vtt" to MIME_TEXT_VTT,
        "srt" to MIME_SUBRIP,
        "ttml" to MIME_TTML,
        "dfxp" to MIME_TTML
    )

    /**
     * Devuelve la [SubtitleTrack] si la URL es http(s) y el formato está
     * soportado; `null` en cualquier otro caso (omisión silenciosa).
     */
    fun filter(url: String, language: String? = null, label: String? = null): SubtitleTrack? {
        if (!VideoUrlValidator.isHttpUrl(url)) return null
        val mime = SUPPORTED[extensionOf(url)] ?: return null
        return SubtitleTrack(url = url, mimeType = mime, language = language, label = label)
    }

    private fun extensionOf(url: String): String? {
        val path = url.substringBefore('?').substringBefore('#')
        val lastDot = path.lastIndexOf('.')
        val lastSlash = path.lastIndexOf('/')
        if (lastDot < 0 || lastDot < lastSlash || lastDot == path.length - 1) return null
        return path.substring(lastDot + 1).lowercase()
    }
}
