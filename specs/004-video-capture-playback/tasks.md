# Tasks: Captura y Reproducción de Video Web

**Input**: Design documents from `/specs/004-video-capture-playback/`

**Prerequisites**: plan.md (required), spec.md (required), research.md, data-model.md, contracts/ (video-detection-bridge.md, playback-context-payload.md, findings-panel-ui.md, playback-session.md), quickstart.md

**Tests**: INCLUIDOS y obligatorios — la constitución (Principio IV) exige tests para la lógica crítica nueva: `VideoReportParser`, `VideoFindingsRegistry` (dedup/update/grupos/preferencia HLS), `SubtitleTrackFilter`, parse dual de `VideoLaunchPayload` (JSON + legado) y merge de headers en `PlayerHttpClientFactory`. Se escriben TODOS antes de la implementación (Phase 2), en estado rojo. UI/WebView exenta de tests obligatorios; su verificación es manual vía escenarios quickstart E0–E6.

**Decisions applied (no reopen)**: parser JSON con `org.json` de Android (tests bajo Robolectric, patrón `HistoryManagerTest`); agrupación de rendiciones por `mediaKey`/manifiesto + heurística secundaria de título idéntico no vacío — solo agrupa cuando difieren en resolución/formato (guard contra falsos positivos).

**Organization**: Tasks agrupadas por user story (US1–US5 de spec.md) en orden de prioridad P1 → P2 → P3, cada fase independientemente verificable.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Puede ejecutarse en paralelo (archivos distintos, sin dependencias de tareas incompletas)
- **[Story]**: Historia a la que pertenece (US1–US5); Setup/Tests/Polish no llevan label
- Todas las rutas son exactas; app Android de módulo único `:app`, paquete `com.example.dreamweb`

## Path Conventions

- Código: `app/src/main/java/com/example/dreamweb/{engine,managers,security,ui}/`
- Assets: `app/src/main/assets/`
- Layouts: `app/src/main/res/layout/`
- Tests: `app/src/test/java/com/example/dreamweb/{engine,managers,security}/`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Modelos de dominio compartidos por todos los componentes nuevos — el vocabulario común que los tests de Phase 2 referencian (data-model.md). Sin lógica: solo tipos.

- [X] T001 Create domain models per data-model.md in `app/src/main/java/com/example/dreamweb/engine/VideoModels.kt`: `data class VideoReport` (url, pageUrl, `title` ≤512 chars, `mimeType: String?`, `format: StreamFormat`, `widthPx`/`heightPx: Int?` rango 16..8192, `durationSec`/`positionSec: Double?` ≥0, `headers: Map<String,String>` ≤32 entradas, `subtitles: List<SubtitleTrack>` ≤8, `mediaKey: String?`, `source: DetectionSource`); `enum class StreamFormat { HLS, DASH, PROGRESSIVE, UNKNOWN }`; `enum class DetectionSource { DOM, NETWORK }`; `data class VideoFinding` (id estable, pageUrl, title, `primaryRendition`, `renditions` ≥1, durationSec, positionSec, headers, subtitles, `probed: Boolean = false`); `data class Rendition` (url, format, `resolutionLabel` `"{height}p"` | `"Auto"`, heightPx, bandwidthBps); `data class SubtitleTrack` (url http(s), mimeType, language?, label?)
- [X] T002 [P] Create `object QualityPreferenceMemory` in `app/src/main/java/com/example/dreamweb/managers/QualityPreferenceMemory.kt` — `var lastResolutionLabel: String?`; vive solo en memoria de sesión: MUST NOT persistir en disco (FR-018)

**Checkpoint**: `./gradlew assembleDebug` compila con los modelos disponibles para tests e implementación.

---

## Phase 2: Tests — Lógica Crítica (PRIMERO, estado rojo)

**Purpose**: Todos los tests de lógica crítica escritos ANTES de cualquier implementación (decisión del usuario + Principio IV). Cada test define la API que la implementación deberá satisfacer.

> **NOTE: Los tests DEBEN fallar en este punto** — las clases/APIs nuevas aún no existen; el fallo de compilación es el estado rojo esperado. Los tests existentes no tocados (`VideoLaunchPayloadTest`, `VideoUrlValidatorTest`, `HistoryManagerTest`…) deben permanecer intactos.

- [X] T003 [P] Create `app/src/test/java/com/example/dreamweb/engine/VideoReportParserTest.kt` (Robolectric: `@RunWith(RobolectricTestRunner::class)` + `@Config(sdk = [34])`, patrón de `HistoryManagerTest`) — cubrir `VideoReportParser.parse(json)`: reporte mínimo `{"url","pageUrl"}` válido; esquema completo; campos ausentes → defaults; tipos erróneos → campo omitido sin invalidar el reporte; payload >64 KB → descartado; JSON no-objeto/malformado → null; `url` que no supera `VideoUrlValidator.isVideoUrl` → rechazado; `pageUrl` no-http → se conserva la pageUrl actual como referer, nunca una inyectada; `title` >512 → truncado; `width`/`height` fuera de 16..8192 → omitidos; `durationSec`/`positionSec` <0 → omitidos; `headers`: nombre fuera de `^[A-Za-z0-9-]+$` o valor con `\r\n`/NUL → entrada descartada, máx 32 entradas, `Host`/`Content-Length`/`Range`/`User-Agent`/`Referer`/`Cookie`/`Origin`/`Sec-Fetch-*` excluidos siempre; `subtitles` >8 → recortado, url no-http o formato no soportado → pista omitida sin error
- [X] T004 [P] Create `app/src/test/java/com/example/dreamweb/engine/VideoFindingsRegistryTest.kt` (JVM puro, JUnit4) — cubrir `VideoFindingsRegistry`: dedup por URL de stream normalizada (misma URL por vía DOM+NETWORK → un hallazgo; funde campos vacíos, nunca pisa datos resueltos, salvo `positionSec` que siempre refleja el último valor observado); mutación de fuente por `mediaKey` (misma mediaKey + URL distinta → reemplaza `primaryRendition.url` y resetea `probed`, no crea otro hallazgo); `clear()` vacía la página; capacidad acotada a 64 hallazgos; agrupación por resolución — fuentes: (a) `<source>` siblings del mismo `mediaKey`, (b) variantes `#EXT-X-STREAM-INF` del manifiesto, (c) heurística de título idéntico no vacío SOLO cuando difieren en resolución/formato (guard: mismo título + misma resolución + mismo formato NO agrupa; hallazgos con mediaKey/título vacío nunca se agrupan entre sí); etiquetas `"{height}p"` o `"Auto"` para rendiciones sin resolución (nunca se descartan); preferencia por defecto HLS > DASH > progresivo a igual resolución; `QualityPreferenceMemory.lastResolutionLabel` propone el default cuando coincide con una opción del grupo
- [X] T005 [P] Create `app/src/test/java/com/example/dreamweb/engine/SubtitleTrackFilterTest.kt` (JVM puro) — solo pistas `.vtt`/`.srt`/`.ttml`/`.dfxp` con URL http(s) sobreviven con su mimeType Media3 (`TEXT_VTT`/`APPLICATION_SUBRIP`/`APPLICATION_TTML`); `.ass`, esquemas no-http y URLs inválidas se omiten sin error; language/label conservados cuando existen
- [X] T006 [P] Create `app/src/test/java/com/example/dreamweb/managers/VideoLaunchPayloadJsonTest.kt` (Robolectric, mismo patrón) — cubrir `VideoLaunchPayload`: roundtrip del productor JSON v2 (`v`, url, referer, userAgent, cookies, `headers` mapa, `subtitles` lista, `startPositionMs`, `mimeType`); campos ausentes → defaults; campo desconocido → ignorado; `startPositionMs` negativo → 0; parse dual — entrada que empieza por `{` tras trim → JSON, cualquier otra → parser legado `url|REFERER|UA|COOKIES` intacto; JSON malformado o sin `url` http(s) válida → `null`
- [X] T007 [P] Create `app/src/test/java/com/example/dreamweb/security/PlayerHttpClientFactoryTest.kt` (Robolectric — el merge deriva `Origin` con `android.net.Uri`) — cubrir el merge de headers por capas vía la función testeable que expone la implementación: `payload.headers` capturados aplicados; los nombres gestionados (`User-Agent`, `Referer`, `Origin`, `Cookie`, `Sec-Fetch-*`, `Host`, `Content-Length`, `Range`) NUNCA pueden ser pisados por headers capturados; `referer` → header `Referer` + `Origin` derivado `scheme://host`; `Sec-Fetch-Mode: cors`, `Sec-Fetch-Site: cross-site`, `Accept: */*` fijos; `cookies` → header `Cookie` y alimentan la `CookieJar`
- [X] T008 [P] Update `app/src/test/java/com/example/dreamweb/engine/SnifferBridgeTest.kt` a la firma JSON — `onVideoFound(json)` con callback `(VideoReport) -> Unit`: JSON con url de video válida → callback con reporte parseado; JSON malformado, url rechazada por el validador, y URLs maliciosas (`javascript:`, `data:`, `blob:`) → sin callback; `release()` → no-op aunque llegue reporte válido
- [X] T009 Run `./gradlew test` y confirmar el estado rojo: los únicos motivos de fallo son las APIs nuevas aún inexistentes (`VideoReportParser`, `VideoFindingsRegistry`, `SubtitleTrackFilter`, `VideoLaunchPayload` JSON dual, firma JSON del bridge, merge de headers); anotar como baseline antes de implementar

**Checkpoint**: Suite en rojo por APIs nuevas — la implementación puede empezar. Ningún test existente fue debilitado para compilar.

---

## Phase 3: User Story 1 — Descubrir el video de la página y lanzarlo al reproductor (Priority: P1) 🎯 MVP

**Goal**: Detección lo antes posible (document-start con fallback), hallazgo estructurado consolidado sin duplicados, presentación bajo demanda (indicador + panel D-pad) y lanzamiento manual al player propio a pantalla completa.

**Independent Test**: Página con `<video>` en HTML inicial → indicador con nº ≥1 al terminar la carga → abrir panel con D-pad → fila con título/formato/resolución/duración → CENTER → `NativeVideoActivity` reproduce a pantalla completa (quickstart E1a; página sin video → 0 hallazgos, E1a paso 4).

### Implementation for User Story 1

- [X] T010 [P] [US1] Create `app/src/main/assets/video_detector.js` (~8 KB) per `contracts/video-detection-bridge.md` §4 + research R10: hooks de `HTMLMediaElement` (`src` setter, `setAttribute('src'|'data-*')`, `load()`) solo en modo document-start; `MutationObserver` sobre `document.documentElement` con `{childList, subtree, attributes, attributeFilter: ['src','data-src']}`; sondeo de players (`jwplayer`, `videojs`) con `setInterval` + `clearInterval` al primer hallazgo y backstop `setTimeout(120000)`; reporte `DreamWebSniffer.onVideoFound(JSON.stringify(report))` con campos `url`, `pageUrl`, `title`, `mimeType`, `width`, `height`, `durationSec`, `positionSec` (currentTime), `headers` (sin `Cookie` — `CookieManager` es la fuente autoritativa en el lanzamiento, contrato §4 nota), `subtitles[]`, `mediaKey` (`"v"+índice de aparición`); pre-filtro http(s) no-blob; cada sección envuelta en try/catch; el reporte se descarta sin error si `DreamWebSniffer` no existe; flag de modo para desactivar los hooks de setters en modo fallback
- [X] T011 [P] [US1] Create `app/src/main/java/com/example/dreamweb/engine/SubtitleTrackFilter.kt` — función pura testeable: conserva pistas con URL http(s) y extensión `.vtt`/`.srt`/`.ttml`/`.dfxp` mapeadas a mimeType Media3 (`TEXT_VTT`, `APPLICATION_SUBRIP`, `APPLICATION_TTML`); el resto se omite en silencio (FR-020); `language`/`label` se conservan cuando existen
- [X] T012 [US1] Create `app/src/main/java/com/example/dreamweb/engine/VideoReportParser.kt` — `parse(json: String): VideoReport?` con `org.json` aplicando la cadena de validación del contrato §3 en orden: tamaño ≤64 KB, objeto bien formado, `url` que supere `VideoUrlValidator.isVideoUrl`, `pageUrl` http(s) (si falta/invalida → pageUrl actual de la página como referer), tipos estrictos con defaults y rangos (title ≤512, width/height 16..8192, duration/position ≥0), `headers` sanitizados (nombres `^[A-Za-z0-9-]+$`, valores sin `\r\n`/NUL, máx 32, sin `Host`/`Content-Length`/`Range`/`Sec-Fetch-*`/`User-Agent`/`Referer`/`Cookie`/`Origin`), `subtitles` vía `SubtitleTrackFilter` (máx 8, cada `url` validada http(s) — exponer `isHttpUrl` en `VideoUrlValidator` o validación equivalente interna para `pageUrl`/subtítulos); expone `sanitizeHeaders` reutilizable por el canal de red; todo rechazo loggeable solo vía `logger` en DEBUG
- [X] T013 [US1] Modify `app/src/main/java/com/example/dreamweb/engine/SnifferBridge.kt` — `onVideoFound(json: String)` delega en `VideoReportParser.parse`; el callback pasa de `(String) -> Unit` a `(VideoReport) -> Unit`; se conservan el nombre de clase, el nombre JS `"DreamWebSniffer"`, `logger` y `release()` (superficie de ataque: un solo método)
- [X] T014 [US1] Create `app/src/main/java/com/example/dreamweb/engine/VideoSnifferInjector.kt` — carga `video_detector.js` del asset una vez; `install(webView)`: si `WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)` → `WebViewCompat.addDocumentStartJavaScript(webView, script, setOf("*"))` guardando el `ScriptHandler` devuelto; expone si el modo document-start está activo para decidir el fallback; `uninstall(webView)`: `ScriptHandler.remove()` simétrico
- [X] T015 [P] [US1] Create `app/src/main/java/com/example/dreamweb/engine/VideoFindingsRegistry.kt` — Kotlin puro testeable: `submit(report: VideoReport)` con dedup por URL de stream normalizada (funde campos vacíos, nunca pisa resueltos, `positionSec` siempre del último reporte), actualización por `mediaKey` (misma mediaKey + URL distinta → reemplaza URL del hallazgo y resetea `probed`, FR-005), `clear()` y capacidad acotada a 64 (Principio I); expone snapshot inmutable de `List<VideoFinding>` para la UI; en esta fase cada hallazgo lleva su rendición inicial (la agrupación multi-rendición llega en T029/US4)
- [X] T016 [US1] Modify `app/src/main/java/com/example/dreamweb/engine/CustomWebClient.kt` — `shouldInterceptRequest`: url que pasa `VideoUrlValidator` → construir `VideoReport` con `source = NETWORK`, `pageUrl = lastPageUrl` (jamás `view.url` en hilo worker) y `headers = request.requestHeaders` sanitizados por `VideoReportParser.sanitizeHeaders` → `onVideoDetected(VideoReport)` (firma del callback actualizada); `onPageFinished`: cuando el injector reporta que document-start NO está soportado → `evaluateJavascript` del asset en modo fallback (sustituye al `injectVideoSniffer` inline legado); `onPageStarted` sigue disparando el callback de invalidación
- [X] T017 [US1] Modify `app/src/main/java/com/example/dreamweb/engine/WebViewPool.kt` — `releaseWebView` invoca `VideoSnifferInjector.uninstall(webView)` (`ScriptHandler.remove()`) además del `removeJavascriptInterface("DreamWebSniffer")` vigente — limpieza simétrica (Principio V)
- [X] T018 [P] [US1] Create `app/src/main/res/layout/item_video_finding.xml` — fila de hallazgo per `contracts/findings-panel-ui.md` §3: etiqueta de formato, título truncado, `resolución · duración` formateadas cuando existan, línea `subtítulos: ES · EN` solo si hay pistas, y contenedor de sub-fila `▸ Calidad:` (vacío hasta US4); focusable para D-pad
- [X] T019 [P] [US1] Modify `app/src/main/res/layout/activity_browser.xml` — `externalPlayerButton` evoluciona a `videoIndicatorButton` (`Button`/`TextView` — debe renderizar el texto con nº de hallazgos, no `ImageButton`; badge ~80dp en esquina, `visibility` iff hallazgos ≥1, `translationZ` bajo el cursor virtual) + nuevo `findingsPanel` `FrameLayout` overlay (~35% ancho, lateral derecho, contiene `RecyclerView`, `visible` iff abierto por el usuario, `translationZ` por encima de `webView` **y del cursor virtual** — panel visible completo)
- [X] T020 [US1] Create `app/src/main/java/com/example/dreamweb/ui/VideoFindingsAdapter.kt` — `RecyclerView.Adapter` sobre `VideoFinding`: título vacío → host del stream; campos ausentes no bloquean la fila; actualización de metadatos → `notifyItemChanged` sin mover el foco; la fila refleja el stream vigente tras una mutación de fuente
- [X] T021 [P] [US1] Modify `app/src/main/java/com/example/dreamweb/managers/VideoLaunchPayload.kt` — modelo ampliado: `headers: Map<String,String>`, `subtitles: List<SubtitleTrack>`, `startPositionMs: Long` (≥0), `mimeType: String?` (defaults seguros); `toJson()` productor único del esquema v2 de `contracts/playback-context-payload.md` §1; `parse()` dual — primer char no-blank `{` → JSON (defaults, desconocidos ignorados, `startPositionMs`<0→0, sin `url` http(s) → `null`), si no → parser legado `url|REFERER|UA|COOKIES` sin cambios
- [X] T022 [US1] Modify `app/src/main/java/com/example/dreamweb/ui/BrowserActivity.kt` — cableado de detección: `VideoSnifferInjector.install(webView)` tras `webViewPool.getWebView()` y ANTES de `loadUrl()`; `SnifferBridge { report -> registry.submit(report); refreshIndicator() }`; `CustomWebClient` actualizado (network → registry); `onPageStartedCallback` → `registry.clear()` + cerrar panel + indicador GONE; eliminar el código legado `lastDetectedVideoUrl`/`onVideoFound(String)`
- [X] T023 [US1] Modify `app/src/main/java/com/example/dreamweb/ui/BrowserActivity.kt` — panel y lanzamiento: click del indicador / `KEYCODE_MENU` → abre `findingsPanel` con foco en la 1ª fila; `KEYCODE_BACK` con panel abierto → cierra el panel antes que el historial web; `CENTER` en fila → **recaptura acotada de posición**: `evaluateJavascript` puntual lee `currentTime` del medio con el `mediaKey` del hallazgo (contrato §4 "Recaptura de posición"; si no responde → `positionSec` del hallazgo; timeout ~300 ms no bloquea el lanzamiento) → construye `VideoLaunchPayload.toJson()` desde el hallazgo (`url` = `primaryRendition.url`, `referer` = `pageUrl`, `userAgent` = `webView.settings.userAgentString`, `cookies` = `CookieManager.getCookie(pageUrl)`, `headers`/`subtitles`/`startPositionMs` = `positionSec`×1000/`mimeType` del hallazgo) → cierra el panel → `VideoPlayerManager.launchPlayer(webView, payloadJson)`; actualizar `dispatchKeyEvent.nativeViews` con el nuevo id `videoIndicatorButton`; fullscreen web (`onFullscreenChanged`) → indicador y panel GONE; nada de diálogos automáticos ni auto-play (FR-006/FR-007)

**Checkpoint**: `./gradlew test` en verde para T003–T008 salvo los casos de agrupación que dependen de US4; `./gradlew assembleDebug` OK. La historia es verificable de extremo a extremo con quickstart E1a/E1b.

---

## Phase 4: User Story 2 — Reproducción fiel al contexto de la página (Priority: P2)

**Goal**: El player aplica el contexto completo del hallazgo — headers arbitrarios capturados, referer/origen, cookies de sesión, posición de inicio — a manifiesto y segmentos, con el pipeline TLS por dominio intacto.

**Independent Test**: Stream que devuelve 403 sin `Referer` y 200 con él → reproduce; página con sesión por cookies → las cookies acompañan las peticiones del stream; certificado no confiable → diálogo por dominio, nunca aceptado automática/globalmente (quickstart E2, escenarios US2-1…4).

### Implementation for User Story 2

- [X] T024 [US2] Modify `app/src/main/java/com/example/dreamweb/security/PlayerHttpClientFactory.kt` — merge de headers en 3 capas per `contracts/playback-context-payload.md` §3 (orden de aplicación, la última gana): 1) `payload.headers` capturados ya sanitizados (sin `Host`/`Content-Length`/`Range` ni nombres gestionados), 2) gestionados autoritativos — `User-Agent` vía `setUserAgent(payload.userAgent)`, `Referer` + `Origin` derivado de `payload.referer`, `Cookie` de `payload.cookies`, `Sec-Fetch-Mode: cors`, `Sec-Fetch-Site: cross-site`, `Accept: */*`, 3) `CookieJar` vigente; extraer el merge a una función testeable (la que cubre T007); extraer la construcción del `OkHttpClient` a `createClient(payload)` reutilizable por `VideoInfoProber` (US4); `DomainTrustManager` + `DomainHostnameVerifier` + `TlsExceptionStore` intactos (FR-010)
- [X] T025 [P] [US2] Modify `app/src/main/java/com/example/dreamweb/ui/NativeVideoActivity.kt` — consumo del payload v2: `MediaItem.Builder().setUri(payload.url).setMimeType(payload.mimeType)` (hint; la extensión manda si falta); `player.setMediaItem(item, payload.startPositionMs)` solo si >0 — sin `seekTo(0)` redundante; errores claros FR-012: stream caducado/denegado/red → toast "no se pudo reproducir (caducado o sin acceso); recarga la página" + `finish()`; DRM comercial → toast "contenido protegido no soportado" + `finish()`; diálogo TLS por dominio vigente sin cambios

**Checkpoint**: `PlayerHttpClientFactoryTest` verde; `./gradlew test` completo en verde. Verificable con quickstart E2 (P-D referer/cookies, header custom, P-F TLS por dominio).

---

## Phase 5: User Story 3 — La navegación queda liberada y se restaura al volver (Priority: P2)

**Goal**: Motor de navegación suspendido mientras el player está activo (presupuesto ≤180 MB) y restauración exacta al volver — misma página, misma posición — con degradación documentada a `saveState` y a última URL del historial.

**Independent Test**: Player activo → `dumpsys meminfo` ≤180 MB y par `pauseTimers`/`resumeTimers` en logcat; BACK → misma URL/scroll/estado sin recarga; "Don't keep activities" o muerte de proceso → restauración por `saveState` o al menos la última URL navegada, nunca crash ni pantalla en blanco (quickstart E3).

### Implementation for User Story 3

- [X] T026 [US3] Modify `app/src/main/java/com/example/dreamweb/managers/VideoPlayerManager.kt` — `launchPlayer(webView, payloadJson)` valida ANTES de congelar: `VideoLaunchPayload.parse(payloadJson)` no-null y `url` http(s) válida; payload inválido → toast + return sin `onPause`/`pauseTimers` ni latch (escenario "fallo al lanzar" del spec); conservar la reversión completa vigente ante excepción de `startActivity` (`onResume` + `resumeTimers` + latch libre); `releasePlayer` sigue siendo idempotente
- [X] T027 [US3] Modify `app/src/main/java/com/example/dreamweb/ui/BrowserActivity.kt` — `onSaveInstanceState` → `webView.saveState(outState)` + `outState.putString("lastPageUrl", webView.url)`; `onCreate` con `savedInstanceState` → `webView.restoreState(savedInstanceState)`; si devuelve null → `loadUrl(lastPageUrl)` como red de seguridad (contracts/playback-session.md §4)
- [X] T028 [US3] Modify `app/src/main/java/com/example/dreamweb/ui/BrowserActivity.kt` — muerte de proceso: `onCreate` sin `savedInstanceState` y sin extra `URL` → `lifecycleScope.launch { historyManager.getRecentHistory(1) }` → `loadUrl` de la última entrada navegada; nunca pantalla en blanco (SC-003, US3 esc.3)

**Checkpoint**: Restauración en 3 niveles implementada; verificable con quickstart E3 (meminfo ≤180 MB, BACK sin recarga, saveState tras "Don't keep activities", última URL tras kill).

---

## Phase 6: User Story 4 — Elegir la calidad entre las rendiciones del video (Priority: P2)

**Goal**: Rendiciones del mismo video agrupadas por resolución (una entrada por calidad, no por URL), preferencia HLS como propuesta por defecto, selector de calidad solo cuando hay >1 grupo y memoria de la última elección solo en sesión.

**Independent Test**: Página que expone el mismo video como mp4 480p/720p + `master.m3u8` → el panel muestra una opción por resolución con default HLS → elegir 480p → reproduce esa calidad → siguiente lanzamiento de la sesión propone 480p por defecto (quickstart E4; video de una sola rendición → sin selector).

### Implementation for User Story 4

- [X] T029 [US4] Modify `app/src/main/java/com/example/dreamweb/engine/VideoFindingsRegistry.kt` — agrupación de rendiciones per data-model.md + decisión del usuario: fuentes (a) `<source>` siblings del mismo `mediaKey`, (b) variantes `#EXT-X-STREAM-INF` aportadas por el sondeo, (c) heurística secundaria de título idéntico no vacío — SOLO agrupa cuando difieren en resolución/formato (guard: igual título + igual resolución + igual formato NO agrupa; mediaKey/título vacío nunca se agrupan entre sí); etiqueta `"{height}p"` o `"Auto"` para sin resolución (nunca se descartan); dentro del grupo la propuesta por defecto es HLS > DASH > progresivo (FR-017); `QualityPreferenceMemory.lastResolutionLabel` propone el default cuando coincide (FR-018)
- [X] T030 [P] [US4] Create `app/src/main/java/com/example/dreamweb/managers/VideoInfoProber.kt` — una única petición acotada por hallazgo, gatillada solo cuando faltan duración Y resolución: `.m3u8` → `GET` del manifiesto maestro → parse de `#EXT-X-STREAM-INF` (`RESOLUTION`/`BANDWIDTH`) → variantes que alimentan las rendiciones + metadatos; otras URLs → `HEAD` → `Content-Type`/`Content-Length` (limitación conocida: para progresivos no recupera duración ni resolución — el hallazgo se presenta igual con los campos disponibles, FR-002); reutiliza `PlayerHttpClientFactory.createClient(payload)` con los headers del hallazgo (misma fidelidad que el player, R5); nunca bloquea la presentación; `Call.cancel()` al navegar; fallos de red/TLS silenciosos (el hallazgo permanece)
- [X] T031 [P] [US4] Modify `app/src/main/res/layout/item_video_finding.xml` — sub-fila `▸ Calidad:` con una opción por etiqueta de resolución (`Auto`, `720p`, `480p`…), nunca por URL cruda; visible solo si el hallazgo tiene >1 grupo de resolución (US4 esc.4)
- [X] T032 [US4] Modify `app/src/main/java/com/example/dreamweb/ui/VideoFindingsAdapter.kt` — sub-fila de calidades operable con D-pad per `contracts/findings-panel-ui.md` §4: izquierda/derecha expande y recorre opciones; opción por defecto = `QualityPreferenceMemory.lastResolutionLabel` si existe en el grupo, si no la preferida por formato (HLS > DASH > progresivo); `CENTER` en opción → selecciona y lanza esa rendición; `CENTER` en fila principal → lanza `primaryRendition`
- [X] T033 [US4] Modify `app/src/main/java/com/example/dreamweb/ui/BrowserActivity.kt` — wiring de sondeo y selección: tras `registry.submit`, si al hallazgo le faltan duración Y resolución → `VideoInfoProber.probe` en `lifecycleScope` (cancelar todo sondeo en `onPageStarted`/`clear()`); resultado del sondeo → actualiza el hallazgo en el registry → `notifyItemChanged` sin mover el foco; confirmación de calidad → escribe `QualityPreferenceMemory.lastResolutionLabel` y construye el payload con la rendición elegida

**Checkpoint**: `VideoFindingsRegistryTest` completo en verde (agrupación + preferencia + memoria); verificable con quickstart E4 (P-C agrupada, default HLS, elección aplicada, memoria de sesión).

---

## Phase 7: User Story 5 — Subtítulos de la página disponibles en el reproductor (Priority: P3)

**Goal**: Las pistas de subtítulos declaradas por la página (`<track>`/`textTracks`) viajan en el hallazgo y el payload hasta el player, donde aparecen como pistas seleccionables; los formatos no soportados se omiten sin afectar la reproducción.

**Independent Test**: Página con `<track kind="subtitles" src="…vtt">` → en el player la pista aparece seleccionable y se renderiza; página sin pistas → reproducción sin oferta de pistas ni error; pista `.ass` → omitida sin afectar el video ni otras pistas (quickstart E5, FR-019/FR-020).

### Implementation for User Story 5

- [X] T034 [US5] Modify `app/src/main/assets/video_detector.js` — detectar `<track kind="subtitles|captions">` (src, srclang, label) en cada `<video>` y sus `video.textTracks`, tanto en el escaneo inicial como vía `MutationObserver`, poblando el campo `subtitles[]` del reporte JSON (research R7, contracts/video-detection-bridge.md §4)
- [X] T035 [US5] Modify `app/src/main/java/com/example/dreamweb/ui/NativeVideoActivity.kt` — `payload.subtitles` → lista de `MediaItem.SubtitleConfiguration` (uri, mimeType, language, label) → `MediaItem.Builder().setSubtitleConfigurations(...)`; lista vacía → sin oferta de pistas ni error (PlayerView ya renderiza pistas de texto)

**Checkpoint**: Pipeline de subtítulos completo JS→parser→registry→payload→player; verificable con quickstart E5.

---

## Phase 8: Polish & Cross-Cutting Concerns

**Purpose**: Validación integral y cierre de la feature.

- [X] T036 Run `./gradlew assembleDebug` y `./gradlew test` — build OK y suite completa en verde incluidos los tests preexistentes (`VideoUrlValidatorTest`, `VideoLaunchPayloadTest` legado, `HistoryManagerTest`, `BookmarkManagerTest`, `SnifferBridgeTest` actualizado) — quickstart E0
- [X] T037 [P] Update `README.md` — la sección del extractor/reproductor refleja el estado real por sección (constitución): detección document-start + fallback, hallazgo estructurado, indicador + panel D-pad, contexto completo al player, suspensión/restauración, agrupación de calidades con preferencia HLS, subtítulos; reclasificar "experimental" → verificado por quickstart manual
- [X] T038 Run validación manual de `specs/004-video-capture-playback/quickstart.md` E1a–E6 con páginas P-A…P-F servidas localmente: E1a detección/indicador/panel/multi-video + E1b fallback; E2 referer/cookies/header custom/TLS por dominio; E3 `dumpsys meminfo` ≤180 MB + restauración en 3 niveles + FR-012; E4 agrupación/default HLS/memoria de sesión; E5 pistas seleccionables; E6 mutación de fuente + URL caducada; tabla SC completa incluida SC-008 (0 peticiones con metadatos a hosts ajenos, por inspección de tráfico); registrar resultados en quickstart o notas de la feature
- [X] T039 Final constitution sweep en el diff: limpieza simétrica (`ScriptHandler.remove()`, `removeJavascriptInterface`, scopes de corrutinas cancelables), sin trust-all ni excepciones TLS globales, `QualityPreferenceMemory` solo en RAM, 0 dependencias runtime nuevas en `gradle/libs.versions.toml`, cero auto-play / cero diálogos automáticos, `NativeVideoActivity` sigue `exported="false"`, logging solo DEBUG

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: Sin dependencias — empieza ya
- **Tests (Phase 2)**: Depende de Setup (los tests referencian los modelos) — **BLOQUEA** toda implementación: los tests se escriben primero y quedan en rojo
- **User Stories (Phases 3–7)**: Dependen de Setup + Tests; se ejecutan **secuencialmente en orden de prioridad** (orden requerido por el usuario)
- **Polish (Phase 8)**: Depende de todas las historias completadas

### User Story Dependencies

- **US1 (P1, Phase 3)**: Tras Setup+Tests — sin dependencias de otras historias; entrega el MVP (detectar → presentar → lanzar)
- **US2 (P2, Phase 4)**: Depende de US1 (el productor del payload JSON y la ruta de lanzamiento ya existen; US2 consume `headers`/`startPositionMs`/`mimeType` en el lado player)
- **US3 (P2, Phase 5)**: Depende de US1 (extiende `launchPlayer`/`BrowserActivity` ya cableados); independiente de US2
- **US4 (P2, Phase 6)**: Depende de US1 (registry + panel + lanzamiento) y de US2 (`PlayerHttpClientFactory.createClient` reutilizado por `VideoInfoProber`)
- **US5 (P3, Phase 7)**: Depende de US1 (parser ya acepta `subtitles[]` vía `SubtitleTrackFilter` y el payload ya los serializa; US5 completa la detección JS y el consumo en el player)

### Within Each User Story

- Modelos (Setup) → lógica pura (parser, filter, registry, payload) → glue Android (bridge, injector, client, factory, adapter) → integración en `BrowserActivity`/`NativeVideoActivity`
- `video_detector.js` (asset) antes que `VideoSnifferInjector` (lo carga) y antes que el fallback en `CustomWebClient`
- `SubtitleTrackFilter` antes que `VideoReportParser` (el parser lo usa para sanitizar `subtitles[]`)
- Layouts del panel antes que `VideoFindingsAdapter` y que el wiring de `BrowserActivity`
- `VideoFindingsRegistry` (agrupación T029) y `VideoInfoProber` antes que el wiring de sondeo/selección en `BrowserActivity` (T033)

### Parallel Opportunities

- Setup: T002 en paralelo con T001
- Tests: T003, T004, T005, T006, T007, T008 — 6 archivos de test distintos, todos en paralelo
- US1: T010 (asset JS), T011 (filter), T015 (registry), T018+T019 (layouts), T021 (payload) — en paralelo entre sí una vez listos los modelos
- US2: T024 y T025 en paralelo (factory vs activity)
- US4: T030 (prober) y T031 (layout) en paralelo con T029 (registry)
- Las historias se implementan secuencialmente por decisión del usuario (P1 → P2 → P3); en un equipo con más capacidad, US3 podría solaparse con US2 tras US1

---

## Parallel Example: User Story 1

```bash
# Lógica pura y assets en paralelo (archivos distintos, sin dependencias mutuas):
Task: "Create app/src/main/assets/video_detector.js"
Task: "Create app/src/main/java/com/example/dreamweb/engine/SubtitleTrackFilter.kt"
Task: "Create app/src/main/java/com/example/dreamweb/engine/VideoFindingsRegistry.kt"
Task: "Modify app/src/main/java/com/example/dreamweb/managers/VideoLaunchPayload.kt"

# Layouts del panel en paralelo:
Task: "Create app/src/main/res/layout/item_video_finding.xml"
Task: "Modify app/src/main/res/layout/activity_browser.xml"
```

## Parallel Example: Tests (Phase 2)

```bash
# Los 6 tests de lógica crítica se escriben en paralelo (archivos distintos):
Task: "VideoReportParserTest.kt (Robolectric)"
Task: "VideoFindingsRegistryTest.kt (JVM)"
Task: "SubtitleTrackFilterTest.kt (JVM)"
Task: "VideoLaunchPayloadJsonTest.kt (Robolectric)"
Task: "PlayerHttpClientFactoryTest.kt (Robolectric)"
Task: "SnifferBridgeTest.kt (update firma JSON)"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Completar Phase 1: Setup (modelos)
2. Completar Phase 2: Tests en rojo (obligatorio)
3. Completar Phase 3: US1 — detección + panel + lanzamiento
4. **STOP y VALIDAR**: `./gradlew test` + quickstart E1a/E1b en dispositivo TV
5. Demo/deliverable: cualquier página con video se vuelve reproducible en el player nativo

### Incremental Delivery

1. Setup + Tests → baseline roja documentada
2. US1 → MVP: detección, presentación y lanzamiento funcionales (E1)
3. US2 → fidelidad de contexto: los streams "solo dentro de la página" reproducen (E2)
4. US3 → restauración exacta y presupuesto de RAM verificado (E3)
5. US4 → agrupación de calidades + preferencia HLS + memoria de sesión (E4)
6. US5 → subtítulos en el player (E5)
7. Polish → suite verde + README real + validación E1–E6 completa

Cada historia añade valor sin romper las anteriores: el parse dual del payload, la degradación del fallback y las capas de headers garantizan que lo vigente sigue funcionando mientras se amplía.

---

## Notes

- [P] = archivos distintos, sin dependencias de tareas incompletas
- Labels [US1]–[US5] mapean a las user stories de `spec.md` (P1, P2, P2, P2, P3)
- Tests (Phase 2) NO llevan label de historia: cubren lógica que sirve a varias (p. ej. `VideoFindingsRegistryTest` cubre dedup de US1 y agrupación de US4)
- Restricciones citadas verbatim desde data-model.md/contracts para no quedar a discreción de implementación (límites de tamaño, rangos, precedencias, reglas de agrupación)
- Verificar que los tests fallan por las APIs nuevas antes de implementar (T009)
- Commits tras cada tarea o grupo lógico; parar en cada checkpoint para validar la historia de forma independiente
- Cero dependencias runtime nuevas (stack fijado: org.json del framework, Robolectric ya declarado); UI/WebView sin tests obligatorios — verificación manual E0–E6
