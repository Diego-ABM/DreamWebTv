# Implementation Plan: Deuda Técnica (Bugs UX, Background, Datos, Build, Tests)

**Branch**: `003-deuda-tecnica` | **Date**: 2026-11-23 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/003-deuda-tecnica/spec.md`

## Summary

Saldar la deuda técnica residual no cubierta por los specs 001/002. Cinco
frentes, cada uno con decisión técnica documentada en `research.md`:

1. **Bugs UX**: historial registrado en `onPageFinished`/`onReceivedTitle` con
   upsert idempotente que deduplica consecutivos (hoy se guarda el título de
   la página *anterior* en `onPageStarted`); botón de player externo
   restaurado al salir de fullscreen solo si el video sigue vigente
   (invalidación por navegación); latch `isPlaying` asignado tras validar
   contexto, no antes; esquemas no-web (`intent:`/`mailto:`/`tel:`/`market:`/
   genéricos) resueltos vía intents externos con fallback interno de
   `intent://` y Toast cuando nadie responde — nunca error-page del WebView.
2. **Background**: `PerformanceMonitor` solo arranca en `DEBUG` y gana
   `stop()` (callback Choreographer + scope cancelados) enganchado a un
   contador foreground de activities; el `setInterval` del sniffer JS se
   cancela al primer video detectado (backstop de 2 min) y la detección
   continua pasa al `MutationObserver` ya existente; `BrowserActivity`
   reenvía `onPause`/`onResume` al WebView dejando `pauseTimers()` solo en el
   patrón de offloading al player.
3. **Datos**: un solo `DbHelper` en `AppContainer` inyectado a
   `HistoryManager`/`BookmarkManager` (hoy cada uno crea el suyo → doble
   conexión); borrado individual por id vía long-press; rotación FIFO a
   **500 entradas** (límite fijado).
4. **Build/recursos**: `optimization.enable = true` + `proguard-rules.pro`
   (keep de métodos `@JavascriptInterface` y de las factories reflectivas
   HLS/DASH de Media3) → APK < 12MB; eliminar `glide` y `androidx-leanback`
   (0 usos); strings/drawables del template sin uso fuera; cursor D-pad con
   asset propio y aceleración por `repeatCount`.
5. **Tests**: Robolectric como `testImplementation` para cubrir los managers
   SQLite sin dispositivo (Principio IV), más tests JVM puros del clasificador
   de esquemas y la curva de aceleración.

## Technical Context

**Language/Version**: Kotlin (JVM target 11) sobre AGP 9.2.1 (built-in Kotlin), minSdk 28

**Primary Dependencies**: stack existente — WebView + androidx.webkit, Media3
ExoPlayer (HLS/DASH/UI), OkHttp, SQLite directo (`SQLiteOpenHelper`), appcompat,
recyclerview, kotlinx-coroutines. **Nueva**: `org.robolectric:robolectric` solo
como `testImplementation` (0 bytes en APK — justificado en research R12).
**Eliminadas**: `glide`, `androidx-leanback` (0 usos verificados).

**Storage**: SQLite `dreamweb.db` v1 sin cambios de esquema; conexión única
vía `DbHelper` inyectado desde `AppContainer` (research R8).

**Testing**: JUnit4 + **Robolectric** en `app/src/test` (`./gradlew test`,
sin dispositivo). Lógica nueva determinista como Kotlin puro sin `android.*`
(`ExternalUrlClassifier`, `DpadAcceleration`); managers probados contra
SQLite real emulado por Robolectric.

**Target Platform**: Android TV / leanback, Android 9+ (minSdk 28)

**Project Type**: mobile-app (módulo único `app/`)

**Performance Goals**: APK release optimizado < 12MB; 0 trabajo periódico en
release (0 logs jank/mem, 0 intervalos JS tras detección); cursor D-pad
25→150px/evento progresivo.

**Constraints**: presupuestos de constitución (APK <12MB, Home ≤60MB, video
≤180MB); sin frameworks nuevos de DI/Room; `usesCleartextTraffic` global
documentado (FR-012, decisión del dueño en Clarifications); logging continuo
prohibido fuera de `BuildConfig.DEBUG`.

**Scale/Scope**: ~25 archivos tocados de un codebase de ~20 clases; historial
acotado a 500 filas; sin migración de esquema.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principio | Evaluación | Resultado |
|-----------|------------|-----------|
| I. Eficiencia de recursos | **Elimina** trabajo en background (monitor gated + stop, intervalo JS con apagado) y 2 dependencias muertas. Lo único añadido: un contador foreground (~30 líneas) y un vector de cursor propio (~1KB). Sin WebViews nuevos; pool intacto. | PASS (impacto neto negativo) |
| II. Seguridad/privacidad | No toca TLS ni trust managers. Nuevo punto de entrada: intents externos — se resuelven con el sistema (sin handlers in-app) y `intent://` solo carga fallback http(s) en el WebView. `@JavascriptInterface` sin cambios de superficie; keep rules lo protegen en release. | PASS |
| III. Features verificables | Todo el spec es corrección medible: SC verificables por tests (managers, clasificador), por build (APK, auditoría deps) o por escenarios manuales documentados en quickstart. | PASS |
| IV. Tests en lógica crítica | Robolectric completa la cobertura exigida (managers SQLite); la lógica determinista nueva (clasificador esquemas, aceleración) es JVM pura con test propio (FR-017). Sniffer/payload/matcher ya cubiertos. | PASS |
| V. Ciclo de vida/ownership | El spec *es* disciplina de lifecycle: stop simétrico del monitor, lifecycle reenviado al WebView, latch sin estado falso, `pauseTimers` acotado al offloading. `DbHelper` único con owner claro (`AppContainer`). Sin `GlobalScope` nuevo. | PASS |

**Post-Phase-1 re-check**: sin violaciones. Complejidad nueva declarada:
Robolectric (test-only, 0 bytes en release), contador foreground en
`BrowserApp` (sustituye a `lifecycle:process`, dependencia más pesada —
justificado en R5), vector de cursor propio (~1KB). No se requiere entrada en
Complexity Tracking.

## Project Structure

### Documentation (this feature)

```text
specs/003-deuda-tecnica/
├── plan.md              # This file (/speckit-plan command output)
├── research.md          # Phase 0 output — 15 decisiones (R1-R15)
├── data-model.md        # Phase 1 output — entidades + reglas de estado
├── quickstart.md        # Phase 1 output — 8 escenarios de validación E0-E7
├── contracts/
│   ├── external-url-schemes.md   # tabla esquema→comportamiento (FR-004)
│   └── js-sniffer-lifecycle.md   # condiciones de apagado del sondeo (FR-006)
├── checklists/requirements.md    # preexistente (speckit-checklist)
└── tasks.md             # Phase 2 output (/speckit-tasks — NOT created here)
```

### Source Code (repository root)

```text
app/src/main/
├── java/com/example/dreamweb/
│   ├── BrowserApp.kt            # DEBUG-gate monitor + foreground counter → stop()
│   ├── AppContainer.kt          # DbHelper único inyectado a managers
│   ├── engine/
│   │   ├── CustomWebClient.kt       # historial en onPageFinished; pegamento de
│   │   │                            #   esquemas externos; sniffer JS con apagado;
│   │   │                            #   invalida video en onPageStarted(main-frame)
│   │   ├── CustomWebChromeClient.kt # reenvía onReceivedTitle → historial
│   │   ├── ExternalUrlClassifier.kt # NUEVO — Kotlin puro: url → WebLoad|External
│   │   ├── SnifferBridge.kt         # sin cambios
│   │   └── WebViewPool.kt           # sin cambios
│   ├── managers/
│   │   ├── DbHelper.kt              # instanciado 1 vez (en AppContainer)
│   │   ├── HistoryManager.kt        # ctor (DbHelper); addOrUpdateLatest +
│   │   │                            #   rotación 500 + deleteById + entidad con id
│   │   ├── BookmarkManager.kt       # ctor (DbHelper); deleteById + entidad con id
│   │   ├── VideoPlayerManager.kt    # latch tras validar contexto + try/catch
│   │   ├── DpadHandler.kt           # step dinámico via DpadAcceleration
│   │   └── DpadAcceleration.kt      # NUEVO — Kotlin puro: repeatCount → step
│   ├── metrics/PerformanceMonitor.kt # guarda FrameCallback; +stop(); logs DEBUG
│   └── ui/
│       ├── BrowserActivity.kt       # onPause/onResume → WebView; restauración
│       │                            #   condicionada del botón externo
│       └── HomeActivity.kt          # adapters con id + long-press → deleteById
├── res/
│   ├── drawable/cursor_arrow.xml    # NUEVO — vector propio del cursor
│   ├── drawable/movie.png           # ELIMINADO (0 refs)
│   ├── drawable/default_background.xml # ELIMINADO (0 refs)
│   ├── drawable/app_icon_your_company.png # CONSERVADO (lo usa el manifest)
│   ├── layout/activity_browser.xml  # virtualCursor → @drawable/cursor_arrow
│   └── values/strings.xml           # purge de strings del template sin uso
├── AndroidManifest.xml              # cleartext global + justificación (ya hecho
│                                    #   en spec 001 — auditar, no tocar)
└── proguard-rules.pro               # NUEVO — keep @JavascriptInterface +
│                                    #   HlsMediaSource$Factory/DashMediaSource$Factory
├── build.gradle.kts                 # optimization.enable=true; proguardFiles;
│                                    #   -glide -leanback; +robolectric test;
│                                    #   unitTests.isIncludeAndroidResources
└── ../gradle/libs.versions.toml     # -glide -leanback; +robolectric

app/src/test/java/com/example/dreamweb/
├── managers/
│   ├── HistoryManagerTest.kt        # NUEVO — Robolectric: dedup, rotación,
│   │                                #   deleteById, título definitivo
│   └── BookmarkManagerTest.kt       # NUEVO — Robolectric: add, deleteById, list
├── engine/ExternalUrlClassifierTest.kt  # NUEVO — tabla completa de esquemas
└── managers/DpadAccelerationTest.kt     # NUEVO — monotonía, tope, reseteo
```

**Structure Decision**: módulo único existente, sin paquetes nuevos salvo el
plano de `engine/`/`managers/` ya en uso. Toda lógica determinista nueva se
extrae a clases Kotlin puras (mismo patrón que spec 002: `FilterParser`,
`DomainMatcher`) para cumplir Principio IV sin Robolectric donde baste JVM;
Robolectric se reserva a lo que exige framework Android real (SQLite).

## Decisiones tomadas (documentadas — sin clarificador disponible)

1. **Historial = upsert idempotente `addOrUpdateLatest`** invocado desde
   `onPageFinished` + `onReceivedTitle` (R1): cubre título tardío y dedup
   consecutivo sin coordinación extra; la escritura en `onPageStarted` se
   elimina (causa del bug).
2. **Vigencia del video acotada a la página actual** (R3): navegación de
   main-frame invalida `lastDetectedVideoUrl`; sin TTL ni sondeo extra — es la
   única señal determinista compatible con Principio I.
3. **Resolución de esquemas con el sistema, no whitelist** (R2): clasificador
   por prefijo de esquema (JVM puro) + glue fino; `intent://` usa su
   `browser_fallback_url` interna cuando existe (default del spec).
4. **Monitor con apagado simétrico real** (R5): `stop()` + contador
   foreground en `BrowserApp` en vez de `lifecycle:process` (dependencia
   rechazada por peso).
5. **Sniffer: intervalo cancelado al detectar + backstop 2 min** (R6); el
   `MutationObserver` queda como detección por eventos — opción explícitamente
   permitida por el spec.
6. **Límite de historial fijado: 500 entradas FIFO** (R9) — valor asumido del
   spec confirmado; documentado en `data-model.md`.
7. **Borrado por long-press DPAD_CENTER** (R9): gesto estándar leanback, sin
   controles nuevos; v1 solo borrado individual (Assumption del spec).
8. **Keep rules puntuales** (R10): factories reflectivas HLS/DASH +
   `@JavascriptInterface`; rechazado el keep amplio de `androidx.media3.**`.
9. **Robolectric para managers SQLite** (R12): única vía estándar para
   `./gradlew test` sin dispositivo; `testImplementation` → 0 impacto en APK.
10. **`app_icon_your_company.png` se conserva** (R13): referenciado por el
    manifest; sustituirlo es mejora de producto fuera de scope.

## Advertencias / verificaciones pendientes

- **APK <12MB**: medir `assembleRelease` real; glide+leanback fuera y R8
  deberían reducir, pero el baseline nunca se midió — quickstart E6.
- **Release manual obligado**: verificar JS bridge + HLS/DASH en el APK
  optimizado (las keep rules son la mitigación del edge case conocido).
- **Robolectric vs compileSdk 36**: fijar `@Config(sdk = [34])` en los tests de
  managers para desacoplar de la cobertura de SDKs del release de Robolectric;
  añadir `unitTests.isIncludeAndroidResources = true`.
- **`javascript:` clasificado como `WebLoad`** (ver contrato): es el
  comportamiento estándar de `shouldOverrideUrlLoading`; si se quisiera más
  restrictivo habría que especificarlo — queda anotado por si el usuario lo
  quiere revisar.
