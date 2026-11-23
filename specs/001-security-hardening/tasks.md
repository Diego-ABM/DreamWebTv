# Tasks: Security Hardening (TLS + JS Bridge)

**Input**: Design documents from `/specs/001-security-hardening/`

**Prerequisites**: plan.md (required), spec.md (required), research.md, data-model.md, contracts/, quickstart.md

**Tests**: Incluidos — la constitución (Principio IV) exige tests MUST para la lógica crítica (validador de URLs, parsing del payload `url|REFERER|UA|COOKIES`, normalización de dominio, comportamiento del bridge JS), ejecutables con `./gradlew test` sin dispositivo.

**Organization**: Tareas agrupadas por user story (US1, US2 — ambas P1) para implementación y verificación independientes.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Puede ejecutarse en paralelo (archivos distintos, sin dependencias de tareas incompletas)
- **[Story]**: User story a la que pertenece ([US1], [US2]); Setup/Foundational/Polish no llevan label
- Todas las tareas incluyen ruta de archivo exacta

## Path Conventions

- Módulo único Android: `app/src/main/java/com/example/dreamweb/` (paquetes `security/` nuevo, `engine/`, `managers/`, `ui/`)
- Tests JVM: `app/src/test/java/com/example/dreamweb/` (árbol nuevo, JUnit4)
- Recursos: `app/src/main/res/layout/`, manifest en `app/src/main/AndroidManifest.xml`
- Build: `gradle/libs.versions.toml`, `app/build.gradle.kts`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Infraestructura de build y tests (hoy inexistente: no hay `app/src/test` ni deps de test) y deuda de manifiesto/dependencias exigida por la constitución.

- [X] T001 Declarar `junit = "4.13.2"` y `kotlinxCoroutines = "1.7.3"` en `[versions]`, y entradas `junit` (`junit:junit`) y `kotlinx-coroutines-android` (`org.jetbrains.kotlinx:kotlinx-coroutines-android`) en `[libraries]` de `gradle/libs.versions.toml` (la constitución prohíbe deps solo transitivas; coroutines se usa hoy vía `lifecycleScope`/`CoroutineScope` sin declararse)
- [X] T002 Añadir `testImplementation(libs.junit)` e `implementation(libs.kotlinx.coroutines.android)` al bloque `dependencies` de `app/build.gradle.kts`
- [X] T003 [P] Declarar `android:usesCleartextTraffic="true"` con comentario de justificación (streams IPTV/streaming http legados) en el elemento `<application>` de `app/src/main/AndroidManifest.xml` — confirmado por el usuario; la constitución exige declararlo explícitamente
- [X] T004 [P] Crear el árbol de tests JVM `app/src/test/java/com/example/dreamweb/` con subpaquetes `engine/`, `managers/` y `security/`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Extracciones de lógica crítica compartida y sus tests (Principio IV). Sin estas clases ni US1 (usa `VideoLaunchPayload`) ni US2 (usa `VideoUrlValidator`) pueden implementarse.

**⚠️ CRITICAL**: Ninguna user story puede empezar hasta completar esta fase. Escribir los tests PRIMERO y comprobar que fallan antes de la implementación.

- [X] T005 [P] Crear `VideoUrlValidatorTest` en `app/src/test/java/com/example/dreamweb/engine/VideoUrlValidatorTest.kt` (JUnit4): acepta `http(s)` con `.m3u8`, `.mpd`, `.mp4`, `.mkv`, `.webm`, `/manifest`, `playlist.m3u8`; rechaza `javascript:`, `data:`, `file:`, `blob:`, `intent:`, `vbscript:`, texto plano, cadena vacía, URL malformada, segmentos (`.ts`, `/segment`, `range=`) y ads/trackers (`adsystem`, `adserver`, `analytics`, `doubleclick`, `pixel`, `googlesyndication`, `/ads/`)
- [X] T006 [P] Crear `VideoLaunchPayloadTest` en `app/src/test/java/com/example/dreamweb/managers/VideoLaunchPayloadTest.kt` (JUnit4): parse completo de `url|REFERER|ua|COOKIES|cookies` y casos parciales (sin `|REFERER|`, sin `|UA|`, sin `|COOKIES|` → referer/cookies vacíos, UA por defecto de TV: `Mozilla/5.0 (Linux; Android 10; BRAVIA 4K VH2) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36`)
- [X] T007 Crear `object VideoUrlValidator` en `app/src/main/java/com/example/dreamweb/engine/VideoUrlValidator.kt` con `isVideoUrl(url: String): Boolean`: heurística extraída verbatim de `CustomWebClient.shouldInterceptRequest` (formatos `.m3u8`/`.mp4`/`.mpd`/`.mkv`/`.webm`/`/manifest`/`playlist.m3u8`; exclusión de segmentos `.ts`/`/segment`/`range=`; exclusión de ads/trackers listados en T005) MÁS validación de esquema: solo `http`/`https` (bloquea `javascript:`, `data:`, `file:`, `blob:`, `intent:`, `vbscript:`, texto plano y malformadas) — va tras T005 fallando (TDD)
- [X] T008 Crear `data class VideoLaunchPayload` (`url`, `referer`, `userAgent`, `cookies`) con `parse(videoData: String)` en `app/src/main/java/com/example/dreamweb/managers/VideoLaunchPayload.kt`: extrae el parsing inline de `NativeVideoActivity.setupPlayer` (líneas 38–49): split por `|COOKIES|` → `|UA|` → `|REFERER|`; defaults: referer/cookies `""`, userAgent = UA TV por defecto (ver T006) — va tras T006 fallando (TDD)

**Checkpoint**: `./gradlew test` compila y pasa T005–T008 — la lógica crítica compartida está lista.

---

## Phase 3: User Story 1 - TLS verificado con escape hatch por dominio (Priority: P1) 🎯 MVP

**Goal**: El canal de red del reproductor nativo valida TLS de verdad por defecto; ante certificado inválido se muestra diálogo que nombra el dominio con opciones Aceptar (solo este dominio, persiste y reintenta) / Rechazar (no persiste, cancela); excepciones revocables desde "Seguridad" en Home.

**Independent Test**: Stream HTTPS con certificado válido → reproduce sin diálogo. Servidor autofirmado → diálogo con dominio; Aceptar → reintento automático y reproduce, el dominio queda exceptuado entre sesiones; Rechazar → error sin reproducción y vuelve a preguntar al relanzar. Home → "Seguridad" → lista de excepciones con fecha, revocación por ítem y "Borrar todas" (ver quickstart.md Escenario 1 y contracts/tls-exceptions-ui.md).

### Tests for User Story 1

> **NOTE: Escribir el test PRIMERO, comprobar que falla antes de implementar.**

- [X] T009 [P] [US1] Crear `TlsDomainNormalizationTest` en `app/src/test/java/com/example/dreamweb/security/TlsDomainNormalizationTest.kt` (JUnit4): normalización de host a lowercase sin esquema, sin puerto y sin path; `example.com` NO cubre `sub.example.com` (match exacto, sin wildcard); cadena vacía rechazada

### Implementation for User Story 1

- [X] T010 [US1] Crear `TlsExceptionStore` en `app/src/main/java/com/example/dreamweb/security/TlsExceptionStore.kt`: backed por SharedPreferences archivo `tls_exceptions` (`Context.MODE_PRIVATE`), entrada `host → acceptedAtEpochMs` (Long). API: `isExcepted(host)`, `add(host)`, `remove(host)`, `clear()`, `list(): Map<String, Long>`; función pura testeable `normalize(host)` con las reglas de T009. Escritura únicamente desde "Aceptar" del diálogo; el rechazo nunca escribe (FR-004/FR-005)
- [X] T011 [US1] Crear `DomainTrustManager` en `app/src/main/java/com/example/dreamweb/security/DomainTrustManager.kt`: `X509ExtendedTrustManager` que envuelve el `X509TrustManager` de plataforma (`TrustManagerFactory` con keystore por defecto) y delega `checkServerTrusted`; al capturar `CertificateException` extrae el host del peer desde `SSLSocket`/`SSLEngine` (firmas extendidas invocadas por Conscrypt) y acepta solo si `TlsExceptionStore.isExcepted(host)`; si no, relanza. Debe propagar el host donde falló el handshake para que el diálogo lo consuma: registrar `var lastRejectedHost: String?` (o relanzar una excepción portadora del host) — con redirects el host que falla puede ser distinto al de la URL del stream. Incluir en el mismo archivo el `HostnameVerifier` simétrico: delega en `OkHostnameVerifier` y solo retorna `true` para host exceptuado (cubre hostname-mismatch). PROHIBIDO cualquier aceptación incondicional (FR-002)
- [X] T012 [US1] Crear `PlayerHttpClientFactory` en `app/src/main/java/com/example/dreamweb/security/PlayerHttpClientFactory.kt`: recibe `TlsExceptionStore` y un `VideoLaunchPayload`; construye el `OkHttpClient` con `sslSocketFactory` (SSLContext con `DomainTrustManager`) + hostname verifier de T011 + `followRedirects`/`followSslRedirects` + la `CookieJar` actual que inyecta `cookies` parseadas con `Cookie.parse` (portada de `NativeVideoActivity`, líneas 70–81), y devuelve el `OkHttpDataSource.Factory` con `setUserAgent` y `setDefaultRequestProperties` portando los headers actuales (`Referer` + `Origin` derivado, `Sec-Fetch-Mode: cors`, `Sec-Fetch-Site: cross-site`, `Accept: */*`, `Cookie`) — mismo comportamiento funcional que hoy menos el trust-all
- [X] T013 [US1] Añadir `val tlsExceptionStore = TlsExceptionStore(context)` en `app/src/main/java/com/example/dreamweb/AppContainer.kt`
- [X] T014 [US1] Reescribir `app/src/main/java/com/example/dreamweb/ui/NativeVideoActivity.kt`: eliminar `trustAllCerts`, `sslContext` y `hostnameVerifier { _, _ -> true }` (líneas 55–67) y sus imports (`SecureRandom`, `SSLContext`, `TrustManager`, `X509TrustManager`); eliminar los `Log.d` que emiten URL/Referer/UA (líneas 51–53) o dejarlos tras `BuildConfig.DEBUG`; usar `VideoLaunchPayload.parse(videoData)` y `PlayerHttpClientFactory`; guardar `videoData`/`url` en campo para reintento. En `onPlayerError` recorrer la cadena `PlaybackException.cause` buscando `SSLHandshakeException`, `SSLPeerUnverifiedException` o `CertPathValidatorException`; si hay causa TLS y `!isFinishing && !isDestroyed`, mostrar `AlertDialog` (AppCompat) con el host donde falló el handshake propagado desde `DomainTrustManager` (`lastRejectedHost`/excepción portadora — NO el host de la URL del stream, que puede diferir tras redirects) + tipo de problema (mapeo de la excepción causa según contracts/tls-exceptions-ui.md §2) + opciones: **Aceptar (solo este dominio)** → `tlsExceptionStore.add(host)` con ese mismo host propagado + liberar player + `setupPlayer(videoData)` reintento automático; **Rechazar** y dismiss/Back → no persistir nada, cancelar reproducción, Toast de error y `finish()` (contracts/tls-exceptions-ui.md §2, FR-003/FR-005). Errores de player NO-TLS: conservar un Toast de error genérico y eliminar el bloque fallback muerto de las líneas 147–150 (solo loguea, no ejecuta fallback real)
- [X] T015 [P] [US1] Crear `SecuritySettingsActivity` en `app/src/main/java/com/example/dreamweb/ui/SecuritySettingsActivity.kt`: patrón ViewBinding + RecyclerView + adapter inline como `HomeActivity`; lista una fila por dominio `host — aceptado el <fecha formateada>` desde `TlsExceptionStore.list()`; revocación por ítem (`remove(host)`) con foco D-pad y botón "Borrar todas las excepciones TLS" → `clear()`; recargar lista en `onResume` (contracts/tls-exceptions-ui.md §3)
- [X] T016 [P] [US1] Crear layouts `app/src/main/res/layout/activity_security_settings.xml` (título, RecyclerView de excepciones, botón "Borrar todas las excepciones TLS", navegable con D-pad, estilo coherente con `activity_home.xml`) y `app/src/main/res/layout/item_tls_exception.xml` (dominio + fecha + acción de revocar)
- [X] T017 [P] [US1] Añadir botón "Seguridad" (`@+id/securityButton`, focusable para D-pad) en `app/src/main/res/layout/activity_home.xml`
- [X] T018 [P] [US1] Registrar `SecuritySettingsActivity` (`android:exported="false"`, `android:screenOrientation="landscape"`, `android:configChanges="orientation|screenSize|keyboardHidden|keyboard"` por simetría con BrowserActivity/NativeVideoActivity) en `app/src/main/AndroidManifest.xml`
- [X] T019 [US1] En `app/src/main/java/com/example/dreamweb/ui/HomeActivity.kt`, wirear `binding.securityButton.setOnClickListener` → `startActivity(Intent(this, SecuritySettingsActivity::class.java))`

**Checkpoint**: US1 completo y verificable de forma independiente — Escenario 1 y 4 de quickstart.md; stream con certificado válido reproduce idéntico a hoy (SC-001).

---

## Phase 4: User Story 2 - Bridge JavaScript desacoplado y con entrada validada (Priority: P1)

**Goal**: El bridge `DreamWebSniffer` deja de ser un objeto anónimo que captura `BrowserActivity`; pasa a `SnifferBridge` nombrado que valida input con `VideoUrlValidator` (idéntica heurística que el sniffer de red) y se desregistra en `WebViewPool.releaseWebView()` junto a clients y el scope de corrutinas — sin retención de Activity tras reciclar el WebView.

**Independent Test**: Desde una página de prueba ejecutar `DreamWebSniffer.onVideoFound("javascript:alert(1)")` y variantes (`data:`, `file:`, `blob:`, texto plano, URL https sin patrón de video) → todas ignoradas sin actualizar `lastDetectedVideoUrl` ni mostrar el botón (SC-004). URL `https://` válida con patrón → se comporta como detección legítima. 10 ciclos abrir/cerrar BrowserActivity → 0 Activities retenidas en heap dump/`dumpsys meminfo` (SC-003, quickstart.md Escenarios 2–3).

### Tests for User Story 2

> **NOTE: Escribir el test PRIMERO, comprobar que falla antes de implementar.**

- [X] T020 [P] [US2] Crear `SnifferBridgeTest` en `app/src/test/java/com/example/dreamweb/engine/SnifferBridgeTest.kt` (JUnit4, JVM puro — `SnifferBridge` no tiene dependencias de framework Android): URLs maliciosas (`javascript:`, `data:`, `file:`, `blob:`, texto plano, URL https sin patrón de video) → callback NO invocado; URL `https://` con patrón de video válido → callback invocado; tras `release()` → callback no invocado aunque la URL sea válida

### Implementation for User Story 2

- [X] T021 [US2] Crear `SnifferBridge` en `app/src/main/java/com/example/dreamweb/engine/SnifferBridge.kt` (diseño D6 de research.md): clase nombrada con `private var onVideoFound: ((String) -> Unit)?`; `@JavascriptInterface fun onVideoFound(url: String)` que descarta silenciosamente (log en debug) si `!VideoUrlValidator.isVideoUrl(url)` y si acepta invoca el callback; `fun release()` que anula el callback. SIN referencias a `Activity`/`Context` de corta vida (contracts/js-bridge.md, FR-007, Principio V)
- [X] T022 [US2] Actualizar `app/src/main/java/com/example/dreamweb/engine/CustomWebClient.kt`: sustituir la heurística inline de `shouldInterceptRequest` (líneas 55–81: `isVideoFormat`/`isSegment`/`isAdOrTracker`) por `VideoUrlValidator.isVideoUrl(url)` manteniendo el callback `onVideoDetected("$url|REFERER|$referer")`; añadir `fun release()` que cancela el `CoroutineScope` (`scope.cancel()`) para que el pool pueda liberarlo
- [X] T023 [US2] Actualizar `releaseWebView(webView)` en `app/src/main/java/com/example/dreamweb/engine/WebViewPool.kt` (líneas 56–66): antes de reciclar, ejecutar limpieza simétrica — `webView.removeJavascriptInterface("DreamWebSniffer")`, `(webView.webViewClient as? CustomWebClient)?.release()` (API 26+, minSdk 28), `webView.webViewClient = WebViewClient()` default, `webView.webChromeClient = null`; conservar `stopLoading()`, reset de UA, `loadUrl("about:blank")`, `clearHistory()` y reset del `MutableContextWrapper` (FR-006, Principio V)
- [X] T024 [US2] Actualizar `app/src/main/java/com/example/dreamweb/ui/BrowserActivity.kt`: reemplazar el `addJavascriptInterface(object {...}, "DreamWebSniffer")` anónimo (líneas 79–86) por `addJavascriptInterface(SnifferBridge { url -> runOnUiThread { onVideoFound(url) } }, "DreamWebSniffer")` guardando la instancia en campo; en `onDestroy` invocar `snifferBridge.release()` ANTES de `webViewPool.releaseWebView(it)` (líneas 181–189) para anular el callback antes de remover la interfaz — `release()` no queda como código muerto (contracts/js-bridge.md); la validación de entrada queda delegada al bridge (los guards existentes de `onVideoFound` — `blob:` y prioridad `.m3u8` sobre `.mp4` — se conservan como defensa en profundidad)

**Checkpoint**: US2 completo y verificable de forma independiente — Escenarios 2 y 3 de quickstart.md; matriz de entrada maliciosa del contrato js-bridge.md rechazada en su totalidad.

---

## Phase 5: Polish & Cross-Cutting Concerns

**Purpose**: Verificación integral de quality gates y criterios medibles del spec.

- [X] T025 Ejecutar `./gradlew test` desde la raíz del repo: deben pasar `VideoUrlValidatorTest`, `VideoLaunchPayloadTest`, `TlsDomainNormalizationTest` y `SnifferBridgeTest` sin dispositivo (Principio IV)
- [X] T026 Ejecutar `./gradlew assembleDebug` desde la raíz del repo: el build debe compilar limpio (quality gate de la constitución)
- [X] T027 [P] Verificación estática SC-002: `grep -rn "checkServerTrusted\|hostnameVerifier\|trustAll" app/src/main/java/` debe mostrar SOLO `security/DomainTrustManager.kt` y `security/PlayerHttpClientFactory.kt`, sin aceptación incondicional
- [ ] T028 [P] Validación manual end-to-end de quickstart.md en emulador/dispositivo Android TV: Escenario 1 (diálogo TLS, reintento, persistencia, revocación), Escenario 2 (bridge rechaza inputs maliciosos), Escenario 3 (10 ciclos sin retención: `adb shell dumpsys meminfo com.example.dreamweb | grep Activities` o heap dump), Escenario 4 (edge cases: rotación con diálogo, Back con diálogo pendiente, redirect a host con cert inválido, servidor LAN autofirmado estable)
- [X] T029 Actualizar `README.md` para reflejar el estado real post-feature (TLS verificado + excepciones por dominio, bridge JS validado, infra de tests `./gradlew test`) — la constitución exige que el README refleje completado/experimental/pendiente tras cada cambio

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: Sin dependencias — puede empezar inmediatamente.
- **Foundational (Phase 2)**: Depende de T001–T004 (necesita `junit` declarado para compilar los tests). **BLOQUEA** ambas user stories: US1 consume `VideoLaunchPayload`; US2 consume `VideoUrlValidator`.
- **User Stories (Phase 3–4)**: Ambas dependen solo de Foundational. US1 y US2 son independientes entre sí (archivos disjuntos salvo ninguno compartido) → pueden ejecutarse en paralelo o secuencialmente; ambas son P1, orden sugerido US1 → US2 por severidad de la vulnerabilidad.
- **Polish (Phase 5)**: Depende de US1 + US2 completas.

### User Story Dependencies

- **US1 (P1)**: Tras Foundational. Sin dependencia de US2.
- **US2 (P1)**: Tras Foundational. Sin dependencia de US1 (ambos usan `VideoUrlValidator` pero US1 no lo consume).

### Within Each User Story

- Test (T009) antes de implementación en US1 — debe fallar primero.
- Test (T020) antes de implementación en US2 — debe fallar primero.
- `TlsExceptionStore` (T010) antes que `DomainTrustManager` (T011) → `PlayerHttpClientFactory` (T012) → `AppContainer` (T013) → `NativeVideoActivity` (T014).
- UI de gestión (T015–T019) depende conceptualmente de T010 pero sus archivos son disjuntos → paralelizables entre sí.
- En US2: `SnifferBridge` (T021) y `CustomWebClient` (T022) antes de `WebViewPool` (T023, llama a `CustomWebClient.release()`) y `BrowserActivity` (T024, instancia `SnifferBridge`).

### Parallel Opportunities

- T003 y T004 (Setup) en paralelo tras T001–T002.
- T005–T008 (Foundational): los dos tests en paralelo primero (deben fallar); las dos implementaciones después, paralelizables entre sí (TDD).
- US1: T015, T016, T017, T018 en paralelo (archivos distintos); T019 es el wiring final.
- US1 y US2 enteras en paralelo si hay capacidad (archivos disjuntos).
- T027 y T028 (Polish) en paralelo tras T025–T026.

---

## Parallel Example: User Story 1

```bash
# Lanzar en paralelo los artefactos de UI de gestión (archivos disjuntos):
Task: "Crear SecuritySettingsActivity en app/src/main/java/com/example/dreamweb/ui/SecuritySettingsActivity.kt"
Task: "Crear layouts activity_security_settings.xml e item_tls_exception.xml en app/src/main/res/layout/"
Task: "Añadir botón Seguridad en app/src/main/res/layout/activity_home.xml"
Task: "Registrar SecuritySettingsActivity en app/src/main/AndroidManifest.xml"
```

## Parallel Example: User Story 2

```bash
# Secuencia estricta: T021 → T022/T023 → T024 (T023 necesita CustomWebClient.release() de T022;
# T024 necesita SnifferBridge de T021). Sin paralelismo real dentro de la story:
# la extracción del validador (T007) ya quedó hecha en Foundational.
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Completar Phase 1: Setup (deps junit + coroutines, cleartext declarado, árbol de tests)
2. Completar Phase 2: Foundational (`VideoUrlValidator` + `VideoLaunchPayload` testeados)
3. Completar Phase 3: User Story 1 (TLS real + diálogo + store + UI de revocación)
4. **STOP and VALIDATE**: Escenario 1 de quickstart.md + `./gradlew test` — es la vulnerabilidad más grave (robo de cookies/cabeceras vía MitM)
5. Deploy/demo si procede

### Incremental Delivery

1. Setup + Foundational → base con tests verdes
2. + US1 → validar independiente → demo (MVP: TLS verificado con escape hatch)
3. + US2 → validar independiente → demo (bridge endurecido, leak eliminado)
4. Polish → quality gates + verificación estática SC-002 + README actualizado

### Parallel Team Strategy

1. Un solo dev completa Setup + Foundational (pequeño, ~8 tareas)
2. Tras el checkpoint: Dev A → US1 (TLS), Dev B → US2 (bridge) — archivos disjuntos
3. Integración en Polish: quality gates compartidos

---

## Notes

- [P] = archivos distintos, sin dependencias de tareas incompletas
- [US1]/[US2] = trazabilidad a las historias de spec.md (ambas P1)
- Escribir tests antes de implementación donde están marcados (T005–T006, T009, T020) y verificar que fallan
- `usesCleartextTraffic="true"` es decisión confirmada (streams IPTV http legados) — NO cambiar a false
- NO crear WebViews fuera del pool; el bridge nuevo no debe retener Activity/Context
- Tras `releaseWebView`, llamadas JS a `DreamWebSniffer` deben ser no-op (interfaz removida)
- Excepciones TLS: dominio exacto solamente — sin wildcard ni subdominios en esta iteración
- Commit sugerido tras cada tarea o grupo lógico (los hooks de git-commit son opcionales y manuales)
- Fuera de alcance: limpieza de Glide/Leanback sin uso (deuda registrada aparte), parser real de EasyList del AdBlock; tests de AdBlock/BloomFilter/managers SQLite quedan como deuda registrada, fuera de alcance de esta feature
