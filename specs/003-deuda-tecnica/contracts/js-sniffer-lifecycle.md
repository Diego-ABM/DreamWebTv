# Contract: Ciclo de vida del sniffer JS inyectado (FR-006)

**Feature**: `003-deuda-tecnica` | **Fecha**: 2026-11-23

Contrato del script `injectVideoSniffer` de `CustomWebClient` tras este spec.
Extiende (no sustituye) al contrato del bridge `DreamWebSniffer` de
`specs/001-security-hardening/contracts/js-bridge.md`: el script inyectado es
el *emisor*; el bridge es el *receptor* ya endurecido.

## Funciones del script

```text
checkVideos()         — escanea <video>/<source>, iframes embed/player,
                        window.jwplayer / window.videojs → notify(src)
notify(src)           — filtra http(s) no-blob → DreamWebSniffer.onVideoFound(src)
                        (la validación fuerte es del bridge: VideoUrlValidator)
MutationObserver      — observa document.body {childList, subtree} → checkVideos
setInterval(checkVideos, 10000)   — sondeo fallback
```

## Condiciones de apagado (obligatorias — Principio I)

| Trigger | Efecto |
|---|---|
| Primer `notify(src)` con src aceptado (video detectado) | `clearInterval(pollId)` — el sondeo cumplió su función |
| `setTimeout(…, 120000)` backstop | `clearInterval(pollId)` a los 2 min aun sin detección |
| Navegación / recarga de página | Documento destruido → observer e intervalos mueren con él (sin cleanup manual en Kotlin) |

El `MutationObserver` **no se detiene**: es detección por eventos (permitida
explícitamente por FR-006 como sustituto del sondeo), sin coste por tiempo —
se dispara solo ante mutaciones DOM reales.

## Invariantes

- En cualquier instante tras `onPageFinished` existe **a lo sumo un**
  `setInterval` activo del sniffer por documento, con lifetime ≤ 2 min.
- La detección continua post-intervalo es exclusivamente por eventos
  (observer) — SC-005: "0 intervalos de sondeo activos tras la detección".
- El script no lanza excepciones hacia la página: secciones de reproductores
  envueltas en try/catch (comportamiento actual conservado).
- `notify` solo emite URLs http(s) no-blob; `VideoUrlValidator` (bridge) sigue
  siendo la frontera de seguridad — una src maliciosa que pasara `notify` se
  descarta en el bridge (contrato js-bridge.md §entrada).

## Verificación

- Manual/logcat (debug): tras detectar video, no hay invocaciones repetidas de
  `checkVideos` (instrumentable vía log DEBUG en el bridge).
- Quickstart escenario E3: página con video → espera > 10s tras detección →
  sin actividad de sondeo; página SPA que inserta `<video>` post-detección →
  el observer sigue notificando.
