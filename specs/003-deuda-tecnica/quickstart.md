# Quickstart: Deuda Técnica — guía de validación

**Feature**: `003-deuda-tecnica` | **Date**: 2026-11-23

Escenarios ejecutables que prueban el spec de extremo a extremo. Los detalles
de entidades y contratos viven en `data-model.md` y `contracts/` — aquí solo
se referencian.

## Prerequisitos

- JDK toolchain resoluble por Gradle (foojay resolver ya configurado),
  Android SDK con plataforma 36.
- Dispositivo/emulador Android TV (o proyector leanback) para las verificaciones
  manuales marcadas **[manual]**.
- Rama `003-deuda-tecnica`.

## E0. Compilación y suite unitaria (SC-008, FR-016)

```bash
./gradlew assembleDebug      # compila
./gradlew test               # suite JVM completa, sin dispositivo
```

**Esperado**: BUILD SUCCESSFUL. La suite incluye la cobertura existente
(`VideoUrlValidatorTest`, `SnifferBridgeTest`, `VideoLaunchPayloadTest`,
`DomainMatcherTest`, `FilterParserTest`, `AdBlock*Test`,
`TlsDomainNormalizationTest`) **más** la nueva:
`HistoryManagerTest`, `BookmarkManagerTest` (Robolectric — SQLite real en JVM),
`ExternalUrlClassifierTest`, `DpadAccelerationTest`.

## E1. Historial con título y deduplicación (FR-001, SC-001) **[manual]**

1. Navegar a una página con `<title>` que tarda en cargar (p. ej. una SPA).
2. Volver a Home → sección historial.
3. **Esperado**: la entrada muestra el título definitivo, nunca el de la página
   anterior ni la URL desnuda en entradas con título.
4. Refrescar la misma URL 3 veces seguidas → **1 sola entrada consecutiva**.
5. Navegar A → B → A → el historial tiene A,B,A (dedup solo consecutivo).

## E2. Esquemas externos (FR-004, SC-004) **[manual]**

Según `contracts/external-url-schemes.md`:

- Crear/cargar una página de prueba con enlaces `mailto:`, `tel:`, `market:`,
  `intent://` con `browser_fallback_url` y `intent://` sin fallback.
- **Sin app resolvedora** → Toast de aviso, WebView permanece en la página
  (0 páginas de error internas).
- **Con app** (p. ej. market con Play Store) → abre la app externa.
- `intent://` con fallback y sin app → carga la URL de fallback en el WebView.

## E3. Botón de player externo y fullscreen (FR-002, FR-003, SC-002/003) **[manual]**

1. Página con video detectado → botón externo visible.
2. Entrar a fullscreen nativo del video (10 ciclos entrar/salir) → el botón
   **reaparece las 10 veces** tras salir.
3. Con video detectado: navegar a otra página → botón desaparece (video
   invalidado). Entrar/salir fullscreen → **no** reaparece.
4. Forzar contexto inválido (salir de la activity justo al pulsar reproducir):
   reabrir y pulsar de nuevo → reproduce (0 estado latcheado).
5. Tras detectar video, esperar >10s → en logcat DEBUG no hay sondeo periódico
   del sniffer repitiéndose (contrato `js-sniffer-lifecycle.md`).

## E4. Trabajo en background (FR-005, FR-007, SC-005) **[manual + logcat]**

```bash
# Build release y 5 minutos de uso:
adb shell setprop log.tag.PerformanceMonitor SILENT  # (no necesario: no corre)
adb logcat | grep -i "jank\|System RAM"   # → debe quedar vacío en release
```

1. `assembleRelease` instalado: **0** mensajes periódicos de jank/memoria.
2. En build DEBUG: enviar app a background → el monitor se detiene; al volver
   → reanuda (verificable vía logcat).
3. En BrowserActivity: Home button → volver → la página sigue operativa y
   los videos HTML5 estaban pausados (WebView `onPause`/`onResume` reenviado).
4. Reproducir en player nativo → volver → WebView descongela timers y la
   página reanuda scripts (patrón de offloading intacto).

## E5. Datos: conexión única, borrado, rotación (FR-009/010/011, SC-006)

Automatizado por `HistoryManagerTest`/`BookmarkManagerTest` (Robolectric):

- `addOrUpdateLatest` insert/update por dedup consecutiva.
- Inserción de 505 entradas → quedan 500, las 5 más antiguas rotadas (FIFO).
- `deleteById` elimina sin reaparecer en el listado.

**[manual]**: en Home, pulsación larga sobre una entrada de historial →
desaparece y no vuelve al reabrir la app. Ídem en favoritos. Inspección de
código: `AppContainer` instancia un solo `DbHelper` consumido por ambos
managers.

## E6. Release optimizado y auditoría (FR-008/012/013/014, SC-007)

```bash
./gradlew assembleRelease
ls -la app/build/outputs/apk/release/    # → < 12 MB
```

- Verificar que el APK release funciona: JS bridge detecta video (keep rules
  de `@JavascriptInterface`), HLS y DASH reproducen en el player nativo
  (keep rules de factories reflectivas) — **verificación manual obligada**
  (edge case "reglas insuficientes").
- Manifiesto: `usesCleartextTraffic="true"` + comentario de justificación
  (ya declarado — auditar que persiste).
- Auditoría: `grep -rn "glide\|leanback" app/src/main/java` → 0 usos (el
  manifiesto conserva `uses-feature android.software.leanback`, del SO);
  `libs.versions.toml`
  sin entradas huérfanas; `strings.xml` sin strings del template;
  `virtualCursor` referencia `@drawable/cursor_arrow` propio.

## E7. Cursor D-pad con aceleración (FR-015) **[manual]**

En una página larga, mantener DPAD_RIGHT: el cursor arranca a ~25px/evento y
acelera progresivamente hasta ~150px/evento; al soltar y repulsar, vuelve al
paso base. `DpadAccelerationTest` cubre la curva (monótona, tope, reseteo).

## Criterios de aceptación global

| Criterio | Escenario |
|---|---|
| SC-001 historial sin títulos nulos | E1 + tests managers |
| SC-002/003 botón y latch del player | E3 |
| SC-004 esquemas externos sin fallo silencioso | E2 |
| SC-005 cero background injustificado | E3.5, E4 |
| SC-006 datos consistentes | E5 |
| SC-007 APK <12MB + auditoría | E6 |
| SC-008 build + test verdes | E0 |
