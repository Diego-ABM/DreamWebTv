# Prompts de hallazgos — DreamWeb

Cada bloque es un input independiente para `/speckit-specify`. Cópialo tal cual
(incluye el contexto; cada spec debe ser autocontenido). Orden sugerido de
ejecución: 1 → 2 → 3.

Contexto común ya asumido por todos: proyecto DreamWeb, navegador para Android TV
(Kotlin, módulo único `app`, WebView + androidx.webkit, Media3 ExoPlayer, OkHttp,
SQLite). La constitución v1.0.0 en `.specify/memory/constitution.md` declara estos
hallazgos como deuda y sus correcciones deben cumplirla.

---

## PROMPT 1 — Security hardening (TLS + JS bridge)

```
Corregir las dos violaciones de seguridad del análisis de DreamWeb (violaciones del
Principio II de la constitución):

1. TLS trust-all en `app/src/main/java/com/example/dreamweb/ui/NativeVideoActivity.kt`
   (~líneas 56-67): un X509TrustManager con checkServerTrusted/checkClientTrusted
   vacíos + hostnameVerifier permisivo desactiva la verificación TLS del OkHttp que
   alimenta ExoPlayer → MITM con robo de cookies/headers reenviados.
   Comportamiento esperado: TLS validado por defecto con OkHttpClient estándar;
   ante certificado inválido en un dominio no exceptuado, mostrar diálogo de
   advertencia nombrando el dominio; si el usuario acepta, exceptuar SOLO ese
   dominio de forma persistente y revocable; si rechaza, cancelar reproducción sin
   persistir. PROHIBIDO trust-all global ni hostname verifier permisivo global.

2. JavascriptInterface que retiene la Activity y no valida entrada: en
   `ui/BrowserActivity.kt` (~líneas 79-86) el objeto anónimo registrado como
   "DreamWebSniffer" captura this@BrowserActivity; cuando `WebViewPool.releaseWebView()`
   recicla el WebView, la Activity destruida queda retenida (leak) y cualquier JS de
   la página puede invocar onVideoFound con URLs arbitrarias.
   Comportamiento esperado: `releaseWebView()` remueve el JavascriptInterface,
   resetea webViewClient/webChromeClient y no retiene la Activity (WeakReference o
   desregistro); onVideoFound solo acepta URLs http/https con patrón de video
   reconocible y descarta el resto.

Edge cases: certificado autofirmado en LAN/IPTV (exceptuar una vez y no repreguntar),
diálogo disparado mientras la activity termina, distintos motivos de invalidez de
certificado, 10 ciclos abrir/cerrar BrowserActivity sin retención.
```

## PROMPT 2 — AdBlock real (parser EasyList + listas reales)

```
Convertir el AdBlock decorativo de DreamWeb en un motor funcional (Principio III,
features verificables). Estado actual: `adblock/AdBlockManager.kt` carga
`assets/easylist.txt` (3 dominios dummy) y `easyprivacy.txt` (vacío) en el main
thread dentro de Application.onCreate; el matching compara el host exacto contra la
línea completa sin entender sintaxis EasyList; `adblock/BloomFilter.kt` usa 64K
bits insuficientes para listas reales.

Comportamiento esperado:
- Parser real de sintaxis EasyList: reglas `||dominio^` → dominio bloqueado con
  matching por sufijo de subdominio; excepciones `@@`; ignorar comentarios `!`,
  cabeceras `[Adblock...]`, cosméticas `##`/`#@#` y reglas con opciones `$` no
  soportadas. Parseo tolerante: línea inválida se ignora, nunca crash.
- Listas reales empaquetadas en assets (verificar licencia GPLv3/CC BY-SA de
  EasyList/EasyPrivacy antes de empaquetar completas).
- Carga/parseo en Dispatchers.IO; shouldBlock thread-safe con comportamiento
  definido mientras carga (degradado "no bloquear aún").
- Estructura de matching para ≥60K reglas: falsos positivos <1%, decisión <1ms;
  si BloomFilter no cumple, sustituir por HashSet/Trie justificado.
- Whitelist por dominio persistida para sitios rotos (anti-adblock, falsos
  positivos), consultada antes del filtro.
- Contador de peticiones bloqueadas visible en debug para medir efectividad real.
- Lógica de decisión extraída a unidad testeable sin WebView (función pura sobre
  host/url), con tests unitarios per Principio IV.

Edge cases: reglas regex `/ads\d+/`, path rules `||dom.com/banner/`, wildcards `*`
(definir soporte v1 vs ignorado explícito), lista corrupta/truncada, bloom saturado.
```

## PROMPT 3 — Deuda técnica (bugs, higiene, lifecycle, tests bootstrap)

```
Saldar la deuda restante del análisis de DreamWeb no cubierta por los specs de
security-hardening ni adblock-real (Principios I, IV y V de la constitución):

Bugs de UX:
- `engine/CustomWebClient.kt`: historial se guarda en onPageStarted con view.title
  null/stale → mover registro a onPageFinished/onReceivedTitle y deduplicar
  entradas consecutivas de la misma URL.
- `ui/BrowserActivity.kt`: externalPlayerButton se oculta al entrar en fullscreen y
  nunca se restaura al salir → restaurar si lastDetectedVideoUrl sigue presente.
- `managers/VideoPlayerManager.kt`: isPlaying=true antes del early-return por
  context nulo → puede quedar latcheado; asignar tras validar.
- `shouldOverrideUrlLoading`: esquemas no-http(s) (intent://, mailto:, tel:,
  market:) caen al WebView y fallan → Intent.parseUri + handlers externos + toast
  sin handler.

Higiene y background:
- Declarar kotlinx-coroutines-android explícitamente en libs.versions.toml;
  eliminar glide y androidx.leanback (sin uso en código; mantener uses-feature
  leanback del manifest).
- `metrics/PerformanceMonitor.kt` loguea jank por frame y memoria cada 5s para
  siempre → gatear tras flag debug + método stop().
- Sniffer JS de CustomWebClient corre setInterval(checkVideos, 10000) perpetuo →
  detener tras detectar video o usar solo MutationObserver.
- BrowserActivity nunca delega onPause/onResume al WebView → forwarding completo
  coordinado con VideoPlayerManager (pauseTimers es global).

Datos:
- Un único DbHelper compartido desde AppContainer (hoy dos conexiones a la misma
  DB); métodos delete en HistoryManager/BookmarkManager con UI de borrado;
  límite/rotación del historial documentado.

Manifest/build:
- Declarar android:usesCleartextTraffic explícitamente con justificación
  (streaming legado http) — decisión pendiente del dueño.
- Release con optimization activada + keep rules para JavascriptInterface y APIs
  reflectivas de Media3; medir APK contra presupuesto <12MB.
- Limpiar strings.xml del template Leanback sin uso; virtualCursor con asset
  propio; cursor D-pad con aceleración por pulsación mantenida.

Tests (Principio IV):
- Crear app/src/test/ con junit + kotlinx-coroutines-test (+ robolectric si hace
  falta Context); cobertura mínima: decisión del sniffer extraída a función pura
  (m3u8 detectado, .ts excluido, ads excluidos, prioridad m3u8>mp4), parser del
  payload url|REFERER|UA|COOKIES, matcher de filtros y managers SQLite.
- Verificación: ./gradlew assembleDebug compila y ./gradlew test pasa.
```
