# Contract: JavaScript Bridge `DreamWebSniffer`

**Feature**: `001-security-hardening` | **Fecha**: 2026-11-22

Contrato del puente expuesto a JavaScript arbitrario de páginas cargadas en el
WebView. Es una frontera no confiable (Principio II): todo input se trata como
hostil.

## Interfaz expuesta

```javascript
// Registrado como: addJavascriptInterface(SnifferBridge, "DreamWebSniffer")
window.DreamWebSniffer.onVideoFound(url: string): void
```

## Contrato de entrada (FR-007)

| Input | Resultado |
|-------|-----------|
| `https://cdn.example.com/master.m3u8`, `.mpd`, `.mp4`, `.mkv`, `.webm`, `playlist.m3u8`, `/manifest...` | ✅ Aceptado → `onVideoFound` de BrowserActivity (mismo tratamiento que detección de red) |
| URL http(s) sin patrón de video reconocible | ❌ Descartado silenciosamente (log debug) |
| `javascript:...`, `data:...`, `file:...`, `blob:...`, `intent:...`, `vbscript:...` | ❌ Descartado silenciosamente |
| Texto plano, cadena vacía, URL malformada | ❌ Descartado silenciosamente |
| URL a dominio distinto del de la página | ✅ Permitido si cumple el resto (video en CDN externo — edge case del spec) |
| Segmentos (`.ts`, `/segment`, `range=`) y ads/trackers (`adsystem`, `doubleclick`, `analytics`, `googlesyndication`, `/ads/`, `pixel`) | ❌ Descartado (misma heurística del sniffer de red) |

La validación es **exactamente** `VideoUrlValidator.isVideoUrl(url)` — la misma
función que usa `CustomWebClient.shouldInterceptRequest` para la detección por
red. Un input aceptado garantiza pasar el filtro de la vía legítima.

## Contrato de ciclo de vida (FR-006, Principio V)

- Registro: `BrowserActivity.setupWebView` → `addJavascriptInterface` con la
  instancia guardada en campo de la Activity.
- Liberación del callback: `BrowserActivity.onDestroy` invoca
  `snifferBridge.release()` ANTES de `webViewPool.releaseWebView()` — anula el
  callback para que cualquier llamada JS ya encolada sea no-op incluso antes de
  remover la interfaz (así `release()` no queda como código muerto; decisión
  tomada frente a la alternativa de eliminarlo, porque `removeJavascriptInterface`
  por sí solo no desactiva invocaciones ya en vuelo).
- Desregistro: `WebViewPool.releaseWebView` →
  `removeJavascriptInterface("DreamWebSniffer")` + `webViewClient = WebViewClient()`
  + `webChromeClient = null` + cancelación del scope del `CustomWebClient` saliente.
- El bridge NO retiene referencias a `Activity`/`Context` de corta vida: solo
  guarda un callback nullable que queda inalcanzable al remover la interfaz.
- Tras `releaseWebView`, llamadas JS a `DreamWebSniffer` son no-op (la interfaz
  ya no existe en el WebView reciclado).

## Efectos de una llamada aceptada

Equivalente a detección por red: actualiza `lastDetectedVideoUrl` y muestra el
botón de reproductor externo. No lanza el player por sí solo ni otorga
capacidades adicionales — el botón sigue requiriendo acción del usuario.
