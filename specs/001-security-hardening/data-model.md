# Data Model: Security Hardening (TLS + JS Bridge)

**Date**: 2026-11-22 | **Feature**: `001-security-hardening`

Modelo mínimo: una sola entidad persistida y dos objetos de valor en memoria.
No hay relaciones ni migraciones; la persistencia es SharedPreferences.

## Entidades

### TlsDomainException (persistida)

Dominio exceptuado de la validación TLS por consentimiento del usuario.

| Campo | Tipo | Reglas |
|-------|------|--------|
| `host` | String (clave) | Normalizado: lowercase, sin esquema, sin puerto, sin path. Exacto — no matchea subdominios (`example.com` ≠ `cdn.example.com`). No vacío. |
| `acceptedAtEpochMs` | Long | Epoch millis de la aceptación. Solo informativo en UI. |

**Persistencia**: archivo SharedPreferences `tls_exceptions`
(`Context.MODE_PRIVATE`). Cada entrada es `host → acceptedAtEpochMs`. Todas las
claves del archivo constituyen el conjunto de excepciones; `clear()` implementa
"borrar todas".

**Reglas de validación**:
- Escritura únicamente desde la acción "Aceptar" del diálogo TLS (FR-003/FR-004).
- El rechazo del diálogo nunca escribe (FR-005).
- Revocación: `remove(host)` por ítem o `clear()` global desde
  SecuritySettingsActivity.
- Consulta: `DomainTrustManager` + hostname verifier, en cada handshake del
  canal del player.

**Transiciones de estado**:

```text
[sin excepción] --usuario Acepta en diálogo--> [exceptuado(host)]
[exceptuado(host)] --usuario Revoca/Borra todas--> [sin excepción]
(rechazo: sin transición — el rechazo no persiste)
```

## Objetos de valor (no persistidos)

### VideoLaunchPayload

Resultado de parsear el extra `EXTRA_VIDEO_URL` (`url|REFERER|ua|COOKIES|cookies`,
formato actual inline en `NativeVideoActivity`).

| Campo | Tipo | Origen |
|-------|------|--------|
| `url` | String | Segmento antes del primer delimitador |
| `referer` | String | Tras `\|REFERER\|` (vacío si ausente) |
| `userAgent` | String | Tras `\|UA\|` (UA TV por defecto si ausente) |
| `cookies` | String | Tras `\|COOKIES\|` (vacío si ausente) |

**Validación**: `url` no vacía es condición para construir el MediaItem; la
actividad ya hace `finish()` si el extra está ausente.

### DetectedVideoCandidate (conceptual — validación en tránsito)

Cadena de URL recibida por `VideoUrlValidator.isVideoUrl()`. Pasa a ser
`lastDetectedVideoUrl` solo si: esquema `http`/`https` + patrón de video
reconocible + no segmento + no ad/tracker. Todo lo demás se descarta
silenciosamente (log en debug, FR-007).

## Diagrama de flujo de datos

```text
WebView (página arbitraria)
  │  window.DreamWebSniffer.onVideoFound(url)
  ▼
SnifferBridge ── VideoUrlValidator.isVideoUrl ──► BrowserActivity.lastDetectedVideoUrl
                                                      │ botón player
                                                      ▼
                                          VideoPlayerManager → NativeVideoActivity
                                                      │ VideoLaunchPayload.parse
                                                      ▼
                                          PlayerHttpClientFactory
                                            ├─ OkHttpClient
                                            │    ├─ DomainTrustManager ──► TlsExceptionStore.isExcepted(host)
                                            │    └─ HostnameVerifier ────► TlsExceptionStore.isExcepted(host)
                                            ▼
                                          ExoPlayer (OkHttpDataSource)
                                            │ SSLException
                                            ▼
                                     Diálogo TLS ──Aceptar──► TlsExceptionStore.add + retry
                                              └─Rechazar──► finish (sin persistir)

HomeActivity "Seguridad" ──► SecuritySettingsActivity ──► TlsExceptionStore.list/remove/clear
```
