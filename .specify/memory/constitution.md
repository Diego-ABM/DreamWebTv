# DreamWeb Constitution

## Core Principles

### I. Eficiencia de Recursos Primero

DreamWeb existe para hardware de TV limitado (~2GB RAM). Toda decisión de diseño
MUST priorizar el consumo mínimo de memoria, CPU y tamaño de binario sobre la
comodidad de desarrollo. Reglas non-negotiable:

- Una sola instancia de WebView gestionada por `WebViewPool`; está PROHIBIDO
  crear WebViews ad-hoc fuera del pool.
- El offloading a componentes nativos (ExoPlayer) MUST congelar el WebView
  (`onPause()` + `pauseTimers()`) antes de ceder el foco, y restaurarlo al volver.
- Dependencias nuevas MUST justificar su peso contra el presupuesto de APK;
  las declaradas sin uso MUST eliminarse.
- Trabajo en background permanente (monitores, loops, intervalos JS) MUST tener
  justificación explícita y condición de apagado; el logging continuo en
  producción está PROHIBIDO salvo flag de depuración.

*Rationale:* los presupuestos del README (APK <12MB, ≤60MB en Home, ≤180MB en
video) son la propuesta de valor del producto; cada byte y cada hilo compiten
contra ellos.

### II. Seguridad y Privacidad Verificable

La validación TLS MUST estar activa por defecto en toda conexión de red
(WebView, OkHttp, ExoPlayer). Reglas non-negotiable:

- PROHIBIDO un `TrustManager`/hostname verifier que acepte todo certificado de
  forma global.
- Excepciones TLS permitidas ÚNICAMENTE por dominio y con consentimiento
  explícito del usuario en tiempo de ejecución (diálogo de advertencia);
  deben registrarse y ser revocables.
- `JavascriptInterface` expone superficie de ataque: cada método expuesto MUST
  validar su entrada y tratarse como frontera no confiable.
- Las mitigaciones de privacidad (UA, geolocalización, acceso a archivos,
  scripts anti-fingerprinting) MUST existir y funcionar; no basta declararlas.

*Rationale:* la app navega sitios arbitrarios e inyecta sus cookies/cabeceras
en el reproductor; un bypass TLS global convierte cualquier WiFi hostil en
vector de robo de sesión.

### III. Features Verificables, No Decorativas

Una feature solo se considera implementada cuando funciona de extremo a
extremo con datos reales. Reglas non-negotiable:

- PROHIBIDO presentar stubs, assets dummy o cuerpos vacíos como funcionales;
  deben marcarse explícitamente como experimental/stub en código y README.
- Las cifras del README (efectividad de bloqueo, presupuestos) MUST ser
  medibles y reproducibles; si no se pueden medir, se eliminan del documento.
- La extracción de video y el AdBlock se mantienen clasificados como
  experimentales hasta que exista verificación automatizada o manual
  documentada del flujo completo.

*Rationale:* el análisis reveló AdBlock operando con 3 dominios de ejemplo y
`injectDarkMode()` vacío mientras el README prometía paridad con Brave; la
constitución cierra esa brecha entre documento y realidad.

### IV. Tests en Lógica Crítica

Tests automatizados MUST existir para la lógica determinista de alto riesgo;
la UI/WebView queda exenta por coste de instrumentación. Reglas:

- MUST testearse: matching y parsing de filtros (AdBlock/BloomFilter),
  detección de URLs de video (patrones del sniffer), parsing del payload
  `url|REFERER|UA|COOKIES`, y managers de datos (SQLite).
- Los tests MUST poder ejecutarse con `./gradlew test` sin dispositivo.
- Tests de UI/instrumentación son SHOULD, no MUST, mientras el proyecto siga
  en fase prototipo.

*Rationale:* no existe infraestructura de tests hoy; exigir TDD total sería
impostado, pero la lógica crítica sin tests fue la fuente de los bugs de
parsing y falsos negativos del análisis.

### V. Disciplina de Ciclo de Vida y Ownership

Todo recurso adquirido MUST tener un punto de liberación definido y simétrico.
Reglas non-negotiable:

- Referencias a `Activity`/`Context` de corta vida PROHIBIDAS en objetos de
  larga vida (pool, singletons, bridges JS): usar `WeakReference` o
  desregistrar en `releaseWebView`.
- Al reciclar el WebView MUST limpiarse: `JavascriptInterface`, clients
  previos, listeners y estado de la sesión que no deba persistir.
- Activities MUST reenviar `onPause`/`onResume`/`onDestroy` al WebView y al
  player; `pauseTimers()` global solo dentro del patrón de congelamiento.
- Callbacks y scopes de corrutinas MUST cancelarse o acotarse al lifecycle
  correspondiente (`lifecycleScope`, no `GlobalScope`).

*Rationale:* el pool que recicla WebViews es la optimización central del
proyecto y, simultáneamente, la fuente del leak detectado: sin ownership
explícito la optimización se convierte en deuda.

## Restricciones Técnicas y Presupuestos

- Stack fijado: Kotlin sobre AGP 9.x (built-in Kotlin), WebView + androidx.webkit,
  Media3 ExoPlayer (HLS/DASH), OkHttp, SQLite directo. No introducir frameworks
  de DI, Room ni Compose sin enmienda a esta constitución.
- Presupuestos verificables (del README): APK release < 12MB; RAM en Home
  ≤ 60MB; RAM con video nativo ≤ 180MB. Toda feature MUST declarar su impacto
  estimado si supera ±10% de cualquier presupuesto.
- Toda dependencia MUST declararse explícitamente en `libs.versions.toml`;
  PROHIBIDO depender de artefactos que lleguen solo por vía transitiva.
- Dependencias declaradas sin uso en código MUST eliminarse en la misma
  revisión que lo detecte.
- `minSdk = 28`; targets Android TV/leanback; `usesCleartextTraffic` debe
  declararse explícitamente con su justificación (streaming legado http).

## Flujo de Desarrollo y Quality Gates

- Todo cambio MUST compilar con `./gradlew assembleDebug` antes de integrarse.
- Checklist de seguridad por cambio: sin trust-all, sin APIs de log verboso en
  release, sin referencias Activity en objetos persistentes, sin
  `JavascriptInterface` nuevo sin revisión de entrada.
- Cambios en AdBlock/sniffer/parsing MUST venir con su test de lógica crítica
  o una justificación explícita de por qué no es testeable.
- El README MUST reflejar el estado real por sección (completado /
  experimental / pendiente) tras cada cambio de feature.
- Complejidad nueva (nuevo componente, nuevo thread, nuevo asset pesado)
  MUST justificarse en el commit o PR frente a los Principios I y V.

## Governance

Esta constitución suple cualquier otra práctica o convención del proyecto.
Las enmiendas requieren: (1) documentación del cambio y su motivo,
(2) actualización del Sync Impact Report, y (3) plan de migración si la
enmienda invalida código existente. Versionado semántico del documento:
MAJOR para eliminación o redefinición incompatible de principios, MINOR para
principios o secciones nuevas, PATCH para aclaraciones de redacción.

Toda revisión de código MUST verificar cumplimiento de los principios; la
complejidad no justificada es motivo de rechazo. Los incumplimientos
detectados (ver gaps en el Sync Impact Report) se tratan como deuda técnica
registrada, no como precedente permitido. `README.md` actúa como documento de
visión y estado; esta constitución gobierna en caso de conflicto.

**Version**: 1.0.0 | **Ratified**: 2026-11-22 | **Last Amended**: 2026-11-22
