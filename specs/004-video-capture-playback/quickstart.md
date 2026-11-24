# Quickstart: Captura y Reproducción de Video Web — guía de validación

**Feature**: `004-video-capture-playback` | **Date**: 2026-11-23

Escenarios ejecutables que prueban el spec de extremo a extremo. Detalles de
entidades y contratos en `data-model.md` y `contracts/` — aquí solo se
referencian.

## Prerequisitos

- JDK toolchain resoluble por Gradle, Android SDK plataforma 36.
- Dispositivo/emulador Android TV (o proyector leanback) para los escenarios
  marcados **[manual]**. Para el fallback de detección (E1b) hace falta un
  WebView sin `DOCUMENT_START_SCRIPT` o forzar la rama con un flag DEBUG.
- Rama `004-video-capture-playback`.
- Páginas de prueba (servir localmente, p. ej. `python3 -m http.server`):
  - **P-A**: HTML con `<video src="…mp4">` estático + `<track kind="subtitles"
    src="…vtt" srclang="es">`.
  - **P-B**: página que crea el `<video>` dinámicamente (JS tras 3 s) y luego
    muta su `src` (P-B' simula post-anuncio).
  - **P-C**: mismo video como `master.m3u8` + `v480.mp4` + `v720.mp4` (varios
    `<source>` o URLs detectables).
  - **P-D**: stream tras endpoint que exige `Referer` (403 sin él, 200 con él)
    y/o cookie de sesión (devolver cookie en la página y exigirla en el
    manifiesto).
  - **P-E**: página sin video (control de falsos positivos).
  - **P-F**: stream https con certificado autofirmado.

## E0. Compilación y suite unitaria

```bash
./gradlew assembleDebug
./gradlew test
```

**Esperado**: BUILD SUCCESSFUL. La suite conserva los tests vigentes
(`VideoUrlValidatorTest`, `SnifferBridgeTest`, `VideoLaunchPayloadTest`…)
**más** la cobertura nueva de lógica crítica (Principio IV):

- `VideoReportParserTest` (Robolectric): esquema completo, campos ausentes,
  tipos hostiles, headers/subtítulos sanitizados, límite 64 KB.
- `VideoFindingsRegistryTest`: dedup por URL, mutación por `mediaKey`, clear
  en navegación, agrupación por resolución + preferencia HLS + memoria de
  sesión.
- `VideoLaunchPayloadJsonTest` (Robolectric): roundtrip JSON v2, defaults,
  dual-parse legado/intacto.
- `SubtitleTrackFilterTest`: solo .vtt/.srt/.ttml/.dfxp sobreviven; el resto se
  omite sin error.

## E1a. Detección temprana (US1, FR-001…004, SC-001) **[manual]**

1. Navegar a **P-A** → antes/al terminar la carga: indicador de hallazgos
   visible con nº=1 (sin diálogo, sin auto-play).
2. Abrir el panel con D-pad → fila con título, formato, resolución y duración
   declarados.
3. Navegar a **P-B** → el video aparece en el panel ≤2 s desde que el stream
   empieza a solicitarse (sin recargar).
4. Navegar a **P-E** → sin indicador (0 hallazgos, sin falsos positivos de
   ads/trackers).
5. Página con 2+ videos distintos → todos en el panel; elegir cualquiera.

## E1b. Fallback sin document-start **[manual]**

- Forzar rama fallback (flag DEBUG o WebView antiguo) → P-A sigue detectando
  a más tardar en `onPageFinished`; P-B detecta vía sondeo acotado +
  `shouldInterceptRequest`. Logcat: sin crash del injector.

## E2. Contexto fiel (US2, FR-009/010, SC-002) **[manual]**

1. **P-D** sin referer devuelve 403 probado por curl → desde el panel se
   lanza y **reproduce** (Referer/Origin/cookies aplicados a manifiesto y
   segmentos).
2. Página con `fetch` que añade header custom observable por
   `requestHeaders` → el header viaja en las peticiones del player
   (verificable en el servidor de prueba).
3. **P-F** → diálogo TLS por dominio (vigente); aceptar → reproduce;
   rechazar → vuelta a navegación intacta. La excepción aparece revocable en
   Seguridad.

## E3. Suspensión y restauración (US3, FR-011, SC-003/004) **[manual]**

1. Con video reproduciéndose: `adb shell dumpsys meminfo com.example.dreamweb`
   → total ≤ 180 MB; logcat muestra par `pauseTimers`/`resumeTimers`.
2. BACK → la página reaparece **misma URL, mismo scroll, mismo estado** (sin
   recarga visible).
3. Developer option "Don't keep activities" → lanzar, salir/volver → la
   página se restaura desde `saveState` (recarga de la misma URL admitida).
4. Error deliberado (stream offline) → toast claro + navegación intacta
   (FR-012); relanzar funciona tras recargar.

## E4. Calidades (US4, FR-016…018, SC-005) **[manual]**

1. **P-C** → panel muestra una entrada por resolución (`Auto`, `720p`,
   `480p`), no por URL; default = la opción HLS.
2. Elegir `480p` → el stream reproducido es el 480 (verificable por bitrate/
   resolución en el servidor o `stats for nerds` del player).
3. Repetir con otro video en la misma sesión → `480p` preseleccionado
   (memoria de sesión; reiniciar la app la borra — no persiste).

## E5. Subtítulos (US5, FR-019/020, SC-007) **[manual]**

1. **P-A** → en el player aparece la pista "Español" seleccionable y se
   renderiza sobre el video.
2. **P-C** sin `<track>` → reproducción sin oferta de pistas ni error.
3. Página con pista `.ass` → omitida; el video y el resto de pistas intactos.

## E6. Actualización de fuente y caducidad (edge cases) **[manual]**

1. **P-B'** muta el `src` tras reproducir un anuncio → el hallazgo refleja el
   stream vigente (sin duplicar entrada).
2. URL firmada que caduca entre detección y lanzamiento → mensaje claro de
   caducidad + navegación intacta para reintentar.

## Métricas de aceptación

| SC | Dónde se verifica |
|---|---|
| SC-001 | E1a pasos 1–3 |
| SC-002 (≥95 %) | E2 pasos 1–2 sobre el set de prueba |
| SC-003 (100 %/0 crashes) | E3 pasos 2–4 |
| SC-004 (≤180 MB) | E3 paso 1 |
| SC-005 | E4 pasos 1–3 |
| SC-006 (0 ads/segmentos) | E1a paso 4 + filtro de `VideoUrlValidator` en tests |
| SC-007 | E5 |
| SC-008 (0 telemetría) | inspección de tráfico: ninguna petición sale a hosts
  ajenos al sitio navegado durante E1–E5 |
