# Research: Deuda Técnica

**Feature**: `003-deuda-tecnica` | **Date**: 2026-11-23

Resolución de todas las decisiones técnicas del plan. No quedan marcadores
NEEDS CLARIFICATION: el spec ya fue clarificado (FR-012, cleartext global) y el
resto se decide aquí contra el código real (`app/src/main/...`).

---

## R1. Punto de registro del historial y deduplicación (FR-001)

**Decisión**: extraer el registro a un método idempotente del manager,
`addOrUpdateLatest(url, title)`:

- Si la fila más reciente (`MAX(timestamp)`) tiene la **misma URL** → `UPDATE`
  de `title` y `timestamp` sobre esa fila (no inserta duplicado).
- Si difiere → `INSERT` normal.

Se invoca desde **dos** callbacks del WebView:

- `CustomWebClient.onPageFinished` con `view.title` (título definitivo en la
  gran mayoría de casos).
- `CustomWebChromeClient.onReceivedTitle` → reenviado al client/manager para
  cubrir el caso de título que llega tras `onPageFinished` (SPA, title tardío).

La escritura actual en `onPageStarted` (`CustomWebClient.kt:36-50`,
`view.title` = título de la página **anterior** o null) se elimina — es la
causa raíz del bug.

**Rationale**: la semántica "una entrada por URL cargada, con el último título
recibido" del edge case de SPA se cumple exactamente: la deduplicación es solo
entre entradas **consecutivas** idénticas (misma URL que la última fila →
update), no global — una URL visitada de nuevo tras otra página genera nueva
entrada. El `UPDATE` también corrige entradas ya insertadas con título nulo
cuando el título llega tarde (escenario 1 de US1).

**Alternatives considered**:

- *Registrar solo en `doUpdateVisitedHistory`*: rechazado — dispara también en
  navegación back/forward de la pila y el título no siempre está disponible.
- *Registrar solo en `onReceivedTitle`*: rechazado — páginas sin `<title>` o
  con título que llega antes de `onPageFinished` quedarían con registro
  incompleto o duplicado; el método idempotente permite usar ambos puntos sin
  coordinación extra.
- *Dedup global por URL (UNIQUE constraint)*: rechazado — contradice el edge
  case del spec ("deduplicación solo entre entradas consecutivas idénticas");
  una UNIQUE violaría el historial real de navegación.

## R2. Esquemas externos (FR-004)

**Decisión**: dos capas — un clasificador **JVM puro** + el glue Android:

- `engine/ExternalUrlClassifier.kt` (nuevo, sin imports `android.*`):
  `fun classify(url: String): ExternalAction` devuelve una sealed class:
  - `WebLoad` — `http(s)`, `about:`, `data:`, `blob:` → flujo actual
    (`shouldOverrideUrlLoading` devuelve false tras el check AdBlock).
  - `External(scheme)` — `mailto:`, `tel:`, `sms:`, `smsto:`, `geo:`,
    `market:`, `intent:` y cualquier otro esquema `^[a-z][a-z0-9+.-]*:` no web
    → resolución externa.
- Glue en `CustomWebClient.shouldOverrideUrlLoading`: para `External`,
  construye el `Intent` y llama `packageManager.resolveActivity` /
  `startActivity` dentro de try/catch (`ActivityNotFoundException`,
  `SecurityException`); sin resolvedor → `Toast` de aviso y **return true**
  (nunca navega el WebView al esquema → fin del "error page" del bug).
  Mapeo de intents: `mailto/tel/sms/smsto` → `ACTION_VIEW` con `Uri.parse`;
  `market:` → `ACTION_VIEW`; genéricos → `ACTION_VIEW` con la URI original.
- `intent://` → `Intent.parseUri(url, Intent.URI_INTENT_SCHEME)`; en
  `ActivityNotFoundException` se lee el extra `browser_fallback_url` del intent
  parseado: si existe y es http(s), se carga en el propio WebView
  (`view.loadUrl(fallback)`); si no existe → Toast. Intent malformado
  (`URISyntaxException`) → Toast. Decisión tomada del default del spec:
  "intent:// usa su URL de fallback interna cuando exista".

**Rationale**: la clasificación por esquema es lógica determinista de alto
riesgo (Principio IV) → JVM pura y testeable; el glue Android es fino y no
testeable sin instrumentación (exento por la misma constitución). Resolver con
el sistema en vez de mantener una whitelist manual cubre `whatsapp:`,
`fb://`, `vnd.youtube:` y futuros esquemas gratis.

**Alternatives considered**:

- *Whitelist cerrada de esquemas conocidos + descartar el resto*: rechazada —
  dejaría `intent:`/`whatsapp:` caídos al WebView; el spec pide "y
  equivalentes".
- *Siempre Toast genérico sin intentar resolver*: rechazada — incumple "abren
  la app correspondiente" de US1.
- *Fallback a Play Store para `market:`*: rechazada para v1 — el spec solo
  pide resolver o avisar; un fallback sintetizado sería comportamiento
  inventado no especificado.

## R3. Restauración del botón de player externo (FR-002)

**Decisión**: `lastDetectedVideoUrl` pasa a representar "video vigente de la
página actual":

- Se **invalida** (`= null` + botón `GONE`) en `onPageStarted` para el
  main-frame — una navegación nueva descarta el video previo (edge case:
  "el video detectado deja de estar disponible").
- En el callback `onFullscreenChanged(false)` de `BrowserActivity`, restaurar
  `externalPlayerButton.visibility = VISIBLE` **solo si**
  `lastDetectedVideoUrl != null` (simétrico a las otras vistas restauradas).
- Si el sniffer notifica un video nuevo mientras se está en fullscreen, el
  estado se actualiza pero la visibilidad se aplica al salir.

**Rationale**: la vigencia se acota a la página actual — única señal fiable sin
sondeo extra (Principio I). Cubre los dos escenarios de aceptación (reaparece
si vigente; no reaparece si la página navegó).

**Alternatives considered**:

- *Timestamp/TTL de validez*: rechazado — arbitrario (un HLS en pausa puede
  seguir válido minutos después) y exigiría trabajo temporal en background.
- *Sondeo JS de "video sigue en DOM"*: rechazado — reintroduce polling
  (FR-006 lo prohíbe); la navegación es el único invalidador determinista.

## R4. Latch del reproductor (FR-003)

**Decisión**: reordenar `VideoPlayerManager.launchPlayer` — validar primero,
marcar después:

1. `val context = webView?.context` → null → return (sin tocar `isPlaying`).
2. `(context as? Activity)?.let { if (it.isFinishing || it.isDestroyed) return }`
   — contexto inválido → descarte limpio.
3. Congelar el WebView (`onPause()` + `pauseTimers()`), `isPlaying = true`,
   `context.startActivity(intent)` dentro de try/catch: en excepción →
   `isPlaying = false` + descongelar + Toast.

**Rationale**: el bug actual (`VideoPlayerManager.kt:11-27`) asigna
`isPlaying = true` antes de `webView?.context ?: return`, latchando el flag
para siempre. El orden validar→marcar→actuar elimina la ventana; el try/catch
cubre `ActivityNotFoundException`/`IllegalStateException` durante destrucción.
Se mantiene `object` (estado app-wide intencional: coordina con
`releasePlayer` en `onResume`).

## R5. PerformanceMonitor (FR-005)

**Decisión**: gating + apagado simétrico:

- `BrowserApp.onCreate`: `if (BuildConfig.DEBUG) performanceMonitor.start()` —
  en release el monitor **nunca arranca** (0 logs, 0 callbacks).
- `PerformanceMonitor` pasa a guardar el `FrameCallback` en campo y expone
  `stop()`: `Choreographer.removeFrameCallback(cb)` + `scope.cancel()`.
- `BrowserApp` registra `registerActivityLifecycleCallbacks` con un contador
  de activities resumidas: al quedar en 0 (app a background) → `stop()`; al
  volver a >0 en debug → `start()` si no estaba activo. Así el monitor no
  corre con la app en background ni siquiera en debug.

**Rationale**: FR-005 pide "inactivo salvo flag de depuración" y "apagado que
libere sus callbacks". El contador foreground cierra ambos con ~30 líneas en la
`Application` — sin dependencias nuevas (`ProcessLifecycleOwner` requeriría
androidx.lifecycle:process, peso injustificado).

**Alternatives considered**:

- *Solo `if (DEBUG) start()` sin stop real*: rechazado — el choreographer
  callback y la corrutina de 5s seguirían corriendo en debug con la app en
  background; FR-005 exige liberación.
- *`lifecycle:process` (ProcessLifecycleOwner)*: rechazado — dependencia nueva
  contra Principio I para ahorrar ~20 líneas.

## R6. Fin del intervalo JS del sniffer (FR-006)

**Decisión**: modificar el script inyectado en
`CustomWebClient.injectVideoSniffer`:

- Guardar el id del `setInterval` y **clearInterval en el primer `notify()`
  aceptado** (video detectado → el sondeo cumplió su función).
- Backstop: `setTimeout(function(){ clearInterval(pollId) }, 120000)` — aun sin
  detección, ningún intervalo supera 2 min por página (condición de apagado
  explícita que exige FR-006 / Principio I).
- El `MutationObserver` **permanece** sin intervalo: la detección continua pasa
  a ser por eventos/mutaciones (opción permitida por el spec); el observer
  muere con el documento al navegar — sin cleanup manual.

**Rationale**: cumple literalmente "dejar de sondear tras detectar video" sin
perder detección de players cargados dinámicamente post-detección (el observer
los captura). El backstop evita el intervalo perpetuo en páginas donde nunca
se detecta nada.

**Alternatives considered**:

- *Eliminar el setInterval por completo*: considerado — cubre FR-006 pero
  empeora detección en páginas que construyen el player tras interacción o
  timers largos sin mutación DOM observable inmediata; el observer solo ve
  mutaciones, no variables JS (jwplayer) que aparecen después.
- *Intervalo con contador de iteraciones*: equivalente al timeout; se prefiere
  `setTimeout` por legibilidad.

## R7. Lifecycle WebView (FR-007)

**Decisión**: en `BrowserActivity` —

- `onPause()` → `webView?.onPause()` (pausa de la vista, **sin**
  `pauseTimers()`).
- `onResume()` → `webView?.onResume()` **y luego**
  `VideoPlayerManager.releasePlayer(webView)` (que hace `resumeTimers()`);
  orden seguro e idempotente si la activity nunca se pausó para el player.
- `onDestroy()` → flujo existente `webViewPool.releaseWebView` (ya correcto).

`pauseTimers()`/`resumeTimers()` quedan **exclusivamente** en
`launchPlayer`/`releasePlayer` (patrón de offloading al player nativo —
exactamente lo que exige la constitución §V).

**Rationale**: `onPause()`/`onResume()` de WebView pausan JS/rendering de la
vista (barato, simétrico); `pauseTimers()` es global al proceso y solo es
seguro dentro del offloading — mezclarlos en `onPause` de activity congelaría
timers también cuando el usuario solo pone la app en background, que es lo que
el spec acota ("solo se congelan durante el offloading").

## R8. Conexión única a SQLite (FR-009)

**Decisión**: `AppContainer` crea **un** `DbHelper` y lo inyecta:

```kotlin
val dbHelper = DbHelper(context)
val historyManager = HistoryManager(dbHelper)
val bookmarkManager = BookmarkManager(dbHelper)
```

Managers cambian su firma a `(dbHelper: DbHelper)`. `SQLiteOpenHelper` cachea
internamente la `SQLiteDatabase` → una sola conexión por helper; un solo helper
→ una sola conexión para ambos managers.

**Migración**: ninguna — el esquema no cambia (`DATABASE_VERSION` sigue en 1).
La "doble conexión" actual son dos `DbHelper` sobre el mismo archivo; unificar
no toca datos existentes. El edge case "esquema antiguo" queda cubierto porque
no hay migración que hacer (la dedup/rotación es lógica de escritura, no DDL).

**Rationale**: inyección por constructor en lugar de Room/singleton manual —
la constitución prohíbe Room y la inyección permite pasar un `DbHelper` sobre
`RuntimeEnvironment` en tests Robolectric (R12).

## R9. Límite de historial y borrado (FR-010, FR-011)

**Decisión**:

- **Límite fijado: 500 entradas, rotación FIFO**. En `addOrUpdateLatest`, tras
  el insert/update: `DELETE FROM history WHERE id NOT IN (SELECT id FROM
  history ORDER BY timestamp DESC, id DESC LIMIT 500)`. Documentado en
  `data-model.md` y `quickstart.md`.
- **Borrado individual**: los listados pasan de `Pair<url,title>` a entidades
  con `id` (`HistoryEntry`, `Bookmark`) y se añaden `deleteById(id)` a ambos
  managers. Se borra por `id`, no por URL — la dedup solo garantiza no-
  consecutividad; una URL puede aparecer en varias filas no consecutivas.
- **UI (TV-friendly)**: pulsación larga (DPAD_CENTER mantenido →
  `OnLongClickListener`) sobre el item → `deleteById` + refresh de la lista +
  `Toast` de confirmación. Sin botones extra (coherente con el patrón de foco
  D-pad de HomeActivity). Alcance v1: individual, sin "borrar todo" (Assumption
  del spec).

**Rationale**: 500 entradas ≈ <150KB de tabla — despreciable frente al
presupuesto; el FIFO por timestamp es lo que el usuario percibe como "las más
antiguas se descartan". Long-press es el gesto estándar de borrado en UIs leanback
sin añadir controles.

## R10. Optimización de release (FR-013)

**Decisión** (`app/build.gradle.kts`):

```kotlin
release {
    optimization { enable = true }
    proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"),
                  "proguard-rules.pro")
}
```

- `optimization.enable` (DSL vigente desde AGP 8.13; el proyecto usa AGP
  9.2.1) activa **shrinking de código R8 + shrinking de recursos** en un solo
  flag. En AGP 9.2.1 los keep rules siguen viniendo de `proguardFiles`
  (el source-set `keepRules/*.keep` es de AGP 9.3+ — no usarlo aquí).
- `app/proguard-rules.pro` (nuevo):
  ```proguard
  # Interfaces expuestas a JavaScript (frontera SnifferBridge)
  -keepclassmembers class * {
      @android.webkit.JavascriptInterface <methods>;
  }
  -keep class com.example.dreamweb.engine.SnifferBridge { *; }

  # Factories de Media3 instanciadas por reflexión desde
  # DefaultMediaSourceFactory (HLS/DASH se degradarían sin esto)
  -keep class androidx.media3.exoplayer.hls.HlsMediaSource$Factory { *; }
  -keep class androidx.media3.exoplayer.dash.DashMediaSource$Factory { *; }
  ```
  Las consumer-rules de Media3/AppCompat/OkHttp cubren el resto.

**Rationale**: `DefaultMediaSourceFactory` resuelve `HlsMediaSource$Factory` y
`DashMediaSource$Factory` por reflexión (`Class.forName` + constructor) — con
R8 se renombrarían y HLS/DASH dejarían de funcionar **solo en release**
(exactamente el edge case del spec). Las reglas `-keep` puntuales (~2 clases)
protegen eso sin anular el shrinking. El `JavascriptInterface` genérico cubre
cualquier bridge futuro. Verificación manual del APK release: obligatoria en
quickstart (SC-007).

**Alternatives considered**:

- *`-keep class androidx.media3.** { *; }`*: rechazado — congela ~MBs de clases
  Media3 fuera del shrinking sin necesidad; solo las factories reflectivas
  están en riesgo.
- *No añadir reglas y confiar en consumer-rules*: rechazado — el spec exige
  reglas explícitas de retención (FR-013) y las consumer-rules de media3 no
  garantizan estos entry-points reflectivos en todas las versiones.

## R11. Auditoría de dependencias (FR-008)

**Decisión**: eliminar del catálogo y de `app/build.gradle.kts`:

- `glide` — 0 usos en código (`grep` sobre `app/src`: ningún import/uso).
- `androidx-leanback` — 0 usos; el manifiesto conserva
  `uses-feature android.software.leanback` (feature del SO, no de la librería —
  Assumption explícito del spec).

Todas las demás tienen uso verificado: `core-ktx`, `appcompat`, `webkit`,
`recyclerview` (adapters Home), `lifecycle-runtime` (`lifecycleScope`),
`media3-exoplayer/hls/dash/ui/datasource-okhttp`, `okhttp`
(`PlayerHttpClientFactory`), `kotlinx-coroutines-android`, `junit`.

**Nueva dependencia (solo test)**: `org.robolectric:robolectric` como
`testImplementation` — justificación en R12; pesa 0 bytes en el APK
(no es `implementation`). Constitución: dependencia nueva justificada contra
presupuesto → cumple (impacto = 0 en release).

## R12. Tests de managers SQLite sin dispositivo (FR-016)

**Decisión**: **Robolectric** (`testImplementation`), última versión estable
compatible con SDK ≤34/35 (`@Config(sdk = [34])` en las clases de test para
máxima estabilidad). Habilitar `testOptions { unitTests.isIncludeAndroidResources = true }`.

- Los tests de `HistoryManager`/`BookmarkManager` instancian `DbHelper` con
  `RuntimeEnvironment.getApplication()` (o contexto de
  `ApplicationProvider`) → SQLite real nativo en JVM (Robolectric empaqueta
  android-all).
- Cobertura: `addOrUpdateLatest` (insert, dedup consecutivo con update de
  título, no-dedup no consecutivo), rotación a 500 (insertar 505 → 500 y FIFO
  correcto), `deleteById`, `getRecentHistory`/`getAllBookmarks`.

**Rationale**: `SQLiteOpenHelper`/`SQLiteDatabase` son clases del framework —
en JVM puro devuelven stubs. Robolectric es la vía estándar para cumplir
"`./gradlew test` sin dispositivo" (Principio IV) sin abstraer el API de
SQLite detrás de interfaces inventadas.

**Alternatives considered**:

- *`unitTests.isReturnDefaultValues = true` + mocks*: rechazado — devuelve
  defaults, no ejecuta SQLite; los tests serían vacíos de valor.
- *Abstraer `Database` propio + sqlite-jdbc en test*: rechazado — reimplementar
  `ContentValues`/`Cursor`/queries para tests es deuda nueva y diverge del
  código real.
- *Tests instrumentados (`androidTest`)*: rechazado — exige dispositivo,
  prohibido por el spec.

## R13. Recursos y strings (FR-014)

**Decisión**:

- `virtualCursor` (activity_browser.xml:83-92) usa hoy
  `@android:drawable/ic_delete` + `@android:drawable/presence_online` → crear
  `res/drawable/cursor_arrow.xml` (vector propio: puntero tipo flecha, trazo
  claro con contorno oscuro, visible sobre fondos claros y oscuros).
- Eliminar strings del template sin uso: `browse_title`, `related_movies`,
  `grid_view`, `error_fragment`, `personal_settings`, `watch_trailer_1/2`,
  `rent_1/2`, `buy_1/2`, `movie`, `error_fragment_message`, `dismiss_error`.
  Se conservan `app_name` y `external_player_desc` (en uso).
- Eliminar `res/drawable/movie.png` y `res/drawable/default_background.xml`
  (0 referencias) y el `color default_background` huérfano. **Conservar**
  `app_icon_your_company.png`: lo referencia el manifiesto (banner/icon/logo)
  — es asset usado, aunque venga del template.

**Rationale**: borrado verificado por `grep` de referencias; `app_icon` se
mantiene porque borrarlo rompería el manifest — reemplazarlo por un icono
propio es mejora de producto fuera de scope de deuda técnica.

## R14. Aceleración del cursor D-pad (FR-015)

**Decisión**: `DpadHandler` ya recibe `KeyEvent`; los ACTION_DOWN auto-repeat
traen `event.repeatCount` creciente. Step dinámico:

```kotlin
// lógica pura nueva, testeable:
object DpadAcceleration {
    const val BASE_STEP = 25f
    const val MAX_STEP = 150f
    fun stepFor(repeatCount: Int): Float =
        (BASE_STEP * (1f + repeatCount * 0.10f)).coerceAtMost(MAX_STEP)
}
```

`handleKeyEvent` usa `stepFor(event.repeatCount)` en los 4 ejes; `repeatCount`
vuelve a 0 al soltar y al cambiar de dirección → la aceleración se resetea
sola. Sin timers ni hilos extra (Principio I).

**Rationale**: curva lineal con tope — progresiva (25→150px en ~12 repeticiones)
sin overshooting en pantallas 1080p; determinista → test JVM directo (FR-017).

**Alternatives considered**:

- *Handler con delay decreciente*: rechazado — añade un hilo/callback con
  ownership que gestionar (Principio V) para lo que `repeatCount` ya da gratis.
- *Curva exponencial*: rechazado — overshooting rápido en TV; la lineal con cap
  es más predecible con D-pad real.

## R15. Dependencia entre correcciones (orden de trabajo sugerido)

Cadena de implementación que minimiza regresiones (alimentará `tasks.md`):

1. **Datos primero** (R8+R9: DbHelper inyectado, entidades con id, dedup,
   rotación, deleteById) → habilita tests Robolectric de managers.
2. **Bug fixes UX** (R1 historial, R2 esquemas, R3 botón, R4 latch) — R1
   depende de R8 (firma nueva del manager).
3. **Background/lifecycle** (R5 monitor, R6 sniffer JS, R7 lifecycle).
4. **Build/recursos** (R10 release, R11 deps, R13 recursos, R14 aceleración).
5. **Tests** (R12 managers + DpadAcceleration + ExternalUrlClassifier +
   casos nuevos de VideoUrlValidator si cambiara).

Nada bloquea el orden salvo R1→R8; el resto son archivos disjuntos.
