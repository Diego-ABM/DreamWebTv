# Contract: Payload de contexto navegador → reproductor (EXTRA_VIDEO_URL)

**Feature**: `004-video-capture-playback` | **Fecha**: 2026-11-23

Contrato del extra `EXTRA_VIDEO_URL` del Intent hacia `NativeVideoActivity`.
Migra el string `url|REFERER|UA|COOKIES` a un modelo tipado JSON con
parse dual de compatibilidad. El Intent sigue siendo interno
(`NativeVideoActivity` `exported="false"`, FR-014).

## 1. Formato productor (único emitido por el código nuevo)

```json
{
  "v": 2,
  "url": "https://cdn.e.com/v/master.m3u8",
  "referer": "https://site.e.com/watch",
  "userAgent": "Mozilla/5.0 (…) DreamWebTV/1.0 (SmartTV)",
  "cookies": "session=abc; pref=tv",
  "headers": {
    "X-Requested-With": "XMLHttpRequest",
    "Authorization": "Bearer …"
  },
  "subtitles": [
    {"url": "https://cdn.e.com/s/es.vtt", "mimeType": "text/vtt",
     "language": "es", "label": "Español"}
  ],
  "startPositionMs": 41200,
  "mimeType": "application/vnd.apple.mpegurl"
}
```

| Campo | Oblig. | Regla |
|---|---|---|
| `v` | sí | versión de esquema = 2 |
| `url` | sí | stream elegido (la rendición seleccionada) |
| `referer` | no | `pageUrl` del hallazgo (página origen); `""` si ausente |
| `userAgent` | no | UA vigente del WebView en el lanzamiento; default `DEFAULT_USER_AGENT` |
| `cookies` | no | `CookieManager.getCookie(pageUrl)` en tiempo de lanzamiento; `""` si ausente |
| `headers` | no | mapa sanitizado de headers capturados (ver §3) |
| `subtitles` | no | solo pistas filtradas por `SubtitleTrackFilter` |
| `startPositionMs` | no | `positionSec × 1000` del último reporte; `0` = desde el inicio |
| `mimeType` | no | hint del hallazgo; el player sigue infiriendo por extensión si falta |

## 2. Formato aceptado (parse dual en `VideoLaunchPayload.parse`)

| Entrada | Resultado |
|---|---|
| Empieza por `{` tras trim | parse JSON (esquema v2); campos ausentes → defaults; campo desconocido → ignorado |
| Cualquier otra cosa | parser legado `url\|REFERER\|UA\|COOKIES` (sin cambios; tests existentes siguen verdes) |

- Payload JSON malformado o sin `url` http(s) válida → `parse` devuelve
  `null`-equivalente controlado: el lanzamiento se aborta **antes** de
  congelar el WebView (validación previa en `VideoPlayerManager`, FR-012 +
  escenario "fallo al lanzar" del spec).

## 3. Composición de headers en el canal del player (R8)

`PlayerHttpClientFactory.createDataSourceFactory` aplica, de menor a mayor
precedencia:

1. **`payload.headers`** (capturados): solo nombres `^[A-Za-z0-9-]+$`, sin
   `Host`, `Content-Length`, `Range`, `User-Agent`, `Referer`, `Origin`,
   `Cookie`, `Sec-Fetch-*` (estos últimos los gestiona la capa 2 aunque el
   reporte los afirme).
2. **Gestionados** (autoritativos — provienen del navegador, no del JS):
   `User-Agent` vía `setUserAgent(payload.userAgent)`; `Referer` +
   `Origin` derivado de `payload.referer`; `Cookie` de `payload.cookies`;
   `Sec-Fetch-Mode: cors`, `Sec-Fetch-Site: cross-site`, `Accept: */*`.
3. **CookieJar** (vigente): parsea `payload.cookies` por request — cubre
   redirects a otros hosts del stream donde el header Cookie de capa 2 no
   aplicaría al dominio.

Aplicación: `setDefaultRequestProperties(merged)` → afecta a manifiesto y a
todos los segmentos subsecuentes (FR-009).

## 4. Uso en `NativeVideoActivity`

| Campo | Efecto |
|---|---|
| `url` + `mimeType` | `MediaItem.Builder().setUri().setMimeType()` (hint; extensión manda si falta) |
| `subtitles` | `setSubtitleConfigurations(lista de MediaItem.SubtitleConfiguration)` |
| `startPositionMs` | `player.setMediaItem(item, startPositionMs)` si > 0; si no, desde el inicio |
| headers merged | `OkHttpDataSource.Factory` como hoy (pipeline TLS por dominio intacto, FR-010) |

## 5. Errores y degradación

| Fallo | Comportamiento |
|---|---|
| Extra ausente/vacío | `finish()` (vigente) |
| Payload no parseable | no se congela el WebView; toast + vuelta intacta |
| Stream caducado/denegado (4xx/red) | toast claro "no se pudo reproducir (caducado o sin acceso); recarga la página" + `finish()` (FR-012) |
| TLS no confiable | diálogo por dominio vigente (spec 001), sin cambios |
| DRM comercial | error de ExoPlayer → toast "contenido protegido no soportado" + `finish()` (degradación documentada) |

## 6. Invariantes

- Ningún campo del payload se envía a backend (FR-013): vive en el Intent
  interno y en el `OkHttpClient` del player.
- `headers` nunca puede pisar `User-Agent`/`Referer`/`Origin`/`Cookie`/
  `Sec-Fetch-*`/`Host`/`Content-Length`/`Range` — la página no se
  auto-concede contexto.
- `subtitles` en el payload ya pasaron `SubtitleTrackFilter`: el player no
  recibe formatos que no pueda renderizar.
- `startPositionMs` ≥ 0 y solo se aplica si > 0 (un medio sin posición no
  fuerza `seekTo(0)` redundante).

## 7. Verificación

- `VideoLaunchPayloadTest` (vigente): formato legado completo.
- `VideoLaunchPayloadJsonTest` (nuevo, Robolectric): roundtrip JSON v2,
  defaults, campo desconocido ignorado, headers con caracteres de control
  rechazados, `startPositionMs` negativo → 0.
- Manual: stream con referer obligatorio → 200 (escenario US2-1); página con
  sesión → cookies en petición del manifiesto y segmentos (US2-2); página
  que hace `fetch` con `X-*` → header presente (US2-3).
