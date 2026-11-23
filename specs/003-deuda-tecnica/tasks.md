---

description: "Task list for feature implementation"
---

# Tasks: Deuda Técnica (Bugs UX, Background, Datos, Build, Tests)

**Input**: Design documents from `/specs/003-deuda-tecnica/`

**Prerequisites**: plan.md (required), spec.md (required for user stories), research.md, data-model.md, contracts/, quickstart.md

**Tests**: Tests **sí incluidos** — el spec los exige explícitamente como User Story 5 (FR-016, FR-017). Por decisión del spec forman su propia fase al final; los archivos de test se referencian desde las historias que verifican.

**Organization**: Tasks agrupadas por user story (US1–US5 del spec.md, en orden de prioridad P1→P5) para permitir implementación y verificación independiente de cada historia.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Path Conventions

- **Mobile app**: módulo único `app/`; código en `app/src/main/java/com/example/dreamweb/`, recursos en `app/src/main/res/`, tests JVM en `app/src/test/java/com/example/dreamweb/`, build en `app/build.gradle.kts` y catálogo en `gradle/libs.versions.toml`.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Baseline verificable antes de tocar código (el spec es corrección de deuda: hay que medir el punto de partida)

- [ ] T001 Ejecutar baseline en rama `003-deuda-tecnica`: `./gradlew assembleDebug` y `./gradlew test` deben pasar antes de cualquier cambio; registrar el resultado como referencia de no-regresión
- [ ] T002 [P] Medir baseline de release: `./gradlew assembleRelease` y registrar el tamaño actual del APK en `app/build/outputs/apk/release/` (nunca se midió — necesario para la comparativa < 12MB de SC-007/E6)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Conexión única a SQLite (FR-009, research R8) — prerequisito: la nueva firma de los managers es consumida por US1 (escritura de historial) y US3 (borrado)

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

- [ ] T003 Inyectar un único `DbHelper`: en `app/src/main/java/com/example/dreamweb/AppContainer.kt` crear `val dbHelper = DbHelper(context)` y pasarlo a ambos managers (`HistoryManager(dbHelper)`, `BookmarkManager(dbHelper)`); cambiar los constructores en `app/src/main/java/com/example/dreamweb/managers/HistoryManager.kt` y `app/src/main/java/com/example/dreamweb/managers/BookmarkManager.kt` a `(dbHelper: DbHelper)` eliminando el `DbHelper(context)` interno de cada uno (FR-009, R8 — `SQLiteOpenHelper` cachea la conexión: un helper = una conexión física; sin migración, `DATABASE_VERSION` sigue en 1)

**Checkpoint**: `./gradlew assembleDebug` compila; historial y favoritos siguen funcionando con la conexión única — user story implementation can now begin

---

## Phase 3: User Story 1 - Navegación sin glitches visibles (Priority: P1) 🎯 MVP

**Goal**: El historial guarda el título definitivo con dedup consecutiva; el botón de player externo se restaura al salir de fullscreen solo con video vigente; el flag `isPlaying` nunca queda latcheado; los esquemas no-web se resuelven con apps externas o avisan — nunca página de error interna.

**Independent Test**: Navegar a una página → la entrada de historial guarda su título final (quickstart E1). Detectar un video → entrar y salir de fullscreen → el botón externo reaparece; navegar → deja de reaparecer (E3). Pulsar un enlace `mailto:` en dispositivo sin app de correo → Toast de aviso y el WebView no navega; `intent://` con `browser_fallback_url` y sin app → carga el fallback (E2).

### Implementation for User Story 1

- [ ] T004 [P] [US1] Implementar `addOrUpdateLatest(url: String, title: String?)` en `app/src/main/java/com/example/dreamweb/managers/HistoryManager.kt` sustituyendo a `addHistory`: si la fila con `MAX(timestamp)` tiene la **misma url** → `UPDATE` de `title` y `timestamp` sobre esa fila; si difiere → `INSERT`; `title` null se normaliza a `""` en lectura. Deduplicación **solo consecutiva** (misma URL en filas no consecutivas es válida — data-model.md §HistoryEntry; FR-001, R1)
- [ ] T005 [US1] Mover el registro de historial en `app/src/main/java/com/example/dreamweb/engine/CustomWebClient.kt`: eliminar la escritura en `onPageStarted` (causa raíz del bug — `view.title` ahí es el título de la página anterior o null) y registrar en `onPageFinished` llamando a `historyManager.addOrUpdateLatest(url, view.title)` dentro de `scope.launch`, filtrando `about:blank` y url null/blank (depende de T004; FR-001, R1)
- [ ] T006 [US1] Reenviar título tardío en `app/src/main/java/com/example/dreamweb/engine/CustomWebChromeClient.kt`: añadir callback `onReceivedTitle: (String?, String?) -> Unit` al constructor y override `onReceivedTitle(view, title)` que lo invoque con `(view?.url, title)`; cablearlo en `app/src/main/java/com/example/dreamweb/ui/BrowserActivity.kt` (`setupWebView`) hacia `historyManager.addOrUpdateLatest` con `lifecycleScope.launch`, filtrando url null/`about:blank` — cubre SPAs y títulos que llegan tras `onPageFinished` (idempotencia de T004 lo hace seguro; FR-001, R1)
- [ ] T007 [US1] Invalidar el video vigente por navegación en `app/src/main/java/com/example/dreamweb/engine/CustomWebClient.kt` + `app/src/main/java/com/example/dreamweb/ui/BrowserActivity.kt`: añadir callback `onPageStartedCallback: () -> Unit` al client invocado desde `onPageStarted` (main-frame); en la activity, al recibirse → `lastDetectedVideoUrl = null` y `externalPlayerButton.visibility = GONE` (navegación = único invalidador determinista, sin TTL ni sondeo; FR-002, R3)
- [ ] T008 [US1] Restauración condicionada del botón externo en `app/src/main/java/com/example/dreamweb/ui/BrowserActivity.kt`: en el callback `onFullscreenChanged(false)` restaurar `externalPlayerButton.visibility = VISIBLE` **solo si** `lastDetectedVideoUrl != null`; si el sniffer notifica un video nuevo estando en fullscreen, actualizar el estado pero aplicar la visibilidad al salir (US1 escenarios 3 y 4; FR-002, R3)
- [ ] T009 [US1] Reordenar `launchPlayer` en `app/src/main/java/com/example/dreamweb/managers/VideoPlayerManager.kt` (validar → marcar → actuar): `if (isPlaying) return`; `webView?.context ?: return` **sin** tocar `isPlaying`; `(context as? Activity)` con `isFinishing || isDestroyed` → return limpio; congelar WebView (`onPause()` + `pauseTimers()`); `isPlaying = true`; `startActivity(intent)` en try/catch — en excepción revertir (`isPlaying = false`, `webView?.onResume()` + `resumeTimers()`) y Toast (bug actual: `isPlaying = true` se asigna antes del null-check y queda latcheado; FR-003, R4)
- [ ] T010 [P] [US1] Crear `app/src/main/java/com/example/dreamweb/engine/ExternalUrlClassifier.kt` — Kotlin puro, **sin imports `android.*`** (testeable en JVM): `fun classify(url: String): ExternalAction` con sealed interface `WebLoad | External(scheme: String)`; `http(s)`, `about:`, `data:`, `blob:`, `javascript:` y URLs sin esquema → `WebLoad`; cualquier otro esquema válido `^[a-z][a-z0-9+.-]*:` (`mailto`, `tel`, `sms`, `smsto`, `geo`, `market`, `intent`, `whatsapp`, `fb`, `vnd.youtube`…) → `External(scheme)` (tabla completa de contracts/external-url-schemes.md; FR-004, R2)
- [ ] T011 [US1] Resolver esquemas externos en `shouldOverrideUrlLoading` de `app/src/main/java/com/example/dreamweb/engine/CustomWebClient.kt`: tras el check AdBlock existente, `classify(url)` → para `External` intentar resolución con el sistema (`intent:` → `Intent.parseUri(url, Intent.URI_INTENT_SCHEME)` + `startActivity`; `mailto`/`tel`/`sms`/`smsto`/`geo`/`market`/genéricos → `Intent(ACTION_VIEW, Uri.parse(url))`); en `ActivityNotFoundException`/`SecurityException`/`URISyntaxException` sin resolvedor: para `intent:` leer el extra `browser_fallback_url` del intent parseado y si es http(s) → `view.loadUrl(fallback)`; en cualquier otro caso → `Toast` de aviso ("Ninguna app puede abrir este enlace", estilo hardcodeado coherente con el código existente); `return true` **siempre** para `External` — el WebView nunca navega a un esquema ajeno (depende de T010; FR-004, R2, contrato external-url-schemes.md §Resolución)

**Checkpoint**: US1 verificable de forma independiente — E1 (título+dedup), E2 (esquemas), E3.1–E3.4 (botón/latch) de quickstart.md

---

## Phase 4: User Story 2 - Cero trabajo en background sin justificación (Priority: P2)

**Goal**: El `PerformanceMonitor` solo corre en DEBUG y con app en foreground, con `stop()` simétrico que libera callbacks; el `setInterval` del sniffer JS se apaga al detectar video (backstop 2 min) quedando el `MutationObserver` como detección por eventos; `BrowserActivity` reenvía pausa/reanudación al WebView con `pauseTimers()` acotado al offloading al player.

**Independent Test**: Build release → 0 mensajes periódicos de jank/memoria en logcat durante 5 min (E4). Página con video → tras la detección no queda intervalo JS repitiéndose y una SPA que inserta `<video>` sigue notificando vía observer (E3.5). App a background → WebView pausado; volver → reanuda (E4.3).

### Implementation for User Story 2

- [ ] T012 [P] [US2] Añadir apagado simétrico en `app/src/main/java/com/example/dreamweb/metrics/PerformanceMonitor.kt`: guardar el `Choreographer.FrameCallback` en un campo y exponer `stop()` que haga `Choreographer.getInstance().removeFrameCallback(cb)` + `scope.cancel()` (recrear el scope en `start()` si fue cancelado); hacer `start()` idempotente para no duplicar frame callbacks (FR-005, R5)
- [ ] T013 [US2] Gatear el monitor en `app/src/main/java/com/example/dreamweb/BrowserApp.kt`: arrancar solo si `BuildConfig.DEBUG` (en release nunca arranca — 0 logs, 0 callbacks); añadir `registerActivityLifecycleCallbacks` con contador de activities resumidas → al llegar a 0 `performanceMonitor.stop()`, al volver a >0 (solo DEBUG) `start()` si estaba parado (~30 líneas en la Application; se rechaza `lifecycle:process` por peso — R5) (depende de T012; FR-005)
- [ ] T014 [P] [US2] Apagar el sondeo del sniffer en `app/src/main/java/com/example/dreamweb/engine/CustomWebClient.kt` (`injectVideoSniffer`): guardar `var pollId = setInterval(checkVideos, 10000)`; llamar `clearInterval(pollId)` tras el primer `notify(src)` con src aceptado; añadir backstop `setTimeout(function(){ clearInterval(pollId) }, 120000)` — ningún intervalo supera 2 min por documento; el `MutationObserver` permanece **sin** intervalo como detección por eventos (muere con el documento al navegar, sin cleanup Kotlin) (FR-006, R6, contrato js-sniffer-lifecycle.md)
- [ ] T015 [US2] Reenviar lifecycle al WebView en `app/src/main/java/com/example/dreamweb/ui/BrowserActivity.kt`: añadir `override fun onPause()` → `webView?.onPause()` (pausa de vista, **sin** `pauseTimers()`); en `onResume()` existente → `webView?.onResume()` **y luego** `VideoPlayerManager.releasePlayer(webView)` (orden de R7, idempotente); `pauseTimers()`/`resumeTimers()` quedan exclusivamente en `launchPlayer`/`releasePlayer` (patrón de offloading; FR-007, R7)

**Checkpoint**: US2 verificable — E4 (logcat release limpio, monitor se detiene en background), E3.5 (sin sondeo tras detección)

---

## Phase 5: User Story 3 - Datos consistentes y controlables (Priority: P3)

**Goal**: Listados con `id` que permiten borrado individual por long-press D-pad (historial y favoritos) y rotación FIFO del historial a 500 entradas. (La conexión única FR-009 ya quedó en Foundational/T003 — aquí se verifica.)

**Independent Test**: Borrar una entrada de historial/favoritos desde Home → desaparece y no reaparece al reabrir. Insertar más de 500 → las más antiguas rotan. Inspección de `AppContainer` → un solo `DbHelper` compartido (E5).

### Implementation for User Story 3

- [ ] T016 [P] [US3] Crear entidades `HistoryEntry(id: Long, url: String, title: String, timestamp: Long)` y `Bookmark(id: Long, url: String, title: String, timestamp: Long)` (data-model.md verbatim) en `app/src/main/java/com/example/dreamweb/managers/`; cambiar `getRecentHistory` en `HistoryManager.kt` y `getAllBookmarks` en `BookmarkManager.kt` para devolver `List<HistoryEntry>`/`List<Bookmark>` incluyendo `COLUMN_ID` en la query (conservar orden actual: timestamp DESC y título ASC respectivamente) (FR-010, R9)
- [ ] T017 [P] [US3] Añadir `deleteById(id: Long)` a `app/src/main/java/com/example/dreamweb/managers/HistoryManager.kt` y `app/src/main/java/com/example/dreamweb/managers/BookmarkManager.kt` — borrado por **id**, no por url (la dedup solo garantiza no-consecutividad; una URL puede existir en varias filas) (FR-010, R9)
- [ ] T018 [US3] Añadir rotación FIFO a 500 en `addOrUpdateLatest` de `app/src/main/java/com/example/dreamweb/managers/HistoryManager.kt`: tras cada escritura ejecutar `DELETE FROM history WHERE id NOT IN (SELECT id FROM history ORDER BY timestamp DESC, id DESC LIMIT 500)` (límite fijado: 500 entradas, FIFO por timestamp — data-model.md verbatim) (depende de T004 de US1; FR-011, R9)
- [ ] T019 [US3] Borrado por long-press en `app/src/main/java/com/example/dreamweb/ui/HomeActivity.kt`: adaptar `HistoryAdapter` y `FavoritesAdapter` a las nuevas entidades (`bind` muestra title/url, click → navega a url) y añadir `itemView.setOnLongClickListener` (gesto estándar leanback: DPAD_CENTER mantenido) → `lifecycleScope.launch { deleteById(id) }` + recargar la lista + `Toast` de confirmación; sin controles nuevos, alcance v1 solo borrado individual (depende de T016, T017; FR-010, R9)

**Checkpoint**: US3 verificable — E5 (borrado persiste, rotación a 500, conexión única inspeccionable en `AppContainer`)

---

## Phase 6: User Story 4 - Build de release honesto y artefactos limpios (Priority: P4)

**Goal**: Release con `optimization.enable = true` + `proguard-rules.pro` (keep de `@JavascriptInterface` y factories reflectivas Media3) → APK < 12MB; `glide`/`leanback` eliminados (0 usos); recursos del template purgados; cursor con asset propio y aceleración por `repeatCount`; cleartext global auditado.

**Independent Test**: `./gradlew assembleRelease` → APK optimizado < 12MB (E6). `grep -rn "glide\|leanback" app/src/main/java` → 0 usos. D-pad mantenido → el cursor acelera progresivamente 25→150px/evento (E7). Manifiesto: `usesCleartextTraffic="true"` + justificación persisten.

### Implementation for User Story 4

- [ ] T020 [P] [US4] Limpiar el catálogo en `gradle/libs.versions.toml`: eliminar las entradas `leanback`/`glide` de `[versions]` y `androidx-leanback`/`glide` de `[libraries]` (0 usos verificados en código — la feature `android.software.leanback` del manifiesto se conserva, es del SO); añadir `robolectric` (versión estable compatible SDK ≤34) a `[versions]` y `org.robolectric:robolectric` a `[libraries]` (FR-008, R11/R12)
- [ ] T021 [US4] Actualizar dependencias en `app/build.gradle.kts`: eliminar `implementation(libs.androidx.leanback)` e `implementation(libs.glide)`; añadir `testImplementation(libs.robolectric)` y `testOptions { unitTests.isIncludeAndroidResources = true }` dentro del bloque `android` (depende de T020; FR-008, R11/R12 — robolectric pesa 0 bytes en el APK al ser test-only)
- [ ] T022 [US4] Activar optimización de release en `app/build.gradle.kts`: en `buildTypes.release` poner `optimization { enable = true }` (R8 + resource shrinking en un flag, DSL vigente en AGP 9.2.1) y `proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")` (los keep rules siguen viniendo de `proguardFiles` en AGP 9.2.1 — el source-set `keepRules/` es de AGP 9.3+, no usarlo) (FR-013, R10)
- [ ] T023 [P] [US4] Crear `app/proguard-rules.pro` con las reglas verbatim de R10: `-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }`, `-keep class com.example.dreamweb.engine.SnifferBridge { *; }`, `-keep class androidx.media3.exoplayer.hls.HlsMediaSource$Factory { *; }` y `-keep class androidx.media3.exoplayer.dash.DashMediaSource$Factory { *; }` (factories instanciadas por reflexión desde `DefaultMediaSourceFactory` — sin ellas HLS/DASH falla solo en release; se rechaza el keep amplio de `androidx.media3.**`) (FR-013, R10)
- [ ] T024 [P] [US4] Crear cursor propio `app/src/main/res/drawable/cursor_arrow.xml` (vector: puntero tipo flecha, trazo claro con contorno oscuro, visible sobre fondos claros y oscuros) y en `app/src/main/res/layout/activity_browser.xml` apuntar `virtualCursor` a `@drawable/cursor_arrow` eliminando `@android:drawable/ic_delete`, `@android:drawable/presence_online` y `app:tint` (FR-014, R13)
- [ ] T025 [P] [US4] Purgar recursos del template sin uso: eliminar de `app/src/main/res/values/strings.xml` los strings `browse_title`, `related_movies`, `grid_view`, `error_fragment`, `personal_settings`, `watch_trailer_1`, `watch_trailer_2`, `rent_1`, `rent_2`, `buy_1`, `buy_2`, `movie`, `error_fragment_message`, `dismiss_error` (conservar `app_name` y `external_player_desc`, en uso); eliminar `app/src/main/res/drawable/movie.png`, `app/src/main/res/drawable/default_background.xml` y el color `default_background` de `app/src/main/res/values/colors.xml` (0 referencias verificadas); **conservar** `app_icon_your_company.png` — lo referencia el manifiesto (FR-014, R13)
- [ ] T026 [P] [US4] Crear `app/src/main/java/com/example/dreamweb/managers/DpadAcceleration.kt` — Kotlin puro, verbatim de R14: `object DpadAcceleration { const val BASE_STEP = 25f; const val MAX_STEP = 150f; fun stepFor(repeatCount: Int): Float = (BASE_STEP * (1f + repeatCount * 0.10f)).coerceAtMost(MAX_STEP) }` (FR-015, R14)
- [ ] T027 [US4] Usar aceleración en `app/src/main/java/com/example/dreamweb/managers/DpadHandler.kt`: sustituir el `step` fijo de 25f por `DpadAcceleration.stepFor(event.repeatCount)` en los 4 ejes de `handleKeyEvent` (`repeatCount` crece en auto-repeat y vuelve a 0 al soltar/cambiar dirección → reseteo gratis, sin timers) (depende de T026; FR-015, R14)
- [ ] T028 [US4] Auditoría de manifiesto y dependencias: verificar que `app/src/main/AndroidManifest.xml` conserva `usesCleartextTraffic="true"` con su comentario de justificación (alcance global — decisión de Clarifications; auditar, no tocar) y que `uses-feature android.software.leanback` sigue declarada sin depender de la librería eliminada; `grep -rni "glide\|leanback" app/src/main/java` → 0 coincidencias y toda dependencia restante del catálogo tiene uso en código (FR-008, FR-012, E6)
- [ ] T029 [US4] Compilar y medir: `./gradlew assembleRelease` → APK < 12MB en `app/build/outputs/apk/release/` (depende de T020–T027; SC-007, E6 — si supera el presupuesto registrar la medición contra el baseline de T002 y escalar antes de cerrar la historia)

**Checkpoint**: US4 verificable — E6 (APK, auditoría, manifiesto), E7 (aceleración D-pad)

---

## Phase 7: User Story 5 - Lógica crítica con tests automatizados (Priority: P5)

**Goal**: Cobertura Principio IV completada: managers SQLite vía Robolectric (SQLite real en JVM, sin dispositivo) y lógica nueva determinista (`ExternalUrlClassifier`, `DpadAcceleration`) como tests JVM puros.

**Independent Test**: `./gradlew test` se ejecuta sin dispositivo y pasa con la suite existente (`VideoUrlValidatorTest`, `SnifferBridgeTest`, `VideoLaunchPayloadTest`, `DomainMatcherTest`, `FilterParserTest`, `AdBlock*Test`, `TlsDomainNormalizationTest`) más los 4 archivos nuevos (E0).

### Tests for User Story 5

- [ ] T030 [P] [US5] Crear `app/src/test/java/com/example/dreamweb/managers/HistoryManagerTest.kt` — Robolectric con `@Config(sdk = [34])` y `DbHelper` sobre `RuntimeEnvironment.getApplication()`: verificar insert básico, dedup consecutivo (misma URL que la última fila → UPDATE de título, sin fila nueva), no-dedup no consecutivo (A→B→A → 3 filas), rotación (505 inserciones → quedan 500, las 5 más antiguas descartadas FIFO por timestamp), `deleteById` (la entrada no reaparece en `getRecentHistory`) y orden timestamp DESC (depende de T016–T018; FR-016, R12)
- [ ] T031 [P] [US5] Crear `app/src/test/java/com/example/dreamweb/managers/BookmarkManagerTest.kt` — Robolectric con `@Config(sdk = [34])`: verificar `addBookmark`, `deleteById` y `getAllBookmarks` con orden por título ASC (depende de T016, T017; FR-016, R12)
- [ ] T032 [P] [US5] Crear `app/src/test/java/com/example/dreamweb/engine/ExternalUrlClassifierTest.kt` — JVM puro: cubrir la tabla completa del contrato external-url-schemes.md (`http`/`https`/`about:`/`data:`/`blob:`/`javascript:`/sin esquema → `WebLoad`; `intent`/`mailto`/`tel`/`sms`/`smsto`/`geo`/`market` y genéricos `whatsapp:`/`fb:`/`vnd.youtube:` → `External(scheme)`) (depende de T010; FR-017)
- [ ] T033 [P] [US5] Crear `app/src/test/java/com/example/dreamweb/managers/DpadAccelerationTest.kt` — JVM puro: `stepFor(0) == 25f`, monotonía creciente con `repeatCount`, tope `MAX_STEP = 150f` nunca superado, reseteo (volver a repeatCount 0 → BASE_STEP) (depende de T026; FR-017)
- [ ] T034 [US5] Ejecutar `./gradlew test` completo sin dispositivo: suite existente + los 4 tests nuevos en verde (depende de T030–T033; FR-016/FR-017, SC-008, E0)

**Checkpoint**: US5 verificable — E0 (build + suite JVM completa en verde)

---

## Phase 8: Polish & Cross-Cutting Concerns

**Purpose**: Validación end-to-end de los escenarios manuales y del build release (varios SC exigen verificación en dispositivo por tratarse de WebView/UI)

- [ ] T035 Ejecutar la validación manual de quickstart.md en dispositivo/emulador Android TV: E1 (historial/dedup), E2 (esquemas externos con/sin resolvedor y fallback `intent://`), E3 (10 ciclos fullscreen → botón reaparece; navegación → no reaparece; contexto inválido → sin latch), E5 (long-press borrado) y E7 (aceleración D-pad)
- [ ] T036 [P] Verificación manual obligada del build release (edge case del spec — reglas insuficientes): instalar el APK de `app/build/outputs/apk/release/`, confirmar que el JS bridge detecta video (`@JavascriptInterface` sobrevive a R8) y que HLS y DASH reproducen en el player nativo; durante 5 min de uso `adb logcat` → 0 mensajes periódicos de jank/memoria y 0 sondeos tras detección (E4, E6, SC-005)
- [ ] T037 Cierre: `./gradlew assembleDebug && ./gradlew test && ./gradlew assembleRelease` todo en verde y registrar el resultado final contra SC-001…SC-008 en el quickstart (notas de validación) — especialmente SC-006/SC-007 ya cubiertos por E5/E6

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies - can start immediately
- **Foundational (Phase 2)**: Depends on Setup completion - BLOCKS all user stories (la nueva firma `(DbHelper)` de los managers la consumen US1 y US3 — research R15)
- **User Stories (Phase 3–7)**: All depend on Foundational phase completion
  - Recomendado **secuencial en orden de prioridad** (P1→P5): varias historias tocan los mismos archivos (`CustomWebClient.kt` en US1 y US2; `build.gradle.kts` en US4) — research R15 confirma el orden datos→bugs→background→build→tests
  - US3 tiene dependencia interna: T018 (rotación) extiende `addOrUpdateLatest` creado en T004 (US1)
  - US5 testea el código de US1/US3/US4 — debe ir después de esas implementaciones
- **Polish (Phase 8)**: Depends on all user stories being complete

### User Story Dependencies

- **User Story 1 (P1)**: Can start after Foundational (Phase 2) — T005/T006 dependen de la firma nueva del manager (T003)
- **User Story 2 (P2)**: Can start after Foundational (Phase 2) — independiente de US1 aunque toca el mismo `CustomWebClient.kt` (ejecutar después de US1 para evitar conflictos de merge)
- **User Story 3 (P3)**: Can start after Foundational (Phase 2) — T018 depende de T004 (US1); el resto es independiente
- **User Story 4 (P4)**: Can start after Foundational (Phase 2) — independiente de US1–US3 (archivos disjuntos salvo `DpadHandler.kt`, sin solape con otras historias)
- **User Story 5 (P5)**: Depende de T004/T010/T016–T018/T026 (el código bajo test) — va última por diseño del spec

### Within Each User Story

- Models/logic pura antes que glue (T010 antes de T011; T026 antes de T027)
- Managers antes que consumers/UI (T004 antes de T005; T016+T017 antes de T019)
- Config antes que verificación (T020–T027 antes de T029)
- Story complete before moving to next priority

### Parallel Opportunities

- T002 (medición baseline release) en paralelo con T001
- Dentro de US1: T004 (manager) y T010 (classifier, archivo nuevo) en paralelo; T007/T008/T009 (archivos distintos a manager/classifier) tras T005
- Dentro de US2: T012 (monitor), T014 (script JS) en paralelo
- Dentro de US3: T016 (entidades+reads) y T017 (deleteById) en paralelo
- Dentro de US4: T020, T023, T024, T025, T026 (archivos disjuntos) en paralelo; T021/T027/T029 secuenciales
- Dentro de US5: T030–T033 (4 archivos de test nuevos) todos en paralelo

---

## Parallel Example: User Story 1

```bash
# Lanzar juntos los artefactos sin dependencias entre sí:
Task: "Implementar addOrUpdateLatest en managers/HistoryManager.kt"        # T004
Task: "Crear engine/ExternalUrlClassifier.kt (JVM puro)"                   # T010

# Después, en paralelo los cambios de UI/lifecycle sobre BrowserActivity
# ya pueden prepararse mientras T005/T006 ajustan los WebViewClients:
Task: "Restauración condicionada del botón externo en BrowserActivity.kt"  # T008
Task: "Reordenar launchPlayer en managers/VideoPlayerManager.kt"           # T009
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup (baseline verde + medición release)
2. Complete Phase 2: Foundational (CRITICAL — `DbHelper` único, firma nueva de managers)
3. Complete Phase 3: User Story 1 (bugs de UX visibles)
4. **STOP and VALIDATE**: quickstart E1–E3 en dispositivo
5. Los bugs de US1 son el incremento con mayor impacto de usuario por sí solo

### Incremental Delivery

1. Setup + Foundational → conexión única y firmas listas
2. US1 → bugs de UX corregidos (MVP — lo que el usuario ve)
3. US2 → 0 background injustificado (presupuestos Principio I)
4. US3 → datos controlables (borrado + rotación)
5. US4 → build release optimizado y artefactos limpios
6. US5 → cobertura Principio IV completa + suite verde
7. Polish → validación end-to-end de quickstart E0–E7

### Notas de ejecución

- La mayoría de historias tocan archivos disjuntos, pero `CustomWebClient.kt` (US1+US2) y `HistoryManager.kt` (US1+US3) se comparten → orden secuencial P1→P5 recomendado incluso con capacidad paralela
- Verificación manual obligada: E1–E4/E6/E7 requieren dispositivo o APK release — los tests JVM (US5) no los sustituyen
- Commit después de cada tarea o grupo lógico

---

## Notes

- [P] tasks = different files, no dependencies
- [Story] label maps task to specific user story for traceability (US1–US5 de spec.md)
- FR-009 (conexión única) vive en Foundational/T003 por ser prerequisito de US1 y US3 (research R15); su verificación ocurre en US3 (escenario 3, E5)
- Las constantes decisiones del spec están citadas verbatim en las tareas: dedup **solo consecutiva**, rotación **500 FIFO por timestamp**, backstop **120000 ms**, `BASE_STEP=25f`/`MAX_STEP=150f`, `javascript:` → `WebLoad`, `intent://` → `browser_fallback_url` cuando exista
- Tests exigidos por el spec (US5): Robolectric `@Config(sdk = [34])` para managers SQLite; JVM puro para `ExternalUrlClassifier` y `DpadAcceleration`
- No escribir código de producción fuera de lo listado; cada tarea referencia archivo(s) exacto(s)
