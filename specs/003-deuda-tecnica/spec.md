# Feature Specification: Deuda Técnica (bugs, higiene y bootstrap de tests)

**Feature Branch**: `003-deuda-tecnica`

**Created**: 2026-11-22

**Status**: Draft

**Input**: Saldar la deuda técnica restante del análisis no cubierta por
`001-security-hardening` ni `002-adblock-real`: bugs de UX, higiene de
dependencias, trabajo en background perpetuo, lifecycle del WebView, gestión de
datos, configuración de manifest/build y bootstrap de tests de lógica crítica
(Principios I, IV y V de la constitución).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Bugs de UX y navegación corregidos (Priority: P1)

Como usuario, el historial muestra los títulos reales de las páginas que visité,
el botón de reproductor externo reaparece al salir de pantalla completa cuando hay
video detectado, y los enlaces a apps externas (intent://, mailto, market) se
manejan sin errores.

**Why this priority**: Son los bugs visibles que degradan la experiencia diaria;
cada uno tiene causa raíz identificada en el análisis y fix acotado.

**Independent Test**: Visitar página con título conocido → aparece en historial con
ese título. Entrar y salir de fullscreen con video detectado → botón visible.
Click en enlace `intent://`/`mailto:` → se abre handler o toast, nunca error en el
WebView.

**Acceptance Scenarios**:

1. **Given** navegación a página con `<title>`, **When** termina de cargar,
   **Then** el historial guarda url + título real (registro en `onPageFinished`/
   `onReceivedTitle`, no en `onPageStarted`).
2. **Given** dos visitas consecutivas a la misma URL, **Then** el historial no
   muestra duplicados consecutivos.
3. **Given** video detectado y fullscreen activado, **When** el usuario sale de
   fullscreen, **Then** `externalPlayerButton` vuelve a visible si
   `lastDetectedVideoUrl` sigue presente.
4. **Given** `webView` nulo al invocar `VideoPlayerManager.launchPlayer`,
   **Then** `isPlaying` no queda latcheado en true.
5. **Given** click en `intent://`/`mailto:`/`tel:`/`market:`, **Then** se intenta
   resolver con `Intent.parseUri`/handler externo; sin handler → toast amigable,
   nunca carga fallida en el WebView.

---

### User Story 2 - Higiene de dependencias y trabajo en background (Priority: P2)

Como mantenedor, el build declara exactamente lo que usa y nada más; la app no
ejecuta trabajo perpetuo sin justificación ni flag de depuración.

**Why this priority**: Constitución Sección 2 (deps explícitas, eliminar muertas)
y Principio I (prohibido trabajo background permanente sin apagado). Impacto
directo en presupuesto de APK y RAM.

**Independent Test**: `libs.versions.toml` declara `kotlinx-coroutines-android`;
`glide` y `leanback` eliminados (mantener solo `uses-feature`/`LEANBACK_LAUNCHER`
en manifest); build compila; PerformanceMonitor solo corre con flag debug.

**Acceptance Scenarios**:

1. **Given** `app/build.gradle.kts`, **Then** `kotlinx-coroutines-android` está
   declarado explícitamente y `glide`/`androidx.leanback` no aparecen.
2. **Given** build release, **Then** `PerformanceMonitor` no loguea jank/memoria
   (gateado por flag de debug) y tiene método de parada.
3. **Given** el JS sniffer inyectado, **Then** no corre `setInterval` perpetuo:
   se detiene tras detectar video o usa MutationObserver sin polling fijo.
4. **Given** BrowserActivity en background, **Then** `webView.onPause()` se invoca
   en `onPause` y `onResume()`/`resumeTimers()` en `onResume`, coordinado con
   `VideoPlayerManager.releasePlayer`.
5. **Given** `DbHelper`, **Then** una sola instancia compartida desde
   `AppContainer` sirve a HistoryManager y BookmarkManager.

---

### User Story 3 - Datos gestionados y configuración de build correcta (Priority: P2)

Como usuario, puedo eliminar entradas del historial y favoritos; el historial no
crece sin límite; los sitios `http://` legados cargan según la decisión explícita
del proyecto; el APK release se aproxima al presupuesto <12MB.

**Why this priority**: Gestión básica de datos que el producto necesita para ser
usable, más las decisiones de build/manifest que bloquean los presupuestos.

**Independent Test**: Long-press en entrada de historial/favorito → opción borrar.
Navegar a `http://` según configuración decidida. `assembleRelease` con
optimización activada → APK medido.

**Acceptance Scenarios**:

1. **Given** entrada de historial/favorito, **When** el usuario solicita borrarla,
   **Then** desaparece de la lista y de SQLite (método delete en managers).
2. **Given** historial acumulado, **Then** existe límite/rotación documentado.
3. **Given** decisión sobre `usesCleartextTraffic` (justificada para streaming
   legado http), **Then** está declarada explícitamente en el manifest.
4. **Given** build release, **Then** `optimization` está activada con keep rules
   para el `JavascriptInterface` y el tamaño se mide contra el presupuesto <12MB.
5. **Given** `strings.xml` y `virtualCursor`, **Then** strings del template
   Leanback sin uso eliminados y cursor con asset propio.
6. **Given** D-pad mantenido, **Then** el cursor acelera (paso creciente) en vez
   de 25px fijos por pulsación.

---

### User Story 4 - Bootstrap de tests de lógica crítica (Priority: P3)

Como mantenedor, existe infraestructura de tests unitarios que cubre la lógica
determinista de alto riesgo, ejecutable sin dispositivo.

**Why this priority**: Principio IV de la constitución; sin ella, regresiones en
parsing/sniffer/datos no se detectan. P3 porque depende de los refactors de
extracción a funciones puras.

**Independent Test**: `./gradlew test` ejecuta y pasa tests para: decisión del
sniffer (isVideoFormat/isSegment/isAdOrTracker extraída a función pura), parsing
del payload `url|REFERER|UA|COOKIES`, matching de filtros, y managers SQLite.

**Acceptance Scenarios**:

1. **Given** `app/src/test/` con `testImplementation junit` (+ coroutines-test /
   robolectric si hace falta Context), **Then** `./gradlew test` corre verde.
2. **Given** la lógica del sniffer, **Then** está extraída a función/clase pura
   testeable fuera del WebViewClient con casos: `.m3u8` detectado, `.ts` excluido,
   dominio de ads excluido, mp4 vs m3u8 prioridad.
3. **Given** el parser de payload de video, **Then** tests cubren con/sin
   referer, UA y cookies, y entradas malformadas.

---

### Edge Cases

- `onPageFinished` puede dispararse múltiples veces por página (iframes/SPA) →
  dedupe por URL antes de insertar en historial.
- `intent://` malformado o con `S.browser_fallback_url`: parsear con fallback;
  nunca propagar excepción al WebView.
- `pauseTimers()` es global a todos los WebViews: coordinar con el congelamiento
  del player para no reanudar timers mientras el video nativo sigue activo.
- Activar R8 puede romper `JavascriptInterface` y reflection de Media3 → keep
  rules requeridas y smoke test del flujo sniffer→player en release.
- Borrado de historial mientras la lista está visible: actualización de UI tras
  delete.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: El historial MUST registrar url + título tras la carga de página
  (no en `onPageStarted`) y deduplicar entradas consecutivas de la misma URL.
- **FR-002**: `externalPlayerButton` MUST restaurar visibilidad al salir de
  fullscreen cuando `lastDetectedVideoUrl` siga presente.
- **FR-003**: `VideoPlayerManager.launchPlayer` MUST asignar `isPlaying` solo tras
  validar que puede lanzarse.
- **FR-004**: `shouldOverrideUrlLoading` MUST manejar esquemas no-http(s)
  (`intent://` parseado con `Intent.parseUri` + fallback; `mailto:`/`tel:`/
  `market:` a handlers externos; sin handler → toast) sin error en el WebView.
- **FR-005**: `kotlinx-coroutines-android` MUST declararse en
  `libs.versions.toml`; `glide` y `androidx.leanback` MUST eliminarse de las deps.
- **FR-006**: `PerformanceMonitor` MUST estar gateado tras flag de depuración y
  exponer `stop()`; el sniffer JS MUST no correr polling perpetuo.
- **FR-007**: BrowserActivity MUST delegar `onPause`/`onResume` al WebView,
  coordinado con `VideoPlayerManager`.
- **FR-008**: Un único `DbHelper` compartido desde `AppContainer`; managers MUST
  ofrecer `delete` y el historial MUST tener límite o rotación documentada.
- **FR-009**: `usesCleartextTraffic` MUST declararse explícitamente con
  justificación; release MUST activar `optimization` con keep rules para
  `JavascriptInterface` y APIs reflectivas de Media3.
- **FR-010**: Limpieza de recursos: strings del template sin uso, cursor virtual
  con asset propio, aceleración de cursor D-pad.
- **FR-011**: Infraestructura de tests en `app/src/test/` (`junit`,
  `kotlinx-coroutines-test`, `robolectric` si se requiere Context) con cobertura
  mínima: decisión del sniffer, parser de payload de video, matcher de filtros y
  managers SQLite; ejecutable con `./gradlew test`.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: `./gradlew assembleDebug` compila y `./gradlew test` corre verde.
- **SC-002**: El historial muestra título real en el 100% de páginas visitadas con
  `<title>` definido.
- **SC-003**: APK release con optimización activada medido y reportado contra el
  presupuesto <12MB (gap documentado si no se alcanza).
- **SC-004**: 0 dependencias declaradas sin uso; 0 dependencias usadas sin
  declarar.
- **SC-005**: Logcat en build release no emite métricas de PerformanceMonitor ni
  trazas perpetuas del sniffer.
- **SC-006**: Navegación a `intent://`/`mailto:` produce handler o toast; 0
  errores de página en WebView por esquemas externos.

## Assumptions

- Los fixes P1 de seguridad (TLS + JS bridge) viven en `001-security-hardening`;
  este spec asume que pueden integrarse en cualquier orden sin conflicto.
- La decisión de `usesCleartextTraffic` es del dueño del proyecto; el spec exige
  que sea explícita y justificada, no prescribe el valor.
- R8/optimización puede requerir iteración de keep rules; se acepta PR con
  smoke test manual del flujo sniffer→player como verificación mínima.
- La UI de borrado (long-press vs. botón) queda a criterio de implementación.
