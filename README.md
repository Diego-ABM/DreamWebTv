# 📑 DreamWeb: Plan de Ingeniería Definitivo (v2.0)

DreamWeb es un navegador web ultra-ligero optimizado para **Android TV**, diseñado para dispositivos con recursos limitados (ej. 2GB RAM) ofreciendo una experiencia premium superior a alternativas comerciales.

---

## ⚠️ Estado Actual del Proyecto
- **Captura y Reproducción de Video (feature 004, rama `004-video-capture-playback`):** Implementada. Un detector JS inyectado en document-start (`video_detector.js`, con fallback en `onPageFinished`) reporta hallazgos estructurados (`VideoReport`) por el bridge `DreamWebSniffer`; `VideoReportParser` valida y sanitiza en la frontera; `VideoFindingsRegistry` deduplica por URL, fusiona mutaciones por `mediaKey` (cap 64) y agrupa rendiciones. Un indicador no intrusivo (z-order bajo el cursor virtual) aparece con el nº de hallazgos; el panel lateral `findingsPanel` se abre con click/CENTER/MENU y lanza el player nativo solo a petición del usuario (sin auto-play). El payload del player es **JSON v2** (`url/referer/userAgent/cookies/headers/subtitles/startPositionMs/mimeType`, con parser dual que conserva compatibilidad legado) y antes del lanzamiento se recaptura `positionSec` con un `evaluateJavascript` acotado (~300 ms). Calidad con memoria de sesión (RAM, sin persistencia) y sondeo acotado `VideoInfoProber` (1 petición por hallazgo, solo si faltan duración Y resolución).
- **Seguridad (Completado):** El canal de red del reproductor valida TLS de verdad (sin trust-all): `PlayerHttpClientFactory` monta `DomainTrustManager` + `DomainHostnameVerifier` y aplica headers en capas — capturados sanitizados (jamás nombres gestionados ni `Sec-Fetch-*`), luego gestionados autoritativos (`User-Agent`, `Referer`, `Origin` derivado, `Cookie`, `Sec-Fetch-Mode/Site`, `Accept`). Ante un certificado inválido se muestra un diálogo que nombra el dominio; **Aceptar** guarda una excepción solo para ese dominio (persistente, revocable desde Home → "Seguridad") y reintenta el stream automáticamente. **Rechazar** cancela sin persistir.
- **Suspensión/Restauración (Completado):** `VideoPlayerManager` valida el payload **antes** de congelar (`onPause`+`pauseTimers`), revierte si `startActivity` falla y reanuda al volver (`releasePlayer`, idempotente). `BrowserActivity` persiste `webView.saveState` + `lastPageUrl` y restaura desde `saveState`, extra `URL` o última entrada del historial — nunca pantalla en blanco.
- **Bridge JavaScript (Completado):** `DreamWebSniffer` acepta un único JSON (`onVideoFound(String)`), validado por `VideoReportParser` (URL http(s), cabeceras, límites de esquema); no retiene la Activity y se desregistra al reciclar el WebView (`ScriptHandler.remove()` incluido).
- **Tests (Completado):** 134 tests JUnit4/Robolectric en `app/src/test` ejecutables con `./gradlew test` — validador de URLs, parser de reportes, registry de hallazgos (dedup/mutación/agrupación/memoria de sesión), filtro de subtítulos, payload JSON v2 + dual legado, merge de headers del player y comportamiento del bridge.

---

## 1. 🚀 El Núcleo de Rendimiento Híbrido

Para garantizar fluidez extrema en hardware de TV, implementamos dos estrategias clave de *offloading*:

1.  **AdBlock Real:** Descarte de peticiones de red a dominios de ads/trackers mediante matching **exacto por sufijo de dominio** (`HashSet` sobre las reglas `||dominio^`/`@@||dominio^` de EasyList + EasyPrivacy completas — 87.059 reglas, 0% falsos positivos por construcción).
2.  **Ciclo de Vida en Espejo (Video Playback):** Al detectar streaming, el motor Chromium se congela totalmente, delegando el renderizado al framework nativo del sistema (ExoPlayer).

---

## 2. 🛠️ Arquitectura del Proyecto

El proyecto sigue una estructura de módulo único para maximizar la velocidad de compilación, con una separación estricta de responsabilidades:

```
📦 com.example.dreamweb
 ├── 📄 BrowserApp.kt             <-- Configura el IdleHandler para precarga del WebView
 ├── 📄 AppContainer.kt           <-- Inyección de dependencias manual (Service Locator)
 ├── 📂 engine
 │    ├── 📄 WebViewPool.kt       <-- Gestión de instancia única pre-calentada (limpieza simétrica al reciclar)
 │    ├── 📄 CustomWebClient.kt   <-- Interceptor de red (AdBlock + hallazgos NETWORK) + inyección fallback
 │    ├── 📄 CustomWebChromeClient.kt <-- Gestión de pantalla completa y video
 │    ├── 📄 VideoUrlValidator.kt <-- Heurística compartida de detección de video (http/https + patrones)
 │    ├── 📄 VideoModels.kt       <-- VideoReport/VideoFinding/Rendition/SubtitleTrack/StreamFormat
 │    ├── 📄 VideoReportParser.kt <-- Frontera de sanidad: esquema, límites, headers/subtítulos
 │    ├── 📄 VideoFindingsRegistry.kt <-- Dedup URL, mutación por mediaKey, agrupación, cap 64
 │    ├── 📄 VideoSnifferInjector.kt <-- addDocumentStartJavaScript (WebViewCompat) con handle simétrico
 │    ├── 📄 SubtitleTrackFilter.kt <-- Solo .vtt/.srt/.ttml; el resto se omite
 │    └── 📄 SnifferBridge.kt     <-- Bridge JS "DreamWebSniffer" (JSON → VideoReport), sin retener Activity
 ├── 📂 security
 │    ├── 📄 TlsExceptionStore.kt  <-- Excepciones TLS por dominio (SharedPreferences, revocables)
 │    ├── 📄 DomainTrustManager.kt <-- Delega en plataforma; bypass solo por host exceptuado
 │    └── 📄 PlayerHttpClientFactory.kt <-- OkHttpClient del player con TLS real + headers/cookies
 ├── 📂 adblock
 │    ├── 📄 FilterParser.kt      <-- Gramática EasyList v1: ||d^, @@||d^ (Kotlin puro)
 │    ├── 📄 DomainMatcher.kt     <-- HashSet + suffix walk por etiquetas (< 1µs real)
 │    ├── 📄 AdBlockWhitelistStore.kt <-- Whitelist por sitio (SharedPreferences + set volátil)
 │    ├── 📄 PrivacyEngine.kt     <-- Inyección de JS cosmético y privacidad
 │    └── 📄 AdBlockManager.kt    <-- Orquestador: carga async one-shot, swap @Volatile, contador
 ├── 📂 managers
 │    ├── 📄 BookmarkManager.kt   <-- SQLite: Favoritos con precarga
 │    ├── 📄 HistoryManager.kt    <-- SQLite: Historial indexado
 │    ├── 📄 VideoLaunchPayload.kt <-- Payload JSON v2 (dual-parse con legado "url|REFERER|…")
 │    ├── 📄 VideoInfoProber.kt   <-- Sondeo acotado (1 petición/hallazgo) + parser de manifiesto HLS
 │    ├── 📄 QualityPreferenceMemory.kt <-- Preferencia de calidad solo en RAM (sesión)
 │    ├── 📄 VideoPlayerManager.kt <-- Freeze/release del WebView con validación previa al freeze
 │    └── 📄 DpadHandler.kt       <-- Manejo de cursor virtual y scroll para remoto
 ├── 📂 metrics
 │    └── 📄 PerformanceMonitor.kt <-- Telemetría de FPS y estabilidad en tiempo real
 └── 📂 ui
      ├── 📄 HomeActivity.kt      <-- UI 100% nativa de arranque rápido (entrada "Seguridad")
      ├── 📄 BrowserActivity.kt   <-- Contenedor dinámico del WebView Pool + indicador/panel de hallazgos
      ├── 📄 VideoFindingsAdapter.kt <-- Filas del panel + sub-fila de calidades (D-pad)
      ├── 📄 NativeVideoActivity.kt <-- Reproductor ExoPlayer con TLS verificado + diálogo de excepción
      └── 📄 SecuritySettingsActivity.kt <-- "Seguridad y privacidad": excepciones TLS + whitelist AdBlock
```

---

## 3. 🧪 Componentes Clave

### A. AdBlock Real (EasyList + EasyPrivacy)
El motor parsea las listas **completas** empaquetadas en `assets/filters/` (EasyList 77.781 líneas + EasyPrivacy 57.597 líneas → **87.055 dominios bloqueados + 4 excepciones**) en un hilo worker one-shot al arrancar; `shouldBlock` hace suffix walk sobre `HashSet` en **p50 ≈ 170ns / p99 ≈ 340ns** (medido en JVM host). Si el host de un recurso coincide con una regla `||dominio^`, la petición se corta devolviendo un `WebResourceResponse` vacío. Las excepciones `@@` tienen precedencia, la whitelist por sitio (`adBlockToggleButton` en el navegador + sección en "Seguridad y privacidad") se consulta antes del matcher y un contador `AtomicLong` es visible en builds DEBUG.

**Medición real (23-11-2026, suite `AdBlockEffectivenessTest`):** 100% de los dominios muestreados de las reglas aceptadas bloqueados (200/200) y **0 falsos positivos** sobre 102 dominios legítimos top.

### B. Captura y Reproducción de Video (detección estructurada + player nativo)
Identificamos flujos multimedia (.m3u8, .mp4, .mpd) por dos vías: el detector JS (`video_detector.js`, document-start con fallback en `onPageFinished`; hooks `src`/`srcObject`/`setAttribute`, MutationObserver de `<track>` y sondeo acotado) y `shouldInterceptRequest` (hallazgos `NETWORK` con cabeceras de la WebView sanitizadas — nunca `Sec-Fetch-*`). Los `VideoReport` se consolidan en `VideoFindingsRegistry` (dedup por URL, mutación del stream vigente por `mediaKey`, agrupación por resolución/formato, preferencia HLS > DASH > progresivo y memoria de sesión `QualityPreferenceMemory`, cap 64 hallazgos).
- **Presentación manual:** un `Button` indicador (`videoIndicatorButton`, bajo el cursor virtual) muestra `▶ N`; el panel `findingsPanel` (sobre WebView y cursor) lista filas con título/formato/resolución/duración/subtítulos y sub-fila `▸ Calidad:` cuando hay >1 opción. Sin diálogos automáticos ni auto-play; el lanzamiento siempre lo confirma el usuario (CENTER sobre fila u opción, click, o `KEYCODE_MENU` para abrir/cerrar).
- **Lanzamiento:** recaptura acotada de `positionSec` (~300 ms, no bloquea) → payload JSON v2 → `VideoPlayerManager.launchPlayer` valida antes de congelar el WebView (`onPause`+`pauseTimers`) y reanuda al volver (`releasePlayer`, idempotente). Errores de stream caducado/denegado y DRM comercial muestran mensajes claros y devuelven la navegación intacta.
- **Aceleración HW:** Uso de `media3-exoplayer` para decodificación eficiente en 1080p/4K con subtítulos (`vtt/srt/ttml`) vía `SubtitleConfiguration`.

---

## 🗓️ Cronograma de Construcción (Roadmap)

*   **Fase 1: El Esqueleto Operativo** (Completado) - `BrowserApp`, `WebViewPool`, `AppContainer`.
*   **Fase 2: El Escudo Inteligente** (Completado) - Motor AdBlock real: parser EasyList, matching exacto por sufijo de dominio y whitelist por sitio.
*   **Fase 3: Motor de Streaming Premium** - Sniffer de video y `NativeVideoActivity`.
*   **Fase 4: Almacenamiento Local Veloz** - SQLite para historial y marcadores.
*   **Fase 5: Módulo de Diagnóstico** - Monitoreo de FPS y gestión de `onTrimMemory`.
*   **Fase 6: Pulido UX** - Cursor virtual `DpadHandler` y modo oscuro forzado por JS.

---

## 📊 Límites Técnicos del Sistema

| Parámetro | Objetivo de Diseño | Comportamiento del Sistema |
| --- | --- | --- |
| **Tamaño del APK** | **< 12 MB** | **Medido: 3,96 MB (3.958.273 B) release unsigned** tras `optimization.enable=true` (R8 + resource shrinking), eliminación de `glide`/`androidx-leanback` y purge de recursos del template — presupuesto cumplido (baseline previo a la feature 003: 16,5 MB). |
| **RAM en Reposo (Home)** | **≤ 60 MB** | Sin inicializar el motor Chromium. |
| **RAM en Video Nativo** | **≤ 180 MB** | Gracias al congelamiento total del WebView. |
| **Efectividad Bloqueo** | **100%** medido sobre las reglas de dominio aceptadas (muestra de 200, 0 FP sobre 102 dominios legítimos) | Filtrado por sufijo de dominio con EasyList + EasyPrivacy completas; no cubre ads first-party ni reglas cosméticas/de URL. |

---
## 📜 Atribución de listas de filtros

DreamWeb distribuye **EasyList** y **EasyPrivacy** completas (`app/src/main/assets/filters/`), obtenidas de las URLs canónicas de [easylist.to](https://easylist.to/) el 23-11-2026. Ambas listas son dual-licensed **GPLv3 / CC BY-SA 3.0**; este proyecto elige distribuirlas bajo **GPLv3** (texto completo en `assets/filters/LICENSE.GPLv3.txt`, procedencia y atribución en `assets/filters/NOTICE.txt`).

---

Desarrollado con ❤️ para mejorar la experiencia web en la televisión.
