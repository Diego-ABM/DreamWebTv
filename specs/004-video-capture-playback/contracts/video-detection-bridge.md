# Contract: Detección de video — script JS ↔ bridge Kotlin

**Feature**: `004-video-capture-playback` | **Fecha**: 2026-11-23

Contrato del canal de detección tras este spec. **Sustituye** al contrato del
script de `specs/003-deuda-tecnica/contracts/js-sniffer-lifecycle.md` (mismas
condiciones de apagado, nuevo formato de reporte) y **extiende** el de
`specs/001-security-hardening/contracts/js-bridge.md` (mismo nombre de bridge,
nueva firma de mensaje).

## 1. Puntos de inyección

| Modo | Condición | API | Momento |
|---|---|---|---|
| Document-start | `WebViewFeature.isFeatureSupported(DOCUMENT_START_SCRIPT)` | `WebViewCompat.addDocumentStartJavaScript(wv, script, setOf("*"))` | tras `pool.getWebView()`, antes de `loadUrl` |
| Fallback | feature no soportada | `webView.evaluateJavascript(script, null)` | `CustomWebClient.onPageFinished` (vigente) |

- Script único: `app/src/main/assets/video_detector.js`, cargado por
  `VideoSnifferInjector`. El modo fallback evalúa el mismo asset con la sección
  de hooks de setters desactivada (players ya existentes).
- El canal de red `shouldInterceptRequest` → `VideoUrlValidator` → reporte
  `NETWORK` con `request.requestHeaders` permanece activo en ambos modos.
- **Limpieza simétrica** (Principio V): `releaseWebView` desregistra el script
  (`ScriptHandler.remove()` guardado por el injector) además del
  `removeJavascriptInterface("DreamWebSniffer")` ya existente.

## 2. Mensaje JS→Kotlin

Un único método expuesto (superficie de ataque mínima):

```kotlin
@JavascriptInterface
fun onVideoFound(json: String)   // SnifferBridge — nombre JS: "DreamWebSniffer"
```

El script invoca `DreamWebSniffer.onVideoFound(JSON.stringify(report))` donde
`report` es:

```json
{
  "url": "https://cdn.e.com/v/master.m3u8",     // obligatorio
  "pageUrl": "https://site.e.com/watch",        // obligatorio
  "title": "Episodio 1",                        // opcional ≤512
  "mimeType": "application/vnd.apple.mpegurl",  // opcional
  "width": 1280, "height": 720,                 // opcional 16..8192
  "durationSec": 1320.5,                        // opcional ≥0
  "positionSec": 41.2,                          // opcional ≥0
  "headers": {"X-Client": "tv"},                // opcional ≤32 entradas
  "subtitles": [                                // opcional ≤8
    {"url": "https://cdn.e.com/s/es.vtt",
     "language": "es", "label": "Español"}
  ],
  "mediaKey": "v3"                              // opcional; id del <video>
}
```

## 3. Validación del lado Kotlin (frontera no confiable, Principio II)

`VideoReportParser.parse(json)` aplica, en orden:

1. Tamaño ≤ 64 KB; JSON objeto bien formado; rechazo silencioso si no.
2. `url` presente y `VideoUrlValidator.isVideoUrl(url)` (http(s), formato de
   video, no segmento, no ads/trackers) — si no, reporte descartado.
3. `pageUrl` http(s) con host; si falta/invalida → el hallazgo conserva la
   `pageUrl` de la página actual como referer (nunca una URL inyectada).
4. Tipos estrictos por campo con defaults seguros (ver `data-model.md`
   §VideoReport); strings recortadas a su límite; números fuera de rango →
   campo omitido (no invalida el reporte).
5. `headers`: nombre `^[A-Za-z0-9-]+$`, valor sin `\r\n`/NUL, máx. 32
   entradas; se descartan `Host`, `Content-Length`, `Range`, `Sec-Fetch-*` y
   `User-Agent`/`Referer`/`Cookie`/`Origin` (capa gestionada, R8).
6. `subtitles`: cada `url` http(s); formato por extensión
   (`.vtt|.srt|.ttml|.dfxp`) vía `SubtitleTrackFilter`; no soportadas →
   omitidas sin error (FR-020).
7. Todo rechazo es loggable solo con `logger` en DEBUG (igual que hoy).

## 4. Qué reporta el script

| Técnica | Qué detecta | Cuándo |
|---|---|---|
| Hook `HTMLMediaElement.src`/setAttribute/`load()` (solo document-start) | URL vigente + `mediaKey`, mutaciones de fuente (FR-005) | instantáneo |
| Escaneo `<video>/<source>` + `MutationObserver` en `document.documentElement` | inserciones dinámicas, `videoWidth`×`videoHeight`, `duration`, `currentTime`, `<track>` subtítulos | por eventos, vida del documento |
| Vars de player (`jwplayer`, `videojs`) | playlist/`file` de players conocidos | sondeo acotado |
| Recaptura de posición | `currentTime` del medio vigente (`mediaKey`) | evaluación puntual al lanzar desde el panel — corrige `positionSec` obsoleto |

`mediaKey`: identificador por elemento (índice estable en el documento, p. ej.
`"v" + índiceDeAparición`). Permite que una mutación de `src` actualice el
hallazgo existente en vez de crear otro (FR-005) y localizar el elemento para
la recaptura de posición.

> **`document.cookie` no se reporta**: la fuente autoritativa de cookies es
> `CookieManager` en el momento de lanzamiento (R8). El script no envía
> `headers.Cookie` y el parser lo descartaría de todos modos — doble cierre.

## 5. Condiciones de apagado (Principio I — sin cambios respecto a spec 003)

| Trigger | Efecto |
|---|---|
| Primer hallazgo aceptado | `clearInterval` del sondeo de players |
| Backstop `setTimeout(120000)` | `clearInterval` aun sin detección |
| Navegación de documento | observer/intervalos mueren con el documento |
| `releaseWebView` | `ScriptHandler.remove()` + `removeJavascriptInterface` |

En cualquier instante hay a lo sumo 1 intervalo del sniffer por documento,
lifetime ≤ 2 min; la detección continua es por eventos. En document-start el
observer nace sobre `documentElement` (antes de `body`) y vive todo el
documento — coste por evento, no por tiempo.

## 6. Invariantes

- Una URL reportada por varias vías produce **un** hallazgo (dedup del
  registry por URL normalizada).
- Una mutación de fuente con mismo `mediaKey` **actualiza**, no duplica.
- El script nunca lanza hacia la página: secciones envueltas en try/catch;
  si `DreamWebSniffer` no existe (race en recycle) el reporte se descarta en
  JS sin error.
- 0 reportes cuya `url` no supere `VideoUrlValidator` llegan al registry —
  garantizado por el parser, aunque el script también pre-filtra http(s)
  no-blob.

## 7. Verificación

- Tests JVM/Robolectric: `VideoReportParserTest` (esquema completo, campos
  ausentes, tipos erróneos, headers/subtítulos hostiles, tamaño límite) y
  `SnifferBridgeTest` actualizado a la firma JSON.
- Manual/logcat DEBUG: página con `<video>` en HTML inicial → reporte antes
  de `onPageFinished` (document-start) o al terminar (fallback); SPA que
  muta `src` → el hallazgo se actualiza.
