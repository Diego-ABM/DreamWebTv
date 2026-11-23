# 📑 DreamWeb: Plan de Ingeniería Definitivo (v2.0)

DreamWeb es un navegador web ultra-ligero optimizado para **Android TV**, diseñado para dispositivos con recursos limitados (ej. 2GB RAM) ofreciendo una experiencia premium superior a alternativas comerciales.

---

## ⚠️ Estado Actual del Proyecto
- **Reproductor Nativo / Extracción de Video:** Funcionalidad experimental. La detección por red y el bridge JS comparten el mismo validador (`VideoUrlValidator`); el lanzamiento del player nativo funciona con URLs detectadas.
- **Seguridad (Completado):** El canal de red del reproductor valida TLS de verdad (sin trust-all). Ante un certificado inválido se muestra un diálogo que nombra el dominio; **Aceptar** guarda una excepción solo para ese dominio (persistente, revocable desde Home → "Seguridad") y reintenta el stream automáticamente. **Rechazar** cancela sin persistir.
- **Bridge JavaScript (Completado):** `DreamWebSniffer` es una clase nombrada (`SnifferBridge`) que valida toda entrada con `VideoUrlValidator`, no retiene la Activity y se desregistra al reciclar el WebView.
- **Tests (Completado):** Infraestructura JUnit4 en `app/src/test` ejecutable con `./gradlew test` (validador de URLs, parsing del payload del player, normalización de dominio TLS, comportamiento del bridge).

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
 │    ├── 📄 CustomWebClient.kt   <-- Interceptor de red (AdBlock + Video Sniffer)
 │    ├── 📄 CustomWebChromeClient.kt <-- Gestión de pantalla completa y video
 │    ├── 📄 VideoUrlValidator.kt <-- Heurística compartida de detección de video (http/https + patrones)
 │    └── 📄 SnifferBridge.kt     <-- Bridge JS "DreamWebSniffer" con entrada validada, sin retener Activity
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
 │    ├── 📄 VideoLaunchPayload.kt <-- Parse del payload "url|REFERER|UA|COOKIES" del player
 │    └── 📄 DpadHandler.kt       <-- Manejo de cursor virtual y scroll para remoto
 ├── 📂 metrics
 │    └── 📄 PerformanceMonitor.kt <-- Telemetría de FPS y estabilidad en tiempo real
 └── 📂 ui
      ├── 📄 HomeActivity.kt      <-- UI 100% nativa de arranque rápido (entrada "Seguridad")
      ├── 📄 BrowserActivity.kt   <-- Contenedor dinámico del WebView Pool
      ├── 📄 NativeVideoActivity.kt <-- Reproductor ExoPlayer con TLS verificado + diálogo de excepción
      └── 📄 SecuritySettingsActivity.kt <-- "Seguridad y privacidad": excepciones TLS + whitelist AdBlock
```

---

## 3. 🧪 Componentes Clave

### A. AdBlock Real (EasyList + EasyPrivacy)
El motor parsea las listas **completas** empaquetadas en `assets/filters/` (EasyList 77.781 líneas + EasyPrivacy 57.597 líneas → **87.055 dominios bloqueados + 4 excepciones**) en un hilo worker one-shot al arrancar; `shouldBlock` hace suffix walk sobre `HashSet` en **p50 ≈ 170ns / p99 ≈ 340ns** (medido en JVM host). Si el host de un recurso coincide con una regla `||dominio^`, la petición se corta devolviendo un `WebResourceResponse` vacío. Las excepciones `@@` tienen precedencia, la whitelist por sitio (`adBlockToggleButton` en el navegador + sección en "Seguridad y privacidad") se consulta antes del matcher y un contador `AtomicLong` es visible en builds DEBUG.

**Medición real (23-11-2026, suite `AdBlockEffectivenessTest`):** 100% de los dominios muestreados de las reglas aceptadas bloqueados (200/200) y **0 falsos positivos** sobre 102 dominios legítimos top.

### B. Extractor Nativo de Video (Video Sniffer + ExoPlayer)
Identificamos flujos multimedia (.m3u8, .mp4, .mpd) para lanzar una actividad nativa.
- **Congelamiento de WebView:** Se invocan `onPause()` y `pauseTimers()` para liberar recursos durante la reproducción.
- **Aceleración HW:** Uso de `media3-exoplayer` para decodificación eficiente en 1080p/4K.

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
