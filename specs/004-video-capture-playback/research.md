# Research: Captura y Reproducción de Video Web

**Feature**: `004-video-capture-playback` | **Date**: 2026-11-23

Resolución de todas las decisiones técnicas del plan. No quedan marcadores
NEEDS CLARIFICATION: la spec ya fue clarificada (presentación bajo demanda,
lanzamiento manual, sondeo acotado, preferencia de calidad solo en sesión,
posición transportada) y el resto se decide aquí contra el código real
(`app/src/main/...`).

---

## R1. Detección temprana: document-start con fallback a onPageFinished (FR-001)

**Decisión**: inyectar el detector de video con
`WebViewCompat.addDocumentStartJavaScript(webView, script, setOf("*"))`,
guardado por `WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)`
(androidx.webkit 1.12.1 ya declarada en `libs.versions.toml`; el soporte en
runtime depende del WebView del dispositivo).

- **Si la feature está soportada** → el script corre antes que los scripts de
  la página en cada documento (main frame; orígenes `*` cubre iframes
  same-process). Esto habilita la técnica clave: envolver los setters de
  `HTMLMediaElement.src`/`setAttribute`/`load()` **antes** de que el player
  de la página exista, además del `MutationObserver` habitual.
- **Si NO está soportada** (WebView antiguo en el TV) → fallback al mecanismo
  vigente: `CustomWebClient.injectVideoSniffer` vía `evaluateJavascript` en
  `onPageFinished` (mismo cuerpo de script ampliado a JSON, degradado: sin
  hooks de setter, con sondeo acotado) + canal de red
  `shouldInterceptRequest` — que permanece activo **en ambos modos**.

La inyección ocurre tras `webViewPool.getWebView()` y antes de `loadUrl()` en
`BrowserActivity.setupWebView` (requisito de la API: el script debe
registrarse antes de la primera navegación para correr en document-start).

**Rationale**: `addDocumentStartJavaScript` es la API de plataforma
diseñada para correr scripts antes del primer recurso de la página — la
detección temprana es el objetivo central de la feature (SC-001). La
degradación por feature-gate es obligatoria porque el WebView de fábrica de
muchas TV-box no expone `DOCUMENT_START_SCRIPT`; el fallback conserva la
funcionalidad actual (SC-001 sigue exigiendo detección ≤ fin de carga para
video en HTML inicial, que el fallback cumple hoy).

**Alternatives considered**:

- *Solo document-start, sin fallback*: rechazado — dispositivos con WebView
  antiguo perderían toda la detección JS (regresión frente al estado actual).
- *ServiceWorkerController para interceptar fetch/XHR*: rechazado — cubre solo
  peticiones de service workers, no el DOM ni players embebidos.
- *Inyección por subrecurso (`shouldInterceptRequest` en el HTML)*: rechazado
  — frágil (caché, CSP, streaming de respuestas) frente a una API diseñada
  para esto.

## R2. Reporte estructurado: bridge JSON + parser JVM validado (FR-002, Principio II)

**Decisión**: `SnifferBridge.onVideoFound` pasa de `(url: String)` a
`(json: String)`; el script reporta `JSON.stringify` de un objeto
`VideoReport` (ver `contracts/video-detection-bridge.md`). El bridge delega en
un parser nuevo, **`engine/VideoReportParser`** (Kotlin, testeable), que:

- Limita el payload a 64 KB y usa `org.json.JSONObject` (org.json es API del
  framework Android; los tests que lo ejercitan corren bajo **Robolectric**,
  ya declarado y usado por `HistoryManagerTest`/`BookmarkManagerTest`).
- Valida cada URL del reporte (video, rendiciones, subtítulos) con
  `VideoUrlValidator.isVideoUrl`/`isHttpUrl` — la misma frontera que hoy.
- Sanitiza metadatos: strings acotados (título ≤ 512 chars), números con
  rango (duración/posición ≥ 0, resolución 16..8192px), headers con nombre
  `[A-Za-z0-9-]+` y valor sin caracteres de control (máx. 32 entradas),
  subtítulos máx. 8.
- Tolera campos ausentes — un reporte con solo `url` ya es válido (los campos
  no resolubles no invalidan el hallazgo, FR-002).

El callback del bridge pasa de `(String) -> Unit` a
`(VideoReport) -> Unit`; el objeto `SnifferBridge` conserva nombre de clase y
de interfaz JS (`"DreamWebSniffer"`) para no romper `WebViewPool.releaseWebView`.

**Rationale**: un hallazgo estructurado por video
(url, title, type, duration, resolution, **headers**) viaja por el bridge y
se serializa en JSON hacia el player. JSON tipado elimina el parsing posicional
del string `url|REFERER|…` (frágil ante metadatos con `|`). `org.json` evita
una dependencia nueva (kotlinx.serialization/Gson sumarían ~0.2–1MB al APK
contra el presupuesto <12MB).

**Alternatives considered**:

- *Parser JSON propio en Kotlin puro* (~150 líneas): rechazado — superficie de
  bugs en la frontera no confiable sin beneficio real (org.json bajo
  Robolectric ya cubre el gate de tests).
- *kotlinx.serialization*: rechazado — dependencia nueva + procesador de
  símbolos; peso y complejidad no justificados para un esquema de ~12 campos.
- *Varios métodos @JavascriptInterface (uno por campo)*: rechazado — más
  superficie de ataque y orden de llamadas no garantizado.

## R3. Consolidación y presentación: `VideoFindingsRegistry` + indicador + panel D-pad (FR-004…007)

**Decisión**:

1. **`engine/VideoFindingsRegistry`** (Kotlin puro, JVM-testeable): estado de
   los hallazgos de la página actual.
   - `submit(report)` → dedup por URL del stream normalizada (mismo video por
     varias vías — red/DOM/player — se funde en un hallazgo, FR-004);
     `mediaKey` (id por elemento `<video>` generado por el script) permite
     **actualizar** el hallazgo cuando la página muta su fuente (FR-005:
     misma `mediaKey` + URL distinta → reemplaza la URL del hallazgo, no crea
     otro).
   - `clear()` en cada navegación de main-frame (`onPageStarted`, mismo hook
     que ya invalida `lastDetectedVideoUrl`).
   - Capacidad acotada (64 hallazgos/página) por Principio I.
2. **Presentación** (decisión de clarificación): el `externalPlayerButton`
   existente evoluciona a **indicador** (badge con nº de hallazgos, visible
   solo si hay ≥1) que abre bajo demanda un **panel** overlay —
   `RecyclerView` (dependencia ya declarada en `libs.versions.toml`) dentro
   de un `FrameLayout` nuevo en `activity_browser.xml`, navegable con D-pad
   por foco estándar (flechas = item, CENTER = lanzar, BACK = cerrar). Sin
   diálogos automáticos ni auto-play (FR-006/FR-007).
3. El canal de red (`shouldInterceptRequest`) reporta al registry con los
   **headers reales** de `WebResourceRequest.requestHeaders` (filtrados por
   `VideoReportParser.sanitizeHeaders`), cubriendo US2-3 — headers arbitrarios
   que el JS no puede ver.

**Rationale**: un conjunto deduplicado de hallazgos vigentes por página es
el modelo que alimenta el panel. Mantener el registry como
clase JVM pura lo hace testeable (Principio IV: "detección de URLs de video"
es lógica crítica constitucional).

**Alternatives considered**:

- *Estado directo en BrowserActivity (como `lastDetectedVideoUrl` hoy)*:
  rechazado — la consolidación/dedup/update es la lógica más bug-prone de la
  feature; extraerla es la única forma de testearla sin dispositivo.
- *Leanback `BrowseFragment`/Compose*: prohibido por constitución (stack
  fijado) y sobrepeso.
- *Panel como Activity separada*: rechazado — montar sobre la navegación
  dentro de BrowserActivity evita pausar el WebView y un nuevo ciclo de vida.

## R4. Payload al player: migración a modelo tipado JSON con parse dual (FR-009)

**Decisión**: `VideoLaunchPayload` migra a modelo tipado serializado en JSON
dentro del mismo extra `EXTRA_VIDEO_URL`:

```json
{"url","referer","userAgent","cookies","headers":{...},
 "subtitles":[{"url","mimeType","language","label"}],
 "startPositionMs":12345,"mimeType":null}
```

- `VideoLaunchPayload.parse(String)` queda **dual**: si el primer carácter
  no-blank es `{` → parse JSON (`org.json`); si no → parser legado
  `url|REFERER|UA|COOKIES` vigente. El productor emite solo JSON; el parse
  legado queda como red de seguridad y mantiene verdes los tests existentes
  (`VideoLaunchPayloadTest`).
- `headers` es `Map<String,String>` (capturados por JS + requestHeaders del
  canal de red); `referer`/`userAgent`/`cookies` siguen first-class porque
  `PlayerHttpClientFactory` los trata de forma especial (ver R8).
- `startPositionMs` → `player.setMediaItem(item, position)` (decisión de
  clarificación: retomar donde iba el medio).
- `subtitles` → `MediaItem.SubtitleConfiguration` (ver R7).

**Rationale**: un modelo de análisis serializado en JSON dentro de un
único extra —con `headers:Map` y `subtitles:List`— es el formato objetivo.
La migración limpia dentro del mismo extra evita coordinar dos
extras; el parse dual preserva compatibilidad con cualquier emisor legado sin
coste de mantenimiento (15 líneas ya testeadas).

**Alternatives considered**:

- *Extra serializable/Parcelable propio*: rechazado — Parcelable exige clase
  estable y ofusca el contenido en logs/debug; JSON es legible y auditable.
- *Migración limpia sin parse dual*: rechazado — el riesgo de un payload
  legado sin parsear es un crash de lanzamiento; el dual cuesta nada.
- *Bundle de extras sueltos además del JSON*: rechazado — duplica canales;
  un solo extra JSON es más simple y auditable.

## R5. Sondeo acotado: `VideoInfoProber` sobre el pipeline TLS existente (FR-002)

**Decisión**: `managers/VideoInfoProber` — OkHttp sobre `lifecycleScope` con
**una única petición por hallazgo**, gatillada solo cuando faltan duración
**y** resolución:

- URL `.m3u8` → `GET` del manifiesto maestro (texto pequeño) y parse de
  `#EXT-X-STREAM-INF` → extrae variantes `RESOLUTION`/`BANDWIDTH` que
  **alimentan las rendiciones** del hallazgo (ver R6) además de los metadatos.
- Otras URLs → `HEAD` → `Content-Type` (formato) y `Content-Length`.
- Reutiliza el `OkHttpClient` de `PlayerHttpClientFactory` (mismo
  `DomainTrustManager` + `DomainHostnameVerifier` + headers del hallazgo) →
  respeta las excepciones TLS por dominio ya concedidas y viaja "como la
  página" (misma fidelidad que la reproducción).
- Nunca bloquea la presentación (el hallazgo se muestra con lo que haya) y se
  cancela al navegar (`Call.cancel()` + `clear()` del registry) — exactamente
  la clarificación del spec. Fallos de red/TLS del sondeo → silenciosos (sin
  diálogo; el hallazgo permanece).

**Rationale**: re-pedir la URL candidata con los headers capturados es la
vía directa para completar duración/formato cuando el DOM no los expone.
Reutilizar la factoría del player evita duplicar el pipeline TLS y garantiza
que el sondeo vea lo mismo que verá el player.

**Alternatives considered**:

- *OkHttpClient por defecto para el sondeo*: rechazado — dominios con
  excepción TLS del usuario fallarían el handshake y quedarían sin metadatos
  aunque la reproducción funcionaría.
- *Parse de moov/atom para resolución de MP4 progresivo*: rechazado — exige
  rangos múltiples y parser binario; supera "una petición acotada".
- *Sondeo en el script JS (fetch/XHR desde la página)*: rechazado — CORS lo
  bloquearía en la mayoría de orígenes; el sondeo nativo no tiene esa
  restricción.

## R6. Rendiciones y preferencia HLS + memoria de sesión (FR-016…018)

**Decisión**: lógica de agrupación dentro de `VideoFindingsRegistry`
(funciones puras, testeadas):

- **Fuentes de rendiciones**: (a) `<source>` siblings del mismo `<video>`
  (mismo `mediaKey`); (b) variantes `#EXT-X-STREAM-INF` del manifiesto HLS
  sondeado (R5); (c) hallazgos de la misma página con el mismo título no
  vacío (heurística secundaria — cubre mp4 sueltos detectados por red/DOM que
  son la misma pieza). Hallazgos con `mediaKey`/título vacío nunca se
  agrupan entre sí (evita fusionar videos distintos de una misma página).
- **Etiqueta de grupo**: `resolution` normalizada (`{height}p` cuando se
  conoce; `"Auto"` para rendiciones sin resolución — nunca se
  descartan, edge case del spec).
- **Preferencia**: dentro de un grupo de resolución, si hay varias URLs del
  mismo contenido se propone por defecto la de tipo adaptativo
  (`.m3u8`/HLS > `.mpd`/DASH > progresivo).
- **Memoria de sesión**: `managers/QualityPreferenceMemory` (objeto en
  memoria, sin disco — clarificación del spec) guarda la última etiqueta
  elegida; si coincide con una opción disponible se propone como default en
  el siguiente lanzamiento.
- **UI**: el selector de calidad aparece como sub-fila del panel solo si el
  hallazgo tiene >1 grupo de resolución (sin selector de opción única,
  escenario 4 de US4). El ABR dentro del manifiesto elegido sigue siendo de
  ExoPlayer (sin cambios).

**Alternatives considered**:

- *Agrupar por similitud de basename de URL*: rechazado — frágil ante CDNs
  con tokens opacos; el `mediaKey` + título cubre los casos reales con menos
  falsos positivos.
- *Parsear el master m3u8 en JS*: rechazado — mismo problema CORS que R5.
- *Persistir preferencia en SharedPreferences*: rechazado — prohibido por la
  clarificación (solo memoria de sesión).

## R7. Subtítulos: detección DOM + `SubtitleConfiguration` con filtro de formato (FR-019/020)

**Decisión**:

- El script detecta `<track kind="subtitles|captions">` (src, srclang, label)
  y `video.textTracks` en cada `<video>` (mismo mecanismo de
  MutationObserver + hooks del detector principal — sin bridge extra).
- Viajan en el `VideoReport` y luego en el payload (`subtitles[]`).
- **`engine/SubtitleTrackFilter`** (función pura, testeable): conserva solo
  pistas http(s) con formato soportado por Media3 — `.vtt`
  (`MimeTypes.TEXT_VTT`), `.srt` (`APPLICATION_SUBRIP`), `.ttml`/`.dfxp`
  (`APPLICATION_TTML`); el resto se omite sin afectar la reproducción
  (FR-020). Idioma/etiqueta se conservan cuando existen.
- `NativeVideoActivity` las adjunta al `MediaItem` con
  `setSubtitleConfigurations(...)`; PlayerView ya renderiza pistas de texto
  — sin código de render propio.

**Alternatives considered**:

- *Subtítulos embebidos solo por ExoPlayer (in-manifiesto)*: insuficiente —
  FR-019 exige las pistas declaradas por la página (sidecar .vtt/.srt).
- *Descargar y convertir formatos no soportados (p. ej. ASS→VTT)*: rechazado
  — peso y parsers nuevos contra Principio I; FR-020 permite omitirlos.

## R8. Contexto en el canal del player: merge de headers con precedencia (FR-009/010)

**Decisión**: `PlayerHttpClientFactory.createDataSourceFactory` pasa a
componer headers en tres capas (orden: se aplica la última):

1. **Capturados** (`payload.headers`) — sanitizados por `VideoReportParser`:
   nombres `[A-Za-z0-9-]+`, sin `Host`/`Content-Length`/`Range` ni headers
   que pisarían la capa gestionada.
2. **Gestionados**: `User-Agent` (vía `setUserAgent`), `Referer` (+ `Origin`
   derivado como hoy), `Cookie` (de `payload.cookies`), `Sec-Fetch-*`,
   `Accept: */*` — la fuente de verdad es la página (`pageUrl`/`CookieManager`
   en tiempo de lanzamiento), no lo que el JS afirme.
3. La `CookieJar` existente se conserva; las cookies de `CookieManager`
   (incluye HttpOnly a diferencia de `document.cookie`) tienen precedencia
   sobre las del reporte JS.

TLS sin cambios: `DomainTrustManager` + `DomainHostnameVerifier` +
`TlsExceptionStore` (FR-010 ya implementado en spec 001 — se conserva
íntegro, no se relaja).

**Alternatives considered**:

- *Headers capturados con precedencia sobre los gestionados*: rechazado —
  una página hostil podría inyectar `Referer`/`Cookie` falsos en el reporte
  JS; la capa gestionada proviene del navegador real.
- *Enviar `document.cookie` como header Cookie además del CookieManager*:
  rechazado — duplicaría cookies y `CookieManager` es estrictamente más
  completo.

## R9. Suspensión y restauración exacta (FR-011, SC-003)

**Decisión**: tres niveles, reutilizando el mecanismo vigente:

1. **Mientras el player está activo**: `VideoPlayerManager.launchPlayer` ya
   hace `onPause()+pauseTimers()`; se conserva. La instancia WebView no se
   destruye → DOM, scroll y estado JS sobreviven intactos; `releasePlayer`
   (al volver) hace `resumeTimers()+onResume()` — restauración exacta sin
   recarga (escenario 2 de US3). Coste extra: cero.
2. **Activity destruida por el sistema** (player encima, presión de memoria):
   `BrowserActivity` añade `onSaveInstanceState` → `webView.saveState(bundle)`
   (guarda pila + página) y en `onCreate(savedInstanceState)` →
   `webView.restoreState(bundle)`; además persiste `lastPageUrl` en el bundle
   como red si `restoreState` devuelve null.
3. **Proceso muerto** (sin bundle): degrada a la última URL navegada —
   `HistoryManager` ya persiste la última entrada; la Activity lee
   `historyManager` solo si no hay `savedInstanceState` ni URL de intent.
   Documentado como degradación aceptable del spec.

El re-lanzamiento fallido sigue revirtiendo el congelado (ya existe: catch →
`onResume`); se añade el caso "payload inválido" → misma reversión + toast.

**Alternatives considered**:

- *Solo persistir last-URL en SharedPreferences*: rechazado como vía única —
  perdería scroll/back-stack en el caso común (WebView vivo); es el nivel 3,
  no el 1.
- *Mantener un segundo WebView oculto "en pausa"*: prohibido por constitución
  (una sola instancia gestionada por el pool).
- *Serializar DOM/estado manualmente*: rechazado — `saveState` cubre el caso
  documentado sin código nuevo de serialización.

## R10. Script detector: diseño JS (detalle de implementación del contrato)

**Decisión** — `app/src/main/assets/video_detector.js` (asset; ~8 KB, el
script supera el tamaño razonable para `"""inline"""`) cargado por
`VideoSnifferInjector` y registrado según R1:

- Envoltura de `HTMLMediaElement` (`src` setter, `setAttribute('src'|'data-…')`,
  `load()`) → reporta al instante la URL vigente del medio (FR-005: mutación
  de fuente).
- `MutationObserver` sobre `document.documentElement` (en document-start
  `body` aún no existe) con `{childList, subtree, attributes, attributeFilter:
  ['src','data-src']}` → inserciones tardías `<video>/<source>/<track>`.
- Sondeo acotado de variables de player (`jwplayer`/`videojs`, conservado del
  script actual): `setInterval` con apagado al primer hallazgo y backstop de
  2 min (mismo contrato de apagado que `contracts/js-sniffer-lifecycle.md`
  de spec 003 — Principio I).
- En modo fallback (sin `DOCUMENT_START_SCRIPT`) el mismo asset se evalúa en
  `onPageFinished`; los hooks de setter se omiten (los players ya existen) y
  el sondeo cubre la detección tardía.
- Cada reporte incluye `mediaKey` (índice/UUID por elemento), `url`,
  `pageUrl`, `title` (de página o `video.title`), `mimeType`/`format` por
  extensión, `duration`, `videoWidth`×`videoHeight`, `currentTime` (posición,
  FR-002), `headers` (`document.cookie` si legible) y `subtitles[]` de
  `<track>`/`textTracks` (R7).

**Alternatives considered**:

- *Script inline en Kotlin string*: rechazado — un asset versionado es
  legible, testeable por diff y evita escapes; el coste de carga (una
  lectura de asset por WebView) es despreciable.
- *Hook de XHR/fetch para URLs de stream*: rechazado en esta iteración — el
  canal de red nativo (`shouldInterceptRequest`) ya observa esas peticiones
  con más fidelidad y sin riesgo de romper el JS de la página.

---

## Riesgos y notas

- **WebView sin `DOCUMENT_START_SCRIPT`**: cubierto por R1 (fallback
  funcional; la detección de HTML-inicial sigue garantizada por
  `onPageFinished`+red).
- **Reportes JS maliciosos**: frontera en `VideoReportParser` (tamaño, tipos,
  URLs validadas, headers sanitizados) — Principio II.
- **Páginas con muchos videos**: registry acotado a 64; la presentación es un
  RecyclerView (virtualizado).
- **m3u8 con autenticación por cookies en el manifiesto**: el sondeo viaja
  con el pipeline completo (R5/R8) — mismo contexto que el player.
- **APK**: +0 dependencias runtime; +1 asset JS (~8 KB); RecyclerView ya
  declarada (por fin con uso real, resuelve deuda de "declarada sin uso").
