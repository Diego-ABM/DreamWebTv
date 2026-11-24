# Contract: Sesión de reproducción — suspensión y restauración

**Feature**: `004-video-capture-playback` | **Fecha**: 2026-11-23

Contrato de FR-011/FR-012 y US3 (P2): el motor de navegación se suspende
mientras el player está activo y la navegación se restaura exactamente al
volver, con degradación documentada.

## 1. Estados

```text
NAVEGANDO ──launchPlayer()──> CONGELADO ──releasePlayer()──> NAVEGANDO
                                 │
              proceso/activity destruida ──> RECREADA (saveState) o
                                               DESECHADA (last URL)
```

## 2. Congelado (vigente, se conserva)

`VideoPlayerManager.launchPlayer(webView, payloadJson)`:

1. Valida payload y estado (contexto vivo, activity no finishing, latch
   `isPlaying` libre) **antes** de congelar — un lanzamiento inválido nunca
   deja el WebView suspendido.
2. `webView.onPause()` + `pauseTimers()` → cero timers JS/render durante la
   reproducción (Principio I; SC-004: RAM total ≤ 180 MB).
3. `startActivity(NativeVideoActivity, EXTRA_VIDEO_URL=payloadJson)`;
   excepción → reversión completa (`onResume`+`resumeTimers`, latch libre,
   toast) — escenario "fallo al lanzar" del spec.

La instancia WebView no se destruye ni recicla: DOM, scroll y estado JS se
conservan — la restauración exacta del caso común no requiere código nuevo
(US3 esc.2, "sin recarga completa cuando sea evitable").

## 3. Restauración al volver

`BrowserActivity.onResume` → `VideoPlayerManager.releasePlayer(webView)`:

- `resumeTimers()` + `onResume()` → la página sigue donde estaba (misma URL,
  mismo scroll, mismo estado JS).
- El panel de hallazgos quedó cerrado al lanzar; los hallazgos persisten en
  el registry (la página no cambió) y el indicador reaparece.

## 4. Recreación y muerte de proceso (degradación documentada)

| Escenario | Mecanismo | Resultado garantizado |
|---|---|---|
| Activity destruida por presión de memoria con player encima | `onSaveInstanceState` → `webView.saveState(outState)` + `outState.putString("lastPageUrl")`; `onCreate` con bundle → `webView.restoreState()`; si devuelve null → `loadUrl(lastPageUrl)` | pila+página restauradas; peor caso: recarga de la misma URL |
| Proceso muerto (sin bundle) | `BrowserActivity.onCreate` sin `savedInstanceState` ni extra `URL` → última entrada de `HistoryManager` | al menos la última URL navegada (spec US3 esc.3) — nunca pantalla en blanco |

`HistoryManager` ya persiste cada `onPageFinished`; la lectura de fallback
es una consulta `ORDER BY timestamp DESC LIMIT 1` en `lifecycleScope` (sin
bloquear el arranque; si la URL llega después, se navega entonces).

## 5. Salida del player

- `finish()` normal (BACK del usuario, error con mensaje claro FR-012,
  rechazo del diálogo TLS): `player.release()` en `onDestroy` (vigente) y
  control vuelve a `BrowserActivity`.
- `NativeVideoActivity` no altera el WebView: la simetría vive entera en
  `VideoPlayerManager` (Principio V).

## 6. Invariantes

- En ningún camino el WebView queda en `pauseTimers` permanente: todo
  `pause` tiene su `resume` simétrico en `releasePlayer`, que además es
  idempotente (latch).
- RAM durante reproducción ≤ 180 MB: el WebView congelado no renderiza ni
  ejecuta timers; el player usa el mismo `OkHttpClient` acotado.
- Si el proceso del navegador murió, el usuario nunca ve crash ni pantalla
  en blanco — recibe la última URL (SC-003).
- El player jamás se invoca desde fuera de la app (activity `exported=false`
  vigente, FR-014).

## 7. Verificación

- Manual: lanzar video → `dumpsys meminfo` ≤ 180 MB → BACK → misma página,
  misma posición, sin recarga visible.
- Manual: con "Don't keep activities" o `am kill` parcial → al volver, la
  página se restaura por `saveState` o al menos la URL.
- Logcat DEBUG: par `pauseTimers`/`resumeTimers` balanceado en cada ciclo
  de lanzamiento.
