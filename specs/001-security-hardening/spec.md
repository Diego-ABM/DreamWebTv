# Feature Specification: Security Hardening (TLS + JS Bridge)

**Feature Branch**: `001-security-hardening`

**Created**: 2026-11-22

**Status**: Draft

**Input**: Corregir las violaciones de seguridad detectadas en el análisis y marcadas
como deuda en la constitución v1.0.0 (Principio II): TLS trust-all en el reproductor
nativo y `JavascriptInterface` que retiene la Activity y no valida su entrada.

## Clarifications

### Session 2026-11-22

- Q: ¿Dónde debe vivir el punto de entrada para que el usuario vea y revoque las excepciones TLS? → A: Sección "Seguridad" accesible desde la pantalla Home (no dentro del reproductor).
- Q: ¿Dónde se persisten las excepciones TLS por dominio? → A: SharedPreferences (set clave-valor: dominio + fecha de aceptación).
- Q: Cuando el usuario acepta la excepción TLS en el diálogo, ¿qué debe pasar con el stream que falló? → A: Reintento automático: al aceptar, el stream reintenta la reproducción inmediatamente.
- Q: Para validar la entrada del bridge DreamWebSniffer, ¿qué debe contar como "patrón de video reconocible"? → A: Reusar la heurística de detección existente (.m3u8/.mpd/.mp4, playlist.m3u8, etc.) extraída a un validador compartido entre sniffer y bridge.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Reproducción de video con TLS verificado y escape hatch por dominio (Priority: P1)

Como usuario, cuando el reproductor nativo carga un stream, la conexión HTTPS se
verifica de verdad. Si el servidor tiene un certificado válido, el video reproduce
sin fricción adicional. Si el certificado es inválido (expirado, autofirmado, CA no
confiable), se me muestra una advertencia con el dominio afectado y puedo decidir
explícitamente si continuar solo para ese dominio.

**Why this priority**: Es la vulnerabilidad más grave del análisis — hoy cualquier
red hostil puede interceptar las conexiones del player y robar las cookies/cabeceras
que la app reenvía. Es violación directa del Principio II de la constitución.

**Independent Test**: Reproducir un stream con certificado válido → reproduce sin
diálogo. Reproducir contra un servidor con certificado autofirmado → aparece el
diálogo de advertencia; al aceptar, el video reproduce y el dominio queda exceptuado;
al rechazar, se muestra error y no hay reproducción.

**Acceptance Scenarios**:

1. **Given** un stream HTTPS con certificado válido, **When** el usuario lanza el
   reproductor nativo, **Then** el video reproduce sin diálogo ni advertencia.
2. **Given** un stream HTTPS con certificado inválido de un dominio no exceptuado,
   **When** falla el handshake TLS, **Then** se muestra diálogo indicando el dominio
   y el tipo de problema, con opciones Aceptar (solo este dominio) / Rechazar; al
   aceptar, el stream reintenta la reproducción automáticamente.
3. **Given** que el usuario aceptó la excepción para un dominio, **When** vuelve a
   reproducir un stream de ese dominio, **Then** no se vuelve a preguntar.
4. **Given** que el usuario rechazó la excepción, **When** reintenta el mismo stream,
   **Then** se vuelve a mostrar el diálogo (el rechazo no se persiste).
5. **Given** dominios exceptuados previamente, **When** el usuario abre la sección
   "Seguridad" desde la pantalla Home, **Then** ve la lista de excepciones y puede
   revocar cualquier dominio (mínimo: opción "borrar todas las excepciones TLS").

---

### User Story 2 - Bridge JavaScript desacoplado del ciclo de vida y con entrada validada (Priority: P1)

Como usuario, la app no pierde memoria al navegar entre páginas y cerrar el navegador,
y ninguna página web puede abusar del puente `DreamWebSniffer` para inyectar URLs
arbitrarias al reproductor.

**Why this priority**: Doble problema del análisis: (a) leak — el bridge anónimo
captura `BrowserActivity` y el pool retiene el WebView reciclado con la referencia
viva; (b) superficie de ataque — cualquier JS de cualquier página puede llamar
`onVideoFound` con una URL controlada que luego se reproduce con las cookies del
sitio actual.

**Independent Test**: Abrir BrowserActivity → volver a Home → repetir 10 veces →
verificar que no se retiene la Activity (profiler/heap dump). Desde una página de
prueba, ejecutar `DreamWebSniffer.onVideoFound("javascript:alert(1)")` y variantes
(`blob:`, `data:`, `file:`, texto plano) → todas ignoradas.

**Acceptance Scenarios**:

1. **Given** un WebView reciclado al pool, **When** `releaseWebView()` se ejecuta,
   **Then** el bridge JS queda removido (`removeJavascriptInterface`) y los clients
   se resetean, sin referencias a la Activity previa.
2. **Given** JS de la página llama `onVideoFound` con esquema `javascript:`/`data:`/
   `file:`/`blob:` o sin patrón de video reconocible, **When** se recibe el callback,
   **Then** se descarta sin actualizar `lastDetectedVideoUrl` ni mostrar el botón.
3. **Given** JS llama `onVideoFound` con URL `https://` con patrón de video válido,
   **When** se recibe, **Then** se comporta igual que una detección legítima.
4. **Given** 10 ciclos de abrir/cerrar BrowserActivity, **Then** no hay retención de
   la Activity (verificable con heap dump o LeakCanary en build debug).

---

### Edge Cases

- Certificado inválido por motivos distintos (expirado vs. hostname mismatch vs.
  cadena no confiable): el diálogo informa el dominio; la excepción se aplica solo
  a ese dominio, no al tipo de error.
- Servidor IPTV/streaming en LAN o con certificado autofirmado estable: el usuario
  exceptúa una vez y no se vuelve a preguntar.
- El diálogo TLS se dispara mientras `NativeVideoActivity` está terminando o el
  proceso en background: no debe crashear ni dejar al player en estado zombie.
- La URL inyectada por JS usa esquema `https` válido pero apunta a un dominio
  distinto al de la página: permitido (el video puede vivir en CDN externo), pero
  nunca con esquemas no-http(s).
- Rotación/ConfigChange durante el diálogo TLS: el diálogo sobrevive o se
  re-presenta sin duplicar la excepción.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: Toda conexión de red del reproductor nativo MUST usar la
  validación TLS de plataforma por defecto; cualquier TrustManager o
  hostnameVerifier custom MUST delegar en el de plataforma y nunca aceptar
  incondicionalmente (las excepciones solo aplican por dominio previamente
  exceptuado por el usuario).
- **FR-002**: PROHIBIDO TrustManager que acepte todos los certificados o
  hostnameVerifier globalmente permisivo.
- **FR-003**: Ante error de certificado en un dominio no exceptuado, el sistema MUST
  mostrar un diálogo de advertencia que nombre el dominio antes de continuar.
- **FR-004**: Las excepciones TLS MUST ser por dominio, persistirse entre sesiones
  y ser revocables por el usuario.
- **FR-005**: El rechazo del diálogo MUST cancelar la reproducción y no persistirse.
- **FR-006**: `WebViewPool.releaseWebView()` MUST remover el `JavascriptInterface`,
  limpiar clients/listeners previos y no retener referencias a la Activity saliente.
- **FR-007**: Los métodos expuestos vía `JavascriptInterface` MUST validar entrada:
  solo esquemas `http`/`https` con patrón de video reconocible — la misma
  heurística de detección existente (.m3u8/.mpd/.mp4, playlist.m3u8, etc.),
  extraída a un validador compartido; todo lo demás se descarta silenciosamente
  (log en debug).
- **FR-008**: La app MUST no retener Activities tras el reciclaje del WebView
  (verificable con heap dump/LeakCanary en debug).

### Key Entities

- **TlsDomainException**: dominio exceptuado de validación TLS. Atributos: dominio
  (host), fecha de aceptación. Persistencia: SharedPreferences (entrada por
  dominio). Relación: consultado por el cliente HTTP del player antes de cada
  conexión.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: El 100% de streams con certificado válido reproducen sin diálogo
  adicional (misma UX que hoy).
- **SC-002**: 0 instancias de trust-all global en el código (verificable por
  inspección/búsqueda estática).
- **SC-003**: Tras 10 ciclos abrir/cerrar BrowserActivity, 0 Activities retenidas
  en heap dump.
- **SC-004**: 0 URLs no-http(s) aceptadas por el bridge JS en suite de pruebas de
  entrada maliciosa.
- **SC-005**: El usuario puede reintentar un stream de certificado inválido tras
  exceptuar el dominio, en una sola interacción adicional.

## Assumptions

- El WebView ya valida TLS correctamente; el fix aplica solo al canal del
  reproductor nativo (OkHttp/ExoPlayer).
- Las excepciones TLS aplican solo al dominio exacto (sin wildcard ni subdominios)
  en esta iteración.
- No se requiere UI de gestión completa: basta la lista con opción de revocar
  (mínimo "borrar todas").
- El usuario es el único decisor de excepciones; no hay política enterprise.
