# Data Model: Captura y Reproducción de Video Web

**Feature**: `004-video-capture-playback` | **Date**: 2026-11-23

Entidades del dominio y su representación. **No hay persistencia nueva**: los
hallazgos viven en memoria acotados a la página actual, la preferencia de
calidad vive en memoria de sesión (prohibido persistirla — clarificación del
spec) y `VideoLaunchPayload` viaja serializado en el extra del Intent. El
único estado que roza disco es el ya existente (`TlsExceptionStore`,
`HistoryManager` para la última URL).

---

## Entidades en memoria (lado navegador)

### VideoReport — mensaje crudo JS→Kotlin

Modelo de frontera producido por `VideoReportParser` a partir del JSON del
bridge `DreamWebSniffer`. Es inmutable y ya sanitizado.

```kotlin
data class VideoReport(
    val url: String,                    // URL del stream (validada por VideoUrlValidator)
    val pageUrl: String,                // document.location.href de la página origen
    val title: String = "",             // título de página o del medio (≤512 chars)
    val mimeType: String? = null,       // "application/vnd.apple.mpegurl", "video/mp4"…
    val format: StreamFormat,           // derivado de mimeType/extensión
    val widthPx: Int? = null,           // videoWidth (16..8192)
    val heightPx: Int? = null,          // videoHeight (16..8192)
    val durationSec: Double? = null,    // ≥0, null si no observable
    val positionSec: Double? = null,    // currentTime del medio, ≥0
    val headers: Map<String, String> = emptyMap(), // sanitizado: ≤32, nombres [A-Za-z0-9-]+
    val subtitles: List<SubtitleTrack> = emptyList(), // ≤8 tras SubtitleTrackFilter
    val mediaKey: String? = null,       // id por elemento <video> generado por el script
    val source: DetectionSource         // DOM | NETWORK (trazabilidad debug)
)

enum class StreamFormat { HLS, DASH, PROGRESSIVE, UNKNOWN }
enum class DetectionSource { DOM, NETWORK }
```

- **Validación** (en `VideoReportParser`, Principio II): `url` debe pasar
  `VideoUrlValidator.isVideoUrl`; `pageUrl` debe ser http(s); cada
  `subtitles[i].url` debe ser http(s); strings acotados; números en rango;
  headers sanitizados (nombre/vlor, sin `Host`/`Content-Length`/`Range`).
  Reporte mínimo válido = `{"url": "…"}` — el resto de campos es opcional.

### VideoFinding — hallazgo presentable

Agregado del registry: un reporte consolidado con sus rendiciones.

```kotlin
data class VideoFinding(
    val id: String,                     // estable por hallazgo (para el panel)
    val pageUrl: String,
    val title: String,
    val primaryRendition: Rendition,    // propuesta por defecto (R6)
    val renditions: List<Rendition>,    // ≥1, agrupadas por resolución
    val durationSec: Double?,
    val positionSec: Double?,
    val headers: Map<String, String>,   // merge DOM + requestHeaders
    val subtitles: List<SubtitleTrack>,
    val probed: Boolean = false         // sondeo acotado ya intentado (R5)
)
```

- **Dedup (FR-004)**: clave = URL de stream normalizada; un reporte con URL ya
  presente funde sus campos (rellena vacíos, nunca pisa datos ya resueltos
  salvo `positionSec` que siempre refleja el último valor observado).
- **Mutación de fuente (FR-005)**: reporte con misma `mediaKey` y URL distinta
  → el hallazgo reemplaza `primaryRendition.url` (el stream vigente gana) y
  `probed` se resetea.
- **Agrupación de rendiciones (FR-016, R6)**: rendiciones se agrupan por
  etiqueta de resolución (`"{height}p"` o `"Auto"`); dentro del grupo la URL
  preferida es HLS > DASH > progresivo (FR-017).

### Rendition — variante del mismo video

```kotlin
data class Rendition(
    val url: String,                    // validada
    val format: StreamFormat,
    val resolutionLabel: String,        // "720p" | "1080p" | "Auto"
    val heightPx: Int? = null,
    val bandwidthBps: Long? = null      // de #EXT-X-STREAM-INF cuando existe
)
```

### SubtitleTrack — pista de subtítulos

```kotlin
data class SubtitleTrack(
    val url: String,                    // http(s) validada
    val mimeType: String,               // TEXT_VTT | APPLICATION_SUBRIP | APPLICATION_TTML
    val language: String? = null,       // srclang de <track>
    val label: String? = null           // label de <track>
)
```

- **Filtro (FR-020)**: `SubtitleTrackFilter` conserva solo extensiones
  `.vtt`/`.srt`/`.ttml`/`.dfxp` con URL http(s); el resto se omite en
  silencio.

### QualityPreferenceMemory — preferencia de sesión

```kotlin
object QualityPreferenceMemory {
    var lastResolutionLabel: String?    // solo memoria; MUST NOT persistir
}
```

- Escritura: al confirmar selección de calidad en el panel.
- Lectura: al componer `primaryRendition` del siguiente hallazgo (FR-018).

## Entidad transportada (lado player)

### VideoLaunchPayload — contexto de reproducción (ampliado)

```kotlin
data class VideoLaunchPayload(
    val url: String,
    val referer: String = "",
    val userAgent: String = DEFAULT_USER_AGENT,
    val cookies: String = "",
    val headers: Map<String, String> = emptyMap(),   // NUEVO
    val subtitles: List<SubtitleTrack> = emptyList(),// NUEVO
    val startPositionMs: Long = 0,                   // NUEVO (positionSec × 1000)
    val mimeType: String? = null                     // NUEVO (hint del hallazgo)
)
```

- **Serialización**: un único extra `EXTRA_VIDEO_URL` con JSON
  (`org.json`); `parse()` dual — `{…}` → JSON; si no → formato legado
  `url|REFERER|UA|COOKIES` (compatibilidad, ver `contracts/playback-context-payload.md`).
- **Reglas**: `startPositionMs ≥ 0`; `subtitles` ya filtradas; `headers` ya
  sanitizados; los campos gestionados (`referer`/`userAgent`/`cookies`) son
  la capa autoritativa (R8).

### PlaybackSession — suspensión/restauración

No es una clase nueva: es el comportamiento coordinado de
`VideoPlayerManager` + `BrowserActivity` (ver `contracts/playback-session.md`).

```text
Estado: NAVEGANDO → CONGELADO (onPause+pauseTimers, player activo)
      → NAVEGANDO (resumeTimers+onResume al volver, WebView intacto)
Fallback 1: Activity recreada → webView.saveState/restoreState + lastPageUrl
Fallback 2: proceso muerto → última URL de HistoryManager
```

## Reglas de consistencia

| Regla | Origen |
|---|---|
| URL de stream solo http(s), anti segmentos/ads/trackers | `VideoUrlValidator` (FR-003) |
| Hallazgos acotados a la página: `clear()` en main-frame nav | FR-004 |
| Registry acotado a 64 hallazgos/página | Principio I |
| 1 sondeo máx. por hallazgo, cancelable, no bloqueante | FR-002 + clarificación |
| Preferencia de calidad: memoria, nunca disco | FR-018 |
| `positionSec` siempre del último reporte observado | FR-002 |
| Subtítulos no soportados se omiten sin error | FR-020 |
| Headers gestionados > headers capturados | R8 / Principio II |

## Diagrama de flujo

```text
JS document-start ──(JSON)──> SnifferBridge ──> VideoReportParser
                                                        │
shouldInterceptRequest ──(url+requestHeaders)────────────┤ (ambos → VideoReport)
                                                        ▼
                                            VideoFindingsRegistry ──┐
                                            (dedup/mutación/grupos) │
       VideoInfoProber (1 req, headers+TLS del player) ──enriquece──┘
                                                        ▼
                              indicador + panel D-pad (BrowserActivity)
                                                        │ selección usuario
                                                        ▼
                              VideoLaunchPayload (JSON extra) ──> Intent
                                                        ▼
                              NativeVideoActivity + PlayerHttpClientFactory
                              (headers merge R8, SubtitleConfiguration,
                               startPositionMs, TLS por dominio intacto)
```
