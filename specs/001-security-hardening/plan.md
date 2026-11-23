# Implementation Plan: Security Hardening (TLS + JS Bridge)

**Branch**: `001-security-hardening` | **Date**: 2026-11-22 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/001-security-hardening/spec.md`

## Summary

Eliminar las dos violaciones de seguridad registradas como deuda en la constitución
v1.0.0 (Principios II y V):

1. **TLS trust-all en el reproductor nativo** (`NativeVideoActivity`, líneas
   55–82): sustituir el `X509TrustManager` que acepta todo y el
   `hostnameVerifier { true }` por validación TLS real. Las excepciones se
   permiten únicamente por dominio, tras consentimiento explícito del usuario en
   un diálogo, se persisten en SharedPreferences, son revocables desde una
   sección "Seguridad" en Home, y aceptar dispara un reintento automático del
   stream.
2. **JavascriptInterface inseguro** (`BrowserActivity`, líneas 79–86): el bridge
   anónimo `DreamWebSniffer` captura la Activity y no valida su entrada. Se
   reemplaza por una clase bridge nombrada que valida con un
   `VideoUrlValidator` compartido (misma heurística que el sniffer de red en
   `CustomWebClient`), se desregistra en `WebViewPool.releaseWebView()` y no
   retiene la Activity.

## Technical Context

**Language/Version**: Kotlin (AGP 9.2.1 con Kotlin integrado), Java 11
(`sourceCompatibility`/`targetCompatibility` = `VERSION_11` en
`app/build.gradle.kts`)

**Primary Dependencies**: WebView + androidx.webkit 1.12.1, Media3 ExoPlayer
1.2.0 (`media3-exoplayer`, `-hls`, `-dash`, `-ui`, `-datasource-okhttp`),
OkHttp 4.12.0, AppCompat 1.6.1, RecyclerView 1.3.2 — todas ya declaradas en
`gradle/libs.versions.toml`

**Storage**: SharedPreferences para excepciones TLS (decisión clarificada);
SQLite existente (history/bookmarks) no se usa en esta feature

**Testing**: JUnit4 sobre JVM — infraestructura de tests inexistente hoy (no hay
`app/src/test` ni deps de test); requiere declarar `junit` en
`libs.versions.toml` + `testImplementation` en `app/build.gradle.kts`. Tests
ejecutables con `./gradlew test` sin dispositivo (Principio IV)

**Target Platform**: Android TV / leanback, minSdk 28, targetSdk 36

**Project Type**: Android app, módulo único `:app`, paquete
`com.example.dreamweb`

**Performance Goals**: Sin metas nuevas; respetar presupuestos README (APK
<12MB, ≤60MB RAM en Home, ≤180MB con video). La feature no añade deps de
runtime ni trabajo en background

**Constraints**:
- PROHIBIDO trust-all global o hostname verifier permisivo (Principio II,
  FR-002).
- Excepciones TLS: por dominio exacto (sin wildcard ni subdominios), con
  consentimiento en runtime, persistidas y revocables.
- UI navegable con D-pad (Android TV); `AppCompatActivity` + ViewBinding,
  sin Compose ni Leanback BrowseFragment.
- Dependencias nuevas (junit, test) MUST declararse en `libs.versions.toml`.
- `NativeVideoActivity` y `BrowserActivity` ya tienen
  `configChanges="orientation|screenSize|keyboardHidden|keyboard"` en el
  manifest → el diálogo TLS sobrevive rotaciones sin recrearse.

**Scale/Scope**: ~19 archivos Kotlin, 3 Activities. La feature toca
`NativeVideoActivity`, `BrowserActivity`, `CustomWebClient`, `WebViewPool`,
`HomeActivity`, `AppContainer`, `build.gradle.kts`, `libs.versions.toml`,
`AndroidManifest.xml` y añade ~7 archivos nuevos + tests.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principio | Evaluación | Resultado |
|-----------|------------|-----------|
| I. Eficiencia de Recursos | Sin deps runtime nuevas (solo `junit` test-scope); sin threads ni monitores nuevos; el WebView único del pool se mantiene. El store SharedPreferences es lectura síncrona por handshake, coste despreciable. | ✅ PASS |
| II. Seguridad y Privacidad Verificable | La feature implementa exactamente el patrón permitido: validación TLS real por defecto + excepción por dominio con consentimiento explícito, registrada y revocable. El bridge JS pasa a validar entrada (frontera no confiable). | ✅ PASS |
| III. Features Verificables | TLS real de verdad (delega al TrustManager de plataforma); no hay stubs. La heurística extraída es la que ya opera en producción. | ✅ PASS |
| IV. Tests en Lógica Crítica | Exige tests para "detección de URLs de video" y "parsing del payload url\|REFERER\|UA\|COOKIES" — ambos se extraen a clases testeables y se añade infra JUnit. Gate condicionado a declarar `junit` en `libs.versions.toml`. | ✅ PASS (con acción requerida) |
| V. Ciclo de Vida y Ownership | `releaseWebView()` pasa a remover el `JavascriptInterface`, resetear clients y cancelar el scope del `CustomWebClient`; el bridge nuevo no retiene Activity. Corrige el leak registrado como deuda. | ✅ PASS |

**Deuda adyacente detectada (no bloquea, registrada como advertencia)**:
- `kotlinx-coroutines` se usa (`lifecycleScope`, `CoroutineScope`) sin declararse
  en `libs.versions.toml` — llega transitivo. La constitución exige declaración
  explícita; se corrige en esta feature como cambio de una línea ya que se
  toca el build file.
- `usesCleartextTraffic` no está declarado en el manifest; la constitución
  exige declararlo explícitamente con justificación (streams http legados).
  Se añade `android:usesCleartextTraffic="true"` + comentario de justificación.
- Glide y Leanback declarados sin uso → fuera de alcance (limpieza aparte).

**Resultado del gate: PASS** — sin violaciones que requieran justificación en
Complexity Tracking. Re-evaluado tras Phase 1: sin cambios.

## Project Structure

### Documentation (this feature)

```text
specs/001-security-hardening/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output
│   ├── js-bridge.md
│   └── tls-exceptions-ui.md
└── tasks.md             # Phase 2 output (/speckit-tasks - NOT created here)
```

### Source Code (repository root)

```text
app/
├── build.gradle.kts                          # + testImplementation(junit), + coroutines explícita
├── src/main/
│   ├── AndroidManifest.xml                   # + SecuritySettingsActivity, + usesCleartextTraffic explícito
│   ├── java/com/example/dreamweb/
│   │   ├── AppContainer.kt                   # + tlsExceptionStore
│   │   ├── security/                         # PAQUETE NUEVO
│   │   │   ├── TlsExceptionStore.kt          # SharedPreferences: host → epochMillis
│   │   │   ├── DomainTrustManager.kt         # X509ExtendedTrustManager delegando en plataforma + store
│   │   │   └── PlayerHttpClientFactory.kt    # Construye OkHttpClient seguro p/ ExoPlayer
│   │   ├── engine/
│   │   │   ├── VideoUrlValidator.kt          # NUEVO: heurística compartida (extraída de CustomWebClient)
│   │   │   ├── SnifferBridge.kt              # NUEVO: @JavascriptInterface nombrado, sin ref a Activity
│   │   │   ├── CustomWebClient.kt            # Usa VideoUrlValidator; + release() que cancela scope
│   │   │   └── WebViewPool.kt                # releaseWebView(): removeJavascriptInterface + reset clients
│   │   ├── managers/
│   │   │   └── VideoLaunchPayload.kt         # NUEVO: parse de "url|REFERER|UA|COOKIES" (extraído)
│   │   └── ui/
│   │       ├── NativeVideoActivity.kt        # Sin trust-all; diálogo TLS + reintento
│   │       ├── BrowserActivity.kt            # Usa SnifferBridge + VideoUrlValidator
│   │       ├── HomeActivity.kt               # Entrada "Seguridad"
│   │       └── SecuritySettingsActivity.kt   # NUEVO: lista + revocar excepciones
│   └── res/layout/
│       ├── activity_home.xml                 # + botón "Seguridad"
│       ├── activity_security_settings.xml    # NUEVO
│       └── item_tls_exception.xml            # NUEVO
└── src/test/java/com/example/dreamweb/       # ÁRBOL NUEVO (JUnit4, JVM)
    ├── engine/VideoUrlValidatorTest.kt
    ├── managers/VideoLaunchPayloadTest.kt
    └── security/TlsDomainNormalizationTest.kt
```

**Structure Decision**: se reusa el layout de módulo único existente. El código
TLS nuevo va a un paquete `security/` para agrupar la frontera de confianza;
la heurística de video y el bridge viven en `engine/` junto al sniffer que los
usa; el parser del payload va a `managers/` junto a `VideoPlayerManager`. La UI
de gestión es una Activity más en `ui/` coherente con el patrón
ViewBinding+RecyclerView de `HomeActivity`.

## Complexity Tracking

> Sin violaciones de constitución que justificar — tabla vacía intencionadamente.
