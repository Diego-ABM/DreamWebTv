# Implementation Plan: Captura y Reproducción de Video Web

**Branch**: `004-video-capture-playback` | **Date**: 2026-11-23 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/004-video-capture-playback/spec.md`

## Summary

Llevar la detección y reproducción de video web de DreamWeb a su nivel
objetivo — detección temprana, contexto completo al player y calidad
seleccionable —, ampliando los
componentes existentes —nunca duplicándolos—:

1. **Detección temprana estructurada** (P1): sniffer JS en *document-start*
   vía `WebViewCompat.addDocumentStartJavaScript` (con fallback garantizado al
   `evaluateJavascript` en `onPageFinished` vigente cuando
   `WebViewFeature.DOCUMENT_START_SCRIPT` no exista en el WebView del
   dispositivo), reporte JSON por el bridge endurecido `DreamWebSniffer` —
   `onVideoFound(url)` → `onVideoFound(json)` validado por un nuevo parser de
   frontera— y consolidación en un registry testeable con dedup, mutación de
   fuente y límite de 64 hallazgos.
2. **Presentación bajo demanda** (P1): el botón de player existente evoluciona
   a indicador no intrusivo con contador; el panel de hallazgos
   (RecyclerView ya declarada) se abre solo por acción del usuario, navegable
   con D-pad; el lanzamiento es siempre manual.
3. **Contexto fiel** (P2): `VideoLaunchPayload` migra a JSON tipado
   (`headers:Map`, `subtitles`, `startPositionMs`, `mimeType`) con parse dual
   legado/JSON; `PlayerHttpClientFactory` aplica headers capturados con
   precedencia estricta bajo la capa gestionada, sobre el pipeline TLS por
   dominio intacto; las cookies reales (`requestHeaders` +
   `CookieManager`) acompañan manifiesto y segmentos.
4. **Sesión liberada y restaurada** (P2): congelado `onPause+pauseTimers`
   vigente; restauración exacta por supervivencia del WebView +
   `saveState/restoreState` ante recreación + última URL del historial ante
   muerte de proceso.
5. **Calidad y subtítulos** (P2/P3): agrupación de rendiciones por resolución
   con preferencia HLS y memoria solo de sesión; `<track>`/textTracks →
   `SubtitleConfiguration` con filtro de formato; sondeo acotado de 1
   petición por hallazgo (sobre el mismo cliente TLS del player) para
   metadatos y variantes de manifiesto.

Stack fijado por constitución: Kotlin/AGP 9.2.1, WebView + androidx.webkit
1.12.1, Media3 ExoPlayer 1.2.0, OkHttp 4.12.0. **Cero dependencias runtime
nuevas**; org.json (framework) para JSON; Robolectric (ya declarado) para los
tests del parser.

## Technical Context

**Language/Version**: Kotlin (AGP 9.2.1 con Kotlin integrado), Java 11
(`sourceCompatibility`/`targetCompatibility` = `VERSION_11` en
`app/build.gradle.kts`)

**Primary Dependencies**: WebView + androidx.webkit 1.12.1
(`WebViewCompat.addDocumentStartJavaScript`, feature `DOCUMENT_START_SCRIPT`),
Media3 ExoPlayer 1.2.0 (`media3-exoplayer`, `-hls`, `-dash`, `-ui`,
`-datasource-okhttp`), OkHttp 4.12.0, androidx.recyclerview 1.3.2 (por fin con
uso real: panel de hallazgos), coroutines 1.7.3 — todas ya declaradas en
`gradle/libs.versions.toml`

**Storage**: Ninguna nueva. Hallazgos en memoria acotados a la página;
preferencia de calidad en memoria de sesión (prohibido persistir — FR-018);
fallback de restauración lee `HistoryManager` (SQLite existente); excepciones
TLS en `TlsExceptionStore` (SharedPreferences existente).

**Testing**: JUnit4 JVM + Robolectric 4.14.1 (para `org.json`); ejecutable con
`./gradlew test` sin dispositivo (Principio IV). Lógica crítica nueva
testeable: `VideoReportParser`, `VideoFindingsRegistry` (dedup/mutación/
agrupación/memoria), `SubtitleTrackFilter`, `VideoLaunchPayload` JSON.

**Target Platform**: Android TV/leanback, minSdk 28, targetSdk 36,
`usesCleartextTraffic="true"` (ya declarado y justificado)

**Project Type**: Android app, módulo único `:app`, paquete
`com.example.dreamweb`

**Performance Goals**: RAM total ≤ 180 MB con player activo (SC-004);
detección de medios dinámicos ≤ 2 s (SC-001); 1 petición de sondeo máx. por
hallazgo; sondeo JS con apagado al 1er hallazgo y backstop 2 min (Principio I)

**Constraints**:
- PROHIBIDO: DI frameworks, Room, Compose, deps sin declarar, WebViews fuera
  del pool, logging continuo en release, trust-all TLS, excepciones TLS
  globales, trabajo en background sin justificación ni apagado.
- `@JavascriptInterface` = frontera no confiable: el JSON se valida con
  límites de tamaño/tipos/rangos antes de tocar el registry (Principio II).
- UI 100 % operable con D-pad; indicador no intrusivo; cero diálogos
  automáticos, cero auto-play (clarificaciones del spec).
- `NativeVideoActivity` permanece `exported="false"` (FR-014).

**Scale/Scope**: ~33 archivos Kotlin existentes, 13 de test. La feature toca
~10 archivos (`SnifferBridge`, `CustomWebClient`, `WebViewPool`,
`VideoLaunchPayload`, `VideoPlayerManager`, `PlayerHttpClientFactory`,
`NativeVideoActivity`, `BrowserActivity`, `activity_browser.xml`) y añade ~8
nuevos + 4–5 tests + 1 asset JS (~8 KB).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principio | Evaluación | Resultado |
|-----------|------------|-----------|
| I. Eficiencia de Recursos | +0 deps runtime; asset JS ~8 KB; RecyclerView declarada pasa a tener uso (resuelve deuda de "declarada sin uso"); hallazgos acotados a 64; sondeo HTTP ≤1 req/hallazgo cancelable; observer por eventos + intervalo con apagado (mismo contrato que spec 003); WebView único del pool | ✅ PASS |
| II. Seguridad y Privacidad | TLS por dominio se conserva íntegro (FR-010); el bridge sigue siendo 1 método y ahora valida JSON tipado con límites estrictos (`VideoReportParser`); headers capturados nunca pisan la capa gestionada (UA/Referer/Cookie/Sec-Fetch/host-headers); ningún dato de detección sale del dispositivo (FR-013) | ✅ PASS |
| III. Features Verificables | Detección real por document-start + red; payload real aplicado a ExoPlayer; quickstart define verificación E2E por user story y SC | ✅ PASS |
| IV. Tests en Lógica Crítica | Parser JSON/frontera, registry (dedup/mutación/grupos), payload dual, filtro de subtítulos → tests JVM/Robolectric con `./gradlew test`; UI/WebView exenta por coste | ✅ PASS |
| V. Ciclo de Vida | ScriptHandler + bridge desregistrados en `releaseWebView`; registry limpiado en main-frame nav; sondeo cancelable ligado a lifecycleScope; latch `isPlaying` con reversión; `pauseTimers`/`resumeTimers` simétricos (vigente) + `saveState/restoreState` | ✅ PASS |

**Notas de deuda gestionada (no bloquea)**: RecyclerView estaba declarada sin
uso — esta feature la emplea (panel). El resto del pipeline (AdBlock,
history, DpadHandler) no cambia.

**Resultado del gate: PASS** — sin violaciones que requieran Complexity
Tracking. Re-evaluado tras Phase 1: sin cambios (cero deps nuevas, ownership
simétrico diseñado en cada contrato).

## Project Structure

### Documentation (this feature)

```text
specs/004-video-capture-playback/
├── plan.md              # This file
├── research.md          # Phase 0 output — R1…R10 (document-start, JSON,
│                        #   registry, payload, sondeo, calidades, subs,
│                        #   headers, restauración, diseño del script)
├── data-model.md        # Phase 1 output — VideoReport/VideoFinding/Rendition/
│                        #   SubtitleTrack/VideoLaunchPayload/QualityMemory
├── quickstart.md        # Phase 1 output — E0…E6 + tabla SC
├── contracts/
│   ├── video-detection-bridge.md     # inyección, esquema JSON, validación, apagado
│   ├── playback-context-payload.md   # extra JSON v2, parse dual, merge de headers
│   ├── findings-panel-ui.md          # indicador + panel D-pad + selector de calidad
│   └── playback-session.md           # congelado/restauración/degradación
├── checklists/requirements.md        # (existente)
└── tasks.md             # Phase 2 output (/speckit-tasks - NOT created here)
```

### Source Code (repository root)

```text
app/
├── src/main/
│   ├── assets/
│   │   └── video_detector.js                  # NUEVO — detector document-start/fallback
│   ├── java/com/example/dreamweb/
│   │   ├── engine/
│   │   │   ├── VideoModels.kt                 # NUEVO — VideoReport/VideoFinding/Rendition/SubtitleTrack
│   │   │   ├── VideoReportParser.kt           # NUEVO — JSON→VideoReport validado (frontera)
│   │   │   ├── VideoFindingsRegistry.kt       # NUEVO — dedup/mutación/grupos/memoria (JVM puro)
│   │   │   ├── SubtitleTrackFilter.kt         # NUEVO — formatos soportados (JVM puro)
│   │   │   ├── VideoSnifferInjector.kt        # NUEVO — carga asset, install/uninstall
│   │   │   │                                  #   (DOCUMENT_START_SCRIPT gate + ScriptHandler)
│   │   │   ├── SnifferBridge.kt               # MOD — onVideoFound(json) → parser → callback(VideoReport)
│   │   │   ├── CustomWebClient.kt             # MOD — red→registry c/ requestHeaders;
│   │   │   │                                  #   fallback eval del asset en onPageFinished
│   │   │   ├── WebViewPool.kt                 # MOD — releaseWebView quita ScriptHandler
│   │   │   └── VideoUrlValidator.kt           # sin cambios (frontera compartida)
│   │   ├── managers/
│   │   │   ├── VideoLaunchPayload.kt          # MOD — JSON v2 + headers/subtitles/startPositionMs;
│   │   │   │                                  #   parse() dual legado/JSON
│   │   │   ├── VideoPlayerManager.kt          # MOD — recibe payload JSON; valida antes de congelar
│   │   │   ├── VideoInfoProber.kt             # NUEVO — 1 req acotada por hallazgo (HEAD / master m3u8)
│   │   │   └── QualityPreferenceMemory.kt     # NUEVO — última calidad (solo RAM de sesión)
│   │   ├── security/
│   │   │   └── PlayerHttpClientFactory.kt     # MOD — merge headers por capas (R8);
│   │   │                                      #   expone OkHttpClient para el prober
│   │   └── ui/
│   │       ├── BrowserActivity.kt             # MOD — registry, indicador+panel,
│   │       │                                  #   lanzamiento manual, saveState/restoreState
│   │       ├── NativeVideoActivity.kt         # MOD — payload v2: headers, startPosition,
│   │       │                                  #   SubtitleConfigurations, errores claros
│   │       └── VideoFindingsAdapter.kt        # NUEVO — RecyclerView adapter del panel
│   └── res/layout/
│       ├── activity_browser.xml               # MOD — indicador + findingsPanel overlay
│       └── item_video_finding.xml             # NUEVO — fila hallazgo (+ sub-fila calidades)
└── src/test/java/com/example/dreamweb/
    ├── engine/VideoReportParserTest.kt        # NUEVO (Robolectric)
    ├── engine/VideoFindingsRegistryTest.kt    # NUEVO (JVM puro)
    ├── engine/SubtitleTrackFilterTest.kt      # NUEVO (JVM puro)
    ├── engine/SnifferBridgeTest.kt            # MOD — firma JSON
    └── managers/VideoLaunchPayloadJsonTest.kt # NUEVO (Robolectric)
```

**Structure Decision**: módulo único `:app` vigente. Toda la lógica crítica
nueva vive en `engine/`/`managers/` como Kotlin puro (testeable sin
dispositivo); el glue Android mínimo queda en `ui/`/`security/`; el JS es un
asset versionado. No se crean paquetes nuevos — se amplían los existentes
según el mapa de la spec ("ampliar, no duplicar").

## Complexity Tracking

> Sin violaciones del Constitution Check que justificar — tabla vacía.
