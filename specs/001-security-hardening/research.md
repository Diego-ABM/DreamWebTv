# Research: Security Hardening (TLS + JS Bridge)

**Date**: 2026-11-22 | **Feature**: `001-security-hardening`

No quedaban NEEDS CLARIFICATION en Technical Context tras la sesión de
clarificación del spec; este documento consolida las decisiones de diseño
técnico frente al código existente.

## D1: Mecanismo de excepción TLS por dominio en OkHttp/ExoPlayer

**Decision**: `DomainTrustManager : X509ExtendedTrustManager` que envuelve el
`X509TrustManager` de plataforma (de `TrustManagerFactory` con keystore por
defecto) y delega `checkServerTrusted`. Cuando la delegación lanza
`CertificateException`, extrae el host del peer desde el `SSLSocket`/`SSLEngine`
(las firmas extendidas `checkServerTrusted(chain, authType, socket|engine)` son
invocadas por Conscrypt en Android) y consulta `TlsExceptionStore`: si el host
está exceptuado, acepta; si no, relanza la excepción.

El `HostnameVerifier` se construye de forma simétrica: delega en
`OkHostnameVerifier` por defecto y solo retorna `true` cuando el hostname está
exceptuado. Esto cubre hostname-mismatch, que no pasa por el TrustManager.

**Rationale**: es el patrón estándar (equivalente a MemorizingTrustManager) y el
único que satisface FR-002/FR-004: validación real por defecto, bypass limitado
a un host exacto y consultable en runtime. OkHttp instala el SSLSocketFactory
resultante y ExoPlayer lo consume vía `OkHttpDataSource.Factory` ya presente.

**Alternatives considered**:
- *Network Security Config (res/xml)*: rechazada — declarativa y estática; no
  permite consentimiento en runtime ni revocación, que son FR-003/FR-004.
- *Reconstruir el OkHttpClient tras aceptar la excepción con un trust-all
  filtrado por host*: rechazada — acaba necesitando la misma comprobación por
  host (un trust-all real para un socket concreto sigue requiriendo saber el
  host), pero con ciclo de vida del cliente más complejo y sin cubrir el
  hostnameVerifier limpiamente.
- *CertificatePinner / conscrypt APIs de confianza por sesión*: rechazadas —
  pinning resuelve el problema inverso (más estricto, no más permisivo).
- *Mantener hostnameVerifier permisivo solo*: rechazada — sería trust-all de
  facto (FR-002).

## D2: Detección del fallo TLS y disparo del diálogo

**Decision**: en el `Player.Listener.onPlayerError` existente, recorrer la
cadena `PlaybackException.cause` buscando `SSLHandshakeException`,
`SSLPeerUnverifiedException` o `CertPathValidatorException`. Si hay causa TLS y
la Activity está viva (`!isFinishing && !isDestroyed`), mostrar
`AlertDialog` (AppCompat, ya en classpath) con el host donde falló el
handshake propagado por `DomainTrustManager` (campo `lastRejectedHost` o
excepción portadora del host — con redirects puede diferir del host de la URL
del stream), el tipo de problema (mapeado desde la excepción causa) y opciones
Aceptar (solo este dominio) / Rechazar.

**Rationale**: ExoPlayer propaga el fallo de red del datasource como causa del
PlaybackException; es el punto único donde hoy se manejan errores
(`NativeVideoActivity.onPlayerError`, líneas 137–151), así que el diálogo se
engancha ahí sin nueva maquinaria asíncrona.

**Alternatives considered**:
- *Interceptor/CallEventListener de OkHttp*: rechazada — el error se reportaría
  igualmente al listener del player; añade un componente sin eliminar el
  handling en onPlayerError.
- *HEAD request previo de sondeo*: rechazada — dobla conexiones (Principio I) y
  muchos servidores de streaming no responden HEAD correctamente.

## D3: Acciones del diálogo y reintento

**Decision**: Aceptar → `TlsExceptionStore.add(host)` + reintento automático
llamando de nuevo a `setupPlayer(videoData)` (el player anterior se libera
antes de recrear). Rechazar → persistir nada, cancelar reproducción y cerrar
con Toast de error. El rechazo no se persiste (FR-005), por lo que un nuevo
intento vuelve a preguntar.

**Rationale**: la clarificación fija reintento automático al aceptar; recrear el
player es el camino ya existente y evita estados zombie. Guardando
`videoData`/`url` en campo se re-presenta o re-ejecuta sin duplicar excepción
tras configChange (ambas Activities ya declaran `configChanges` en el manifest,
por lo que el diálogo no se destruye en rotación).

## D4: Persistencia de excepciones

**Decision**: `TlsExceptionStore` en `security/`, backed por un archivo
SharedPreferences propio (`tls_exceptions`), clave = host normalizado
(lowercase, sin puerto — el host viene de `HttpUrl.host`/`Uri.getHost`), valor =
epoch millis de aceptación. API: `isExcepted(host)`, `add(host)`, `remove(host)`,
`clear()`, `list(): Map<String, Long>`. Normalización de dominio en función pura
testeable (`TlsDomainNormalizationTest`).

**Rationale**: decisión clarificada (SharedPreferences). Un archivo dedicado
evita colisiones con otras prefs y hace trivial "borrar todas" (`clear()`).

**Alternatives considered**:
- *Set de strings en SharedPreferences*: rechazada — no admite la fecha de
  aceptación requerida por la entidad TlsDomainException.
- *SQLite (DbHelper existente)*: rechazada — más pesada de lo necesario y la
  clarificación fijó SharedPreferences.

## D5: Validador compartido de URLs de video

**Decision**: `object VideoUrlValidator` en `engine/` con la heurística extraída
verbatim de `CustomWebClient.shouldInterceptRequest` (líneas 58–80): formatos
`.m3u8/.mp4/.mpd/.mkv/.webm//manifest/playlist.m3u8`, exclusión de segmentos
(`.ts`, `/segment`, `range=`) y de ads/trackers (`adsystem`, `doubleclick`,
etc.). Añade validación de esquema: solo `http`/`https` (bloquea
`javascript:`, `data:`, `file:`, `blob:`, texto plano, URLs malformadas). API:
`isVideoUrl(url: String): Boolean`.

**Rationale**: la clarificación fija reusar la heurística existente extraída a
un validador compartido. Usarlo en ambos puntos (sniffer de red y bridge JS)
garantiza que lo que JS reporta pasa exactamente el mismo filtro que lo que la
red detecta (FR-007), y hace la lógica testeable (Principio IV).

## D6: Bridge JS sin retención de Activity

**Decision**: clase nombrada `SnifferBridge` en `engine/`:

```kotlin
class SnifferBridge(private var onVideoFound: ((String) -> Unit)?) {
    @JavascriptInterface
    fun onVideoFound(url: String) {
        if (!VideoUrlValidator.isVideoUrl(url)) { /* log debug, descartar */ return }
        onVideoFound?.invoke(url)
    }
    fun release() { onVideoFound = null }
}
```

`BrowserActivity` instancia el bridge con un callback que llama a su
`onVideoFound`, y `WebViewPool.releaseWebView()` ejecuta la limpieza simétrica
(FR-006, Principio V): `removeJavascriptInterface("DreamWebSniffer")`,
`webViewClient = WebViewClient()` (default), `webChromeClient = null`, y
`(webViewClient-previo as? CustomWebClient)?.release()` para cancelar su
`CoroutineScope` antes de resetear. El callback del bridge queda desreferenciado
al remover la interfaz → la Activity es GC-able.

**Rationale**: elimina la captura fuerte de `BrowserActivity` por el objeto
anónimo retenido en el WebView reciclado. `removeJavascriptInterface` en
releaseWebView es el punto simétrico de `addJavascriptInterface` en
setupWebView. `WebView.getWebViewClient()` existe desde API 26 (minSdk 28 ✓).

**Alternatives considered**:
- *WeakReference<BrowserActivity> dentro del bridge anónimo*: rechazada — sigue
  dejando el interface registrado en el WebView reciclado (una página siguiente
  podría invocarlo contra una activity muerta); el constitution pide removerlo
  o debilitar la referencia, y remover es más limpio.
- *Bridge como singleton en AppContainer*: rechazada — el callback es por-
  sesión de Activity; un singleton reintroduciría acoplamiento a larga vida.

## D7: UI de gestión "Seguridad"

**Decision**: entrada tipo botón en `activity_home.xml` que abre
`SecuritySettingsActivity` (nueva, `ui/`, registrada en manifest, landscape,
no-exported). La Activity muestra un `RecyclerView` con las excepciones
(dominio + fecha de aceptación formateada), revocación por ítem y un botón
"Borrar todas las excepciones TLS" (mínimo exigido por spec + revocación
individual incluida por simetría del adapter ya usado en Home).

**Rationale**: patrón idéntico al de `HomeActivity` (RecyclerView + adapter
inline + ViewBinding), navegable con D-pad, sin dependencias nuevas. Un
AlertDialog con lista fue descartado por peor navegación D-pad y menor margen
para mostrar dominio+fecha.

## D8: Extracción del parser de payload y cliente del player

**Decision**: `VideoLaunchPayload` en `managers/` (`parse(videoData)` →
data class con `url`, `referer`, `userAgent`, `cookies`) extrae el parsing
`url|REFERER|UA|COOKIES` hoy inline en `NativeVideoActivity.setupPlayer`
(líneas 37–49) — la constitución exige test para ese parsing. La construcción
del `OkHttpClient` se mueve a `PlayerHttpClientFactory` en `security/`, que
recibe `TlsExceptionStore` (vía `AppContainer.tlsExceptionStore`), instala
`DomainTrustManager` + hostname verifier delegante, y conserva la
`CookieJar` de cookies del sitio y los headers actuales (Referer, Origin,
Sec-Fetch-*, UA) sin cambios funcionales.

**Rationale**: separa la frontera de confianza TLS (testeable/auditable en un
solo archivo, facilitando SC-002) del plumbing del player; el parsing testeable
cumple Principio IV sin instrumentación.

## D9: Infraestructura de tests

**Decision**: `junit:junit:4.13.2` declarado en `libs.versions.toml`
(`junit` version + library) y `testImplementation(libs.junit)` en
`app/build.gradle.kts`. Tests JVM puros para `VideoUrlValidator`,
`VideoLaunchPayload` y normalización de dominio. `DomainTrustManager` queda
delgado (delegación + consulta al store); su lógica de decisión se verifica
indirectamente y en validación manual (quickstart).

**Rationale**: Principio IV exige `./gradlew test` sin dispositivo; JUnit4 JVM
es suficiente al diseñar la lógica crítica como funciones/clases sin
dependencias de framework Android. Robolectric se consideró y descartó: coste
de descarga/ejecución alto para lo que aporta sobre SharedPreferences, cuando
la lógica valiosa (normalización) ya es pura.

**Advertencia registrada**: `kotlinx-coroutines` llega hoy transitivo; se
declara explícito en esta feature (constitución, regla de deps).
