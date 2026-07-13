# 📑 DreamWeb: Plan de Ingeniería Definitivo (v2.0)

DreamWeb es un navegador web ultra-ligero optimizado para **Android TV**, diseñado para dispositivos con recursos limitados (ej. 2GB RAM) ofreciendo una experiencia premium superior a alternativas comerciales.

---

## ⚠️ Estado Actual del Proyecto
- **Reproductor Nativo / Extracción de Video:** Esta funcionalidad está en fase de desarrollo experimental. Actualmente, la extracción automática de videos y el lanzamiento del reproductor nativo **aún no funcionan correctamente**. Se está trabajando en la lógica de interceptación de flujos multimedia.

---

## 1. 🚀 El Núcleo de Rendimiento Híbrido

Para garantizar fluidez extrema en hardware de TV, implementamos dos estrategias clave de *offloading*:

1.  **AdBlock Binario:** Descarte de peticiones de red pesadas con un algoritmo probabilístico de tiempo constante ($O(1)$) basado en **Bloom Filters**.
2.  **Ciclo de Vida en Espejo (Video Playback):** Al detectar streaming, el motor Chromium se congela totalmente, delegando el renderizado al framework nativo del sistema (ExoPlayer).

---

## 2. 🛠️ Arquitectura del Proyecto

El proyecto sigue una estructura de módulo único para maximizar la velocidad de compilación, con una separación estricta de responsabilidades:

```
📦 com.example.dreamweb
 ├── 📄 BrowserApp.kt             <-- Configura el IdleHandler para precarga del WebView
 ├── 📄 AppContainer.kt           <-- Inyección de dependencias manual (Service Locator)
 ├── 📂 engine
 │    ├── 📄 WebViewPool.kt       <-- Gestión de instancia única pre-calentada
 │    ├── 📄 CustomWebClient.kt   <-- Interceptor de red (AdBlock + Video Sniffer)
 │    └── 📄 CustomWebChromeClient.kt <-- Gestión de pantalla completa y video
 ├── 📂 adblock
 │    ├── 📄 BloomFilter.kt       <-- Matching de dominios en < 1ms
 │    ├── 📄 PrivacyEngine.kt     <-- Inyección de JS cosmético y privacidad
 │    └── 📄 AdBlockManager.kt    <-- Orquestador del filtrado
 ├── 📂 player (En desarrollo)
 │    └── 📄 NativeVideoActivity.kt <-- Reproductor ExoPlayer para Android TV
 ├── 📂 managers
 │    ├── 📄 BookmarkManager.kt   <-- SQLite: Favoritos con precarga
 │    ├── 📄 HistoryManager.kt    <-- SQLite: Historial indexado
 │    └── 📄 DpadHandler.kt       <-- Manejo de cursor virtual y scroll para remoto
 ├── 📂 metrics
 │    └── 📄 PerformanceMonitor.kt <-- Telemetría de FPS y estabilidad en tiempo real
 └── 📂 ui
      ├── 📄 HomeActivity.kt      <-- UI 100% nativa de arranque rápido
      └── 📄 BrowserActivity.kt   <-- Contenedor dinámico del WebView Pool
```

---

## 3. 🧪 Componentes Clave

### A. AdBlock Pre-compilado (EasyList + EasyPrivacy)
El motor se alimenta de un Bloom Filter pre-procesado. Si una URL coincide con el filtro, la petición se corta instantáneamente devolviendo un `WebResourceResponse` vacío, ahorrando ancho de banda y CPU.

### B. Extractor Nativo de Video (Video Sniffer + ExoPlayer)
Identificamos flujos multimedia (.m3u8, .mp4, .mpd) para lanzar una actividad nativa.
- **Congelamiento de WebView:** Se invocan `onPause()` y `pauseTimers()` para liberar recursos durante la reproducción.
- **Aceleración HW:** Uso de `media3-exoplayer` para decodificación eficiente en 1080p/4K.

---

## 🗓️ Cronograma de Construcción (Roadmap)

*   **Fase 1: El Esqueleto Operativo** (Completado) - `BrowserApp`, `WebViewPool`, `AppContainer`.
*   **Fase 2: El Escudo Inteligente** (En curso) - Integración de Bloom Filters y interceptación de red.
*   **Fase 3: Motor de Streaming Premium** - Sniffer de video y `NativeVideoActivity`.
*   **Fase 4: Almacenamiento Local Veloz** - SQLite para historial y marcadores.
*   **Fase 5: Módulo de Diagnóstico** - Monitoreo de FPS y gestión de `onTrimMemory`.
*   **Fase 6: Pulido UX** - Cursor virtual `DpadHandler` y modo oscuro forzado por JS.

---

## 📊 Límites Técnicos del Sistema

| Parámetro | Objetivo de Diseño | Comportamiento del Sistema |
| --- | --- | --- |
| **Tamaño del APK** | **< 12 MB** | Incluye dependencias de Media3 y filtros binarios. |
| **RAM en Reposo (Home)** | **≤ 60 MB** | Sin inicializar el motor Chromium. |
| **RAM en Video Nativo** | **≤ 180 MB** | Gracias al congelamiento total del WebView. |
| **Efectividad Bloqueo** | **~90%** | Filtrado de dominios equivalente a Brave. |

---
Desarrollado con ❤️ para mejorar la experiencia web en la televisión.
