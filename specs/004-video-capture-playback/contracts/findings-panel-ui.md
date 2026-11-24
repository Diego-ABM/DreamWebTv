# Contract: Indicador de hallazgos y panel D-pad

**Feature**: `004-video-capture-playback` | **Fecha**: 2026-11-23

Contrato de presentación (FR-006/FR-007, FR-016…018). Decisiones de
clarificación: indicador no intrusivo + panel bajo demanda; lanzamiento
siempre manual; sin diálogos automáticos ni auto-play.

## 1. Elementos (en `activity_browser.xml`)

```text
videoIndicatorButton   — badge no intrusivo (esquina, ~80dp, texto con nº
                         de hallazgos). visible iff findings ≥1.
findingsPanel          — FrameLayout overlay (lateral derecho, ~35% ancho)
                         con RecyclerView; visible iff abierto por el usuario.
                         translationZ por encima de webView y del cursor.
```

`videoIndicatorButton` reutiliza la posición/rol del actual
`externalPlayerButton` (que deja de ser "lanzar lo último detectado" y pasa
a ser "abrir hallazgos").

## 2. Máquina de estados de visibilidad

| Estado | Indicador | Panel |
|---|---|---|
| Sin hallazgos | GONE | GONE |
| ≥1 hallazgo, panel cerrado | VISIBLE (+ nº) | GONE |
| Panel abierto | VISIBLE | VISIBLE, foco en 1ª fila |
| Navegación main-frame | se reevalúa (registry.clear → normalmente GONE) | se cierra |
| Player lanzado | — | se cierra antes de lanzar |
| Fullscreen web (custom view) | GONE | GONE |

- Nada aparece solo por detectar: el indicador se muestra al primer hallazgo
  pero el panel exige acción del usuario (FR-006).
- `dispatchKeyEvent`: con panel abierto, D-pad navega el RecyclerView por
  foco estándar (arriba/abajo entre hallazgos; izquierda/derecha expande la
  sub-fila de calidades si existe); `KEYCODE_BACK` cierra el panel antes que
  el historial de navegación.
- Con panel cerrado, `dpadHandler` sigue pudiendo clicar el indicador (ya es
  una vista nativa en `nativeViews`); además `KEYCODE_MENU` o el click del
  cursor lo abren.

## 3. Fila de hallazgo (item del RecyclerView)

```text
[formato] Título truncado           1080p · 22:04
        subtítulos: ES · EN          (solo si hay)
        ▸ Calidad: [Auto] 720p 480p  (sub-fila, solo si >1 grupo)
```

- Metadatos presentados: título (o host del stream si vacío), formato
  (HLS/DASH/MP4…), resolución y duración formateadas cuando existan — los
  campos ausentes no bloquean la fila (FR-002/edge case).
- Si el sondeo acotado (R5) completa metadatos tras pintar la fila, el item
  se actualiza (`notifyItemChanged`) sin mover el foco.
- Hallazgo cuya fuente muta (FR-005): la fila refleja el stream vigente.

## 4. Selector de calidad (sub-fila)

| Regla | Contrato |
|---|---|
| 1 grupo de resolución | sin sub-fila (sin selector de opción única — US4 esc.4) |
| >1 grupos | una opción por etiqueta (`720p`, `480p`, `Auto`), nunca por URL cruda (FR-016) |
| Default | `QualityPreferenceMemory.lastResolutionLabel` si existe en el grupo (FR-018); si no, opción preferida por formato: HLS > DASH > progresivo a igual resolución (FR-017) |
| Confirmación | CENTER sobre la opción → queda seleccionada y se lanza esa rendición |
| Lanzar sin elegir | CENTER sobre la fila principal → lanza `primaryRendition` (el default) |

## 5. Lanzamiento

`CENTER` en fila u opción → `VideoPlayerManager.launchPlayer(webView,
payloadJson)` construido desde el hallazgo + rendición elegida (ver
`contracts/playback-context-payload.md`). Nunca desde otro disparador: sin
auto-play, sin doble lanzamiento (latch `isPlaying` vigente).

## 6. Invariantes

- 0 diálogos automáticos de la feature en ningún flujo (FR-006).
- Panel siempre cerrable con BACK; nunca bloquea la navegación del WebView
  (es overlay no modal).
- El indicador no roba foco ni tapa contenido interactivo crítico (esquina,
  tamaño fijo, z-index bajo el cursor virtual).
- Preferencia de calidad: solo `QualityPreferenceMemory` (RAM); nada se
  escribe en disco (FR-018).

## 7. Verificación

- Manual TV: página con video → aparece indicador sin abrir panel; abrir →
  lista navegable con flechas; BACK cierra; lanzar → player fullscreen.
- Manual: página multi-rendición → grupos por resolución y default HLS;
  segunda reproducción de la sesión → última elección preseleccionada.
