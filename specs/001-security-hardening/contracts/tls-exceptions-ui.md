# Contract: Flujo de excepciones TLS (diálogo + gestión)

**Feature**: `001-security-hardening` | **Fecha**: 2026-11-22

Contrato de comportamiento visible al usuario para la frontera TLS del
reproductor nativo. No es una API de red: la app no expone endpoints externos;
este documento fija el comportamiento exigido por FR-001..FR-005.

## 1. Canal del player (FR-001, FR-002)

- Toda conexión del `OkHttpDataSource` de ExoPlayer usa validación TLS de
  plataforma por defecto. Sin TrustManager trust-all ni hostname verifier
  permisivo en ninguna ruta de código.
- El bypass existe únicamente cuando `TlsExceptionStore.isExcepted(host)` es
  true para el host exacto del socket que está haciendo handshake.

## 2. Diálogo de advertencia (FR-003)

**Disparo**: `onPlayerError` con causa en la cadena `SSLHandshakeException` /
`SSLPeerUnverifiedException` / `CertPathValidatorException`, para un host no
exceptuado, y solo si `!isFinishing && !isDestroyed`.

**Host mostrado**: el dominio del diálogo y el pasado a
`TlsExceptionStore.add()` es el host donde falló el handshake TLS, propagado
desde `DomainTrustManager` (p.ej. un campo `lastRejectedHost` o una excepción
portadora del host) — NO el host de la URL del stream: con redirects, el
handshake que falla puede pertenecer a un host distinto.

**Tipo de problema**: se deriva de la excepción causa más específica de la
cadena `PlaybackException.cause`, mapeada a categorías legibles:

| Excepción causa | Categoría mostrada |
|-----------------|--------------------|
| `CertificateExpiredException` / `CertificateNotYetValidException` | Certificado expirado / aún no válido |
| `CertPathValidatorException` (autofirmado, CA desconocida, cadena incompleta) | Autofirmado o cadena no confiable |
| `SSLPeerUnverifiedException` (fallo del hostname verifier) | El certificado no corresponde al dominio (hostname mismatch) |
| Resto de `CertificateException`/`SSLException` | Problema de certificado (genérico) |

**Contenido mínimo**: dominio afectado + indicación del problema de
certificado + dos opciones navegables por D-pad.

| Acción | Efecto |
|--------|--------|
| **Aceptar (solo este dominio)** | `TlsExceptionStore.add(host)` + reintento automático del stream en la misma Activity |
| **Rechazar** | Cierra diálogo, no persiste nada, la reproducción queda cancelada (error visible; Activity puede terminar) |
| Back/D-pad atrás (dismiss) | Equivale a Rechazar |

**Post-condiciones**:
- Tras Aceptar: el reintento conecta sin volver a preguntar (el host ya está
  exceptuado) → una sola interacción adicional (SC-005).
- Tras Rechazar: un nuevo lanzamiento del mismo stream vuelve a mostrar el
  diálogo (el rechazo no se persiste, FR-005).
- Streams con certificado válido nunca muestran el diálogo (SC-001).
- El diálogo sobrevive configChange: ambas Activities declaran
  `configChanges` en el manifest (rotación no recrea la Activity).

## 3. Gestión en "Seguridad" (FR-004)

**Entrada**: botón "Seguridad" en HomeActivity → `SecuritySettingsActivity`.

**Contenido**: lista de excepciones, una fila por dominio:
`host — aceptado el <fecha>`.

**Acciones**:
- Revocar un dominio individual (foco D-pad por fila + confirmación).
- "Borrar todas las excepciones TLS" → `TlsExceptionStore.clear()` (mínimo
  exigido por spec).

**Efecto inmediato**: la siguiente conexión a un dominio revocado vuelve a
validar TLS real (y a preguntar de nuevo si el certificado sigue inválido).
