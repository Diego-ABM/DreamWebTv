package com.example.dreamweb.engine

/**
 * Modelos de dominio de la feature "Captura y Reproducción de Video Web"
 * (specs/004-video-capture-playback/data-model.md). Solo tipos — la lógica
 * vive en [VideoReportParser], [VideoFindingsRegistry] y [SubtitleTrackFilter].
 */

/** Reporte crudo JS→Kotlin (o red→Kotlin), ya sanitizado por el parser. */
data class VideoReport(
    val url: String,                                 // URL del stream (validada por VideoUrlValidator)
    val pageUrl: String,                             // document.location.href de la página origen
    val title: String = "",                          // título de página o del medio (≤512 chars)
    val mimeType: String? = null,                    // "application/vnd.apple.mpegurl", "video/mp4"…
    val format: StreamFormat,                        // derivado de mimeType/extensión
    val widthPx: Int? = null,                        // videoWidth (16..8192)
    val heightPx: Int? = null,                       // videoHeight (16..8192)
    val durationSec: Double? = null,                 // ≥0, null si no observable
    val positionSec: Double? = null,                 // currentTime del medio, ≥0
    val headers: Map<String, String> = emptyMap(),   // sanitizado: ≤32, nombres [A-Za-z0-9-]+
    val subtitles: List<SubtitleTrack> = emptyList(),// ≤8 tras SubtitleTrackFilter
    val mediaKey: String? = null,                    // id por elemento <video> generado por el script
    val source: DetectionSource                      // DOM | NETWORK (trazabilidad debug)
)

enum class StreamFormat { HLS, DASH, PROGRESSIVE, UNKNOWN }

enum class DetectionSource { DOM, NETWORK }

/** Hallazgo presentable: un reporte consolidado con sus rendiciones. */
data class VideoFinding(
    val id: String,                        // estable por hallazgo (para el panel)
    val pageUrl: String,
    val title: String,
    val primaryRendition: Rendition,       // propuesta por defecto (R6)
    val renditions: List<Rendition>,       // ≥1; agrupadas por resolución en la UI
    val durationSec: Double?,
    val positionSec: Double?,
    val headers: Map<String, String>,      // merge DOM + requestHeaders
    val subtitles: List<SubtitleTrack>,
    val probed: Boolean = false,           // sondeo acotado ya intentado (R5)
    val mimeType: String? = null,          // hint reportado por la página, si existía
    val mediaKey: String? = null           // mediaKey del elemento de la fuente vigente
)

/** Variante del mismo video a una resolución y formato determinados. */
data class Rendition(
    val url: String,                       // validada
    val format: StreamFormat,
    val resolutionLabel: String,           // "{height}p" | "Auto"
    val heightPx: Int? = null,
    val bandwidthBps: Long? = null         // de #EXT-X-STREAM-INF cuando existe
)

/** Pista de subtítulos declarada por la página (formato ya filtrado). */
data class SubtitleTrack(
    val url: String,                       // http(s) validada
    val mimeType: String,                  // TEXT_VTT | APPLICATION_SUBRIP | APPLICATION_TTML
    val language: String? = null,          // srclang de <track>
    val label: String? = null              // label de <track>
)

/** Etiqueta genérica de las rendiciones sin resolución declarable (spec edge case). */
const val AUTO_RESOLUTION_LABEL = "Auto"

/**
 * Deriva el formato de stream desde la URL y el mimeType declarado.
 * Compartido por el parser (reportes DOM) y el canal de red.
 */
fun streamFormatOf(url: String, mimeType: String? = null): StreamFormat {
    val mime = mimeType?.lowercase() ?: ""
    val u = url.lowercase()
    return when {
        mime.contains("mpegurl") || mime.contains("m3u8") -> StreamFormat.HLS
        mime.contains("dash") -> StreamFormat.DASH
        u.contains(".m3u8") -> StreamFormat.HLS
        u.contains(".mpd") -> StreamFormat.DASH
        u.contains(".mp4") || u.contains(".mkv") || u.contains(".webm") ||
            mime.startsWith("video/") -> StreamFormat.PROGRESSIVE
        else -> StreamFormat.UNKNOWN
    }
}
