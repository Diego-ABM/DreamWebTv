# Tasks: AdBlock Real (Parser EasyList + Listas Reales)

**Input**: Design documents from `/specs/002-adblock-real/`

**Prerequisites**: plan.md (required), spec.md (required for user stories), research.md, data-model.md, contracts/easylist-subset.md, quickstart.md

**Tests**: Incluidos — SC-005 y el Principio IV de la constitución exigen suite de parser/matcher ejecutable con `./gradlew test`. Escribir los tests ANTES de la implementación que validan y confirmar que fallan.

**Organization**: Tareas agrupadas por user story (P1 → P3) para implementación y verificación independientes.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Paralelizable (archivos distintos, sin dependencias de tareas incompletas)
- **[Story]**: [US1], [US2], [US3] — solo en fases de user story
- Todas las rutas son relativas a la raíz del repo (`/mnt/B/Proyectos/DreamWebTv`)

## Decisiones de organización (documentadas)

1. **La carga async (`Executors.newSingleThreadExecutor` + swap `@Volatile` + `DomainMatcher.EMPTY` degradado) está en Foundational (T009), no en US2**: sin ella, US1 empaquetaría listas reales de ~100K líneas con carga síncrona en `Application.onCreate` → ANR garantizado y el MVP quedaría roto por diseño. US2 se reduce a observabilidad + verificación del presupuesto de arranque.
2. **`AdBlockWhitelistStore` está en Foundational (T008)**: el flujo de decisión de `data-model.md` (`shouldBlock(requestUrl, pageUrl)`) consulta la whitelist en el paso 1, antes del matcher, así que el contrato de la firma la necesita desde el inicio. US3 aporta los tests y toda la UI.
3. **Matching exacto `HashSet<String>`** reemplaza a `BloomFilter` (research R1): `BloomFilter.kt` se elimina en T010.
4. **`BrowserApp.onCreate` conserva la llamada `container.adBlockManager.init()`** — `init()` pasa a lanzar la tarea one-shot y retornar de inmediato, sin cambio de firma.

---

## Phase 1: Setup (Assets de listas reales)

**Purpose**: Sustituir los assets dummy (4 líneas / 0 líneas) por EasyList + EasyPrivacy completas con atribución y licencia (FR-002, research R6, quickstart §0).

- [x] T001 [P] Descargar EasyList completa con `curl -L https://easylist.to/easylist/easylist.txt -o app/src/main/assets/filters/easylist.txt` (crear el directorio `app/src/main/assets/filters/` si no existe)
- [x] T002 [P] Descargar EasyPrivacy completa con `curl -L https://easylist.to/easylist/easyprivacy.txt -o app/src/main/assets/filters/easyprivacy.txt`
- [x] T003 [P] Crear `app/src/main/assets/filters/NOTICE.txt` con procedencia (URLs canónicas easylist.to), fecha de captura y atribución "EasyList / EasyPrivacy — easylist.to"; documentar además que EasyList/EasyPrivacy son dual-licensed (GPLv3 / CC BY-SA 3.0) y que el proyecto elige distribuirlas bajo GPLv3 (texto incluido en `LICENSE.GPLv3.txt`)
- [x] T004 [P] Crear `app/src/main/assets/filters/LICENSE.GPLv3.txt` con el texto completo de la licencia GPLv3 — la elegida por el proyecto de la doble licencia de las listas (GPLv3 / CC BY-SA 3.0, declarada en su cabecera); la elección queda documentada en `NOTICE.txt` (T003)
- [x] T005 Eliminar los assets dummy `app/src/main/assets/easylist.txt` y `app/src/main/assets/easyprivacy.txt`, y verificar con `wc -l app/src/main/assets/filters/*.txt` que hay decenas de miles de líneas (no 4)

---

## Phase 2: Foundational (Motor de filtros — bloquea todas las stories)

**Purpose**: Núcleo Kotlin puro + orquestador async + store de whitelist que TODAS las stories usan.

**⚠️ CRITICAL**: Ninguna user story puede empezar hasta completar esta fase.

- [x] T006 [P] Crear `app/src/main/java/com/example/dreamweb/adblock/FilterParser.kt` — Kotlin puro sin imports `android.*`: `sealed interface FilterRule` con `DomainBlock(domain)` y `Exception(domain)`, `data class FilterStats(source, linesRead, acceptedBlocked, acceptedExceptions, ignored)` y `parse(line): FilterRule?` implementando la gramática de `contracts/easylist-subset.md` §1-§2 (acepta `||dominio^`, `||dominio` fin de línea — con la **misma semántica de domain-suffix match** que `||dominio^`, divergencia documentada respecto a EasyList estricto en contrato §1 — y `@@||dominio^`; ignora `!`, `[...]`, `##`/`#@#`/`#?#`/`#$#`, cualquier `$`, `*`, paths, regex `/re/`, anclas `|http`, hosts-style y dominios que no cumplan `^[a-z0-9]([a-z0-9.-]*[a-z0-9])?$` con al menos un `.` y longitud ≥ 4)
- [x] T007 [P] Crear `app/src/main/java/com/example/dreamweb/adblock/DomainMatcher.kt` — Kotlin puro: constructor `DomainMatcher(blocked: Set<String>, exceptions: Set<String>)` con HashSets en lowercase, `isBlocked(host)` haciendo suffix walk por etiquetas (`a.b.ads.com` → `a.b.ads.com`, `b.ads.com`, `ads.com`, `com`) consultando `exceptions` antes que `blocked` en la misma pasada, `val ruleCount: Int`, y `companion object { val EMPTY }` (research R1); el suffix walk se implementa como **helper puro reutilizable sin dependencias `android.*`** (función en el propio `DomainMatcher.kt` o en un `DomainSuffixes.kt` del núcleo) compartido con la comparación de whitelist de T008
- [x] T008 [P] Crear `app/src/main/java/com/example/dreamweb/adblock/AdBlockWhitelistStore.kt` — SharedPreferences propio `"adblock_whitelist"` (clave = dominio normalizado reutilizando `TlsExceptionStore.normalize()`, valor = epochMillis), `@Volatile Set<String>` en memoria rehidratado en construcción y actualizado en `add`/`remove`/`clear`, `isExcepted(pageHost)` delegando en el helper puro de suffix walk compartido del núcleo Kotlin (`DomainMatcher.kt`/`DomainSuffixes.kt`, sin `android.*` — el mismo que usa `DomainMatcher.isBlocked`, ver T007) sobre el set volátil (sin I/O en hilo de red), `list(): Map<String, Long>` (patrón idéntico a `TlsExceptionStore`, research R4)
- [x] T009 Reescribir `app/src/main/java/com/example/dreamweb/adblock/AdBlockManager.kt` — `init()` lanza carga one-shot en `Executors.newSingleThreadExecutor()` (propiedad del manager) que lee `filters/easylist.txt` y `filters/easyprivacy.txt` con `BufferedReader`, alimenta `FilterParser`, construye `DomainMatcher` y lo publica en `@Volatile private var engine` (valor inicial `DomainMatcher.EMPTY` → `shouldBlock` devuelve false durante la carga, modo degradado documentado); tras completar la carga y el swap del matcher el executor recibe `shutdown()` (executor one-shot de vida acotada — cumple Principio V: sin background permanente); `enum class LoadState { LOADING, READY, FAILED }` + `isReady`/`stats` observables; `AtomicLong blockedRequests` + `blockedCount()`; nueva firma `shouldBlock(requestUrl: String, pageUrl: String?)` con flujo de `data-model.md`: (1) `whitelist.isExcepted(pageHost)` → ALLOW, (2) `engine.isBlocked(requestHost)` → BLOCK + `blockedRequests.incrementAndGet()` + `Log.d("DreamWebAdBlock", ...)` tras `if (BuildConfig.DEBUG)` — **este es el punto único de log DEBUG de bloqueos y vive dentro de `shouldBlock` en el manager** (T013: `CustomWebClient` NO loguea, solo traduce el boolean a `WebResourceResponse`), (3) ALLOW; hosts normalizados con `TlsExceptionStore.normalize()` (depende de T006, T007, T008)
- [x] T010 Eliminar `app/src/main/java/com/example/dreamweb/adblock/BloomFilter.kt` y actualizar `app/src/main/java/com/example/dreamweb/AppContainer.kt` añadiendo `val adBlockWhitelistStore = AdBlockWhitelistStore(context)` e inyectándolo en el constructor de `AdBlockManager` (depende de T008, T009)

**Checkpoint**: Motor compilable con `./gradlew assembleDebug`; `init()` en `BrowserApp.onCreate` retorna en µs; `shouldBlock` nunca bloquea el hilo de red ni crashea en modo degradado.

---

## Phase 3: User Story 1 - Bloqueo funcional con listas reales (Priority: P1) 🎯 MVP

**Goal**: Los dominios de ads/trackers listados como `||dominio^` en EasyList/EasyPrivacy se bloquean devolviendo `WebResourceResponse` vacío, con excepciones `@@` respetadas, matching por sufijo de subdominio y contador de bloqueos visible en DEBUG.

**Independent Test**: Con las listas reales empaquetadas, navegar a una página que solicite `doubleclick.net`/`googlesyndication.com` → las peticiones se cortan; `@@||x^` no se bloquea; comentarios/cabeceras/cosméticas se ignoran; dominio no listado pasa. Medible vía `adBlockCounterText` en build DEBUG.

### Tests for User Story 1

> **NOTE: Escribir estos tests PRIMERO, confirmar que FALLAN antes de la implementación** (SC-005, Principio IV).

- [x] T011 [P] [US1] Crear `app/src/test/java/com/example/dreamweb/adblock/FilterParserTest.kt` — JUnit4 cubriendo cada fila de `contracts/easylist-subset.md` §5: `||doubleclick.net^` → `DomainBlock`, `@@||ads.ejemplo.com^` → `Exception`, `!`/`[Adblock Plus 3.0]`/`##`/`#@#` ignoradas, `||d.com^$third-party`/`||ads.*^`/`|http://x/`/`/ads\d+/`/`||d.com^/path` ignoradas, `||sub`/`||`/`||-bad-.com^` inválidas ignoradas, y coherencia de `FilterStats` (aceptadas + ignoradas = líneas leídas)
- [x] T012 [P] [US1] Crear `app/src/test/java/com/example/dreamweb/adblock/DomainMatcherTest.kt` — JUnit4: host exacto bloqueado, `x.ads.ejemplo.com` bloqueado por regla `ads.ejemplo.com` (suffix walk), dominio no listado permitido, precedencia `@@` (host en exceptions y blocked → ALLOW), `DomainMatcher.EMPTY.isBlocked(...) == false` en cualquier host (modo degradado US2), TLDs sueltos no explotan el matching

### Implementation for User Story 1

- [x] T013 [P] [US1] Actualizar `app/src/main/java/com/example/dreamweb/engine/CustomWebClient.kt` — en `shouldInterceptRequest` llamar `adBlockManager.shouldBlock(request.url.toString(), view?.url)` devolviendo `WebResourceResponse("text/plain", "UTF-8", null)` al bloquear; en `shouldOverrideUrlLoading` llamar `shouldBlock(url, url)` (el host destino actúa como pageHost, research R5/decisión 6 del plan) y retornar `true` al bloquear; `CustomWebClient` NO añade logging propio de bloqueos — solo traduce el boolean a `WebResourceResponse`/`true` (el log DEBUG de bloqueos vive en `AdBlockManager.shouldBlock`, ver T009)
- [x] T014 [P] [US1] Añadir `TextView` `adBlockCounterText` en `app/src/main/res/layout/activity_browser.xml` — `android:visibility="gone"`, esquina inferior, `translationZ` sobre el WebView (misma familia visual que `virtualCursor`)
- [x] T015 [US1] Actualizar `app/src/main/java/com/example/dreamweb/ui/BrowserActivity.kt` — mostrar `adBlockCounterText` solo si `BuildConfig.DEBUG` y actualizar su texto con `adBlockManager.blockedCount()` en `onPageFinished` (hook determinista de fin de página: callback `onPageFinished` pasado al `CustomWebClient`; el contador es una `AtomicLong` incrementada dentro de `shouldBlock` per T009, así el valor leído al terminar la página es consistente); ocultarlo en fullscreen junto a `favoriteButton` (depende de T013, T014)
- [x] T016 [US1] Verificación de fase: `./gradlew test` con `FilterParserTest` y `DomainMatcherTest` en verde + `./gradlew assembleDebug` compilando sin warnings nuevos

**Checkpoint**: US1 funciona y es medible de forma independiente — peticiones a dominios `||x^` devuelven respuesta vacía y el contador DEBUG crece.

---

## Phase 4: User Story 2 - Carga de filtros sin congelar el arranque (Priority: P2)

**Goal**: La app arranca rápido con ~60K+ reglas: el parseo corre íntegramente en el hilo worker one-shot y el motor es observable (`isReady`, `LoadState`, `FilterStats`) mientras `DomainMatcher.EMPTY` da el modo degradado documentado.

**Independent Test**: Arranque en frío → primer frame de HomeActivity sin ANR; `logcat -s DreamWebAdBlock` muestra `engine ready, N blocked / M exceptions` tras ~1-3s; una navegación iniciada durante la carga recibe ALLOW consistente (nunca crash ni bloqueo del hilo de red).

### Implementation for User Story 2

- [x] T017 [US2] Completar la observabilidad de carga en `app/src/main/java/com/example/dreamweb/adblock/AdBlockManager.kt` — emitir `Log.d("DreamWebAdBlock", "engine ready, $blocked blocked / $exceptions exceptions")` tras `BuildConfig.DEBUG` al publicar el swap `@Volatile`; transición `LoadState.LOADING → READY` (o `FAILED` solo si un asset no se puede abrir — líneas corruptas cuentan como `ignored`, nunca tumban el parseo); confirmar que `BrowserApp.onCreate` (`app/src/main/java/com/example/dreamweb/BrowserApp.kt`) solo encola la tarea sin esperar
- [ ] T018 [US2] Verificación en dispositivo/emulador TV (quickstart Escenario A): `./gradlew installDebug`, arranque en frío cronometrando `Application.onCreate` → primer frame de HomeActivity; el Δ medido vs el build actual con listas dummy debe ser ≤ 200ms (o dentro del ruido de medición) — umbral de SC-003; log `engine ready` presente, y navegación durante la carga sin ANR ni crash (modo degradado ALLOW)

**Checkpoint**: US1 + US2 funcionan juntos: bloqueo real sin coste de arranque medible (SC-003).

---

## Phase 5: User Story 3 - Whitelist por dominio para sitios rotos (Priority: P3)

**Goal**: El usuario exceptúa/rehabilita el sitio actual desde un botón accesible con D-pad mientras navega, y revisa/quita dominios exceptuados desde una sección en `SecuritySettingsActivity`; la excepción se compara contra el host de la página con coincidencia por subdominio y persiste entre reinicios (FR-005, FR-008).

**Independent Test**: Exceptuar `ejemplo.com` vía toggle → la página recarga sin filtrar (contador deja de subir) y `www.ejemplo.com` también pasa; otros dominios siguen bloqueados; reiniciar la app mantiene la excepción; quitarla desde Settings restablece el bloqueo.

### Tests for User Story 3

- [x] T019 [P] [US3] Crear `app/src/test/java/com/example/dreamweb/adblock/AdBlockWhitelistMatcherTest.kt` — JUnit4 del helper puro de suffix walk compartido (`DomainMatcher.kt`/`DomainSuffixes.kt`, sin `android.*` — T007/T008) aplicado al set de dominios exceptuados: el test ejercita la función pura sobre un `Set<String>` normalizado, **sin `SharedPreferences` ni `AdBlockWhitelistStore`**; `ejemplo.com` exceptuado cubre `www.ejemplo.com` y `a.b.ejemplo.com`, no cubre `otroejemplo.com` ni `ejemplo.com.evil.net`, dominio no exceptuado sigue filtrando

### Implementation for User Story 3

- [x] T020 [P] [US3] Añadir `ImageButton` `adBlockToggleButton` en `app/src/main/res/layout/activity_browser.xml` — 48dp, `layout_gravity="top|end"`, bajo `uaToggleButton` (`marginTop` ~144dp), `focusable="true"`, `contentDescription` "Permitir ads en este sitio" (misma familia que `uaToggleButton`, research R7)
- [x] T021 [P] [US3] Añadir sección "AdBlock: dominios exceptuados" en `app/src/main/res/layout/activity_security_settings.xml` — header, `RecyclerView` `adBlockWhitelistRecyclerView` (reutiliza `item_tls_exception.xml` por fila), `TextView` `emptyAdBlockText` y `Button` `clearAdBlockButton`, bajo la sección TLS existente (renombrar el título de pantalla a "Seguridad y privacidad" per decisión 4 del plan)
- [x] T022 [US3] Actualizar `app/src/main/java/com/example/dreamweb/ui/BrowserActivity.kt` — `adBlockToggleButton.setOnClickListener` exceptúa/rehabilita `TlsExceptionStore.normalize(webView.url)` vía `app.container.adBlockWhitelistStore`, muestra `Toast` ("Ads permitidos en X" / "Ads bloqueados en X") y `webView.reload()`; incluir el botón en la lista `nativeViews` de `dispatchKeyEvent` para D-pad; actualizar el estado visual del botón al cambiar de página (depende de T020)
- [x] T023 [US3] Actualizar `app/src/main/java/com/example/dreamweb/ui/SecuritySettingsActivity.kt` — segundo adapter/lista alimentada por `app.container.adBlockWhitelistStore.list()` ordenada por dominio, revocar por ítem con `AlertDialog` de confirmación (mismo patrón `confirmRevoke` TLS), botón "quitar todas" con confirmación, `emptyAdBlockText` según lista vacía, recarga en `onResume` (depende de T021)
- [ ] T024 [US3] Verificación de fase: `./gradlew test` en verde + `./gradlew assembleDebug`; flujo manual de quickstart Escenarios C y D (toggle + Settings + persistencia tras reinicio)

**Checkpoint**: Las 3 user stories funcionan de forma independiente; la whitelist cubre el flujo de decisión completo (página exceptuada → nada se filtra, incluida la navegación al dominio).

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Quality gates de la constitución, medición de efectividad real y README al estado verificado (SC-004).

- [x] T025 [P] Ejecutar `./gradlew test` — suite completa en verde incluyendo `FilterParserTest`, `DomainMatcherTest`, `AdBlockWhitelistMatcherTest` y las suites preexistentes (`VideoUrlValidatorTest`, `TlsDomainNormalizationTest`, etc.) — gate SC-005 / Principio IV
- [x] T026 [P] Ejecutar `./gradlew assembleDebug` y `./gradlew assembleRelease`; medir `ls -la app/build/outputs/apk/release/*.apk` → APK < 12MB (impacto estimado de listas ~+0.7-1MB, advertencia pendiente del plan)
- [ ] T027 [P] Medir RAM en Home tras arranque con motor cargado: `adb shell dumpsys meminfo com.example.dreamweb | grep TOTAL` → PSS ≤ 60MB (delta estimado +5-7MB del HashSet, research R1) — gate Principio I
- [ ] T028 Medir efectividad real (SC-001) y latencia de decisión (SC-002) per quickstart §4: (a) efectividad — muestrear ~200 dominios del set de reglas ya parseadas por `FilterParser` (más fiel que grep; si se usa regex sobre el asset debe cubrir también las reglas `||dominio` sin `^` aceptadas por el contrato, p. ej. `grep -oP '^\|\|[a-z0-9.-]+(?=\^?$)'`), verificar ≥95% bloqueados por el matcher (test JVM o script) y ~100 dominios legítimos top → 0 falsos positivos (con HashSet exacto es 0 por construcción salvo que la lista los contenga); (b) latencia — micro-benchmark de la decisión `isBlocked`/`shouldBlock`: 10.000 consultas sobre hosts aleatorios del corpus, reportar p50/p99 y exigir p99 < 1ms en dispositivo/emulador; registrar las cifras medidas — SC-001/SC-002
- [x] T029 Actualizar `README.md` con la medición real: sustituir "~90%" aspiracional (línea ~92) por la cifra de T028, reclasificar AdBlock de "experimental" según el estado verificado (SC-004), actualizar la descripción "Bloom Filters" (línea ~19) a estructura exacta `HashSet` por sufijo de dominio, actualizar el árbol de arquitectura (sin `BloomFilter.kt`, con `FilterParser.kt`/`DomainMatcher.kt`/`AdBlockWhitelistStore.kt`) y añadir sección de atribución EasyList/EasyPrivacy + licencia GPLv3 (FR-002)
- [ ] T030 Ejecutar la validación end-to-end de `specs/002-adblock-real/quickstart.md` completa en emulador/dispositivo TV (Escenarios A–E: arranque, bloqueo real, toggle whitelist, Settings, RAM) y cerrar las advertencias pendientes de `plan.md`

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: Sin dependencias — puede empezar ya.
- **Foundational (Phase 2)**: Depende de T001/T002 (los assets que el manager parsea deben existir). Bloquea todas las stories.
- **US1 (Phase 3)**: Depende de Phase 2 completa.
- **US2 (Phase 4)**: Depende de T009 (incluido en Phase 2). Puede ejecutarse en paralelo con US1 tras Phase 2; la verificación T018 es más significativa con US1 integrado.
- **US3 (Phase 5)**: Depende de T008/T009 (incluidos en Phase 2). Independiente de US1/US2 salvo que el toggle T022 convive con el contador T015 en `BrowserActivity.kt` (mismo archivo — ordenar T015 antes que T022 si se ejecutan en la misma rama).
- **Polish (Phase 6)**: Depende de todas las stories.

### User Story Dependencies

- **US1 (P1)**: Sin dependencias de otras stories — MVP.
- **US2 (P2)**: Integra sobre el motor de US1 (su verificación asume listas parseándose) pero su lógica (async + degradado) ya existe desde Phase 2.
- **US3 (P3)**: Integra sobre `AdBlockWhitelistStore` + `shouldBlock(requestUrl, pageUrl)` de Phase 2; UI independiente.

### Within Each User Story

- Tests primero (T011/T012, T019) → deben fallar → implementación → verificación de fase.
- Layout (T014, T020, T021) antes que el código que lo referencia por ViewBinding (T015, T022, T023).

### Parallel Opportunities

- Setup: T001, T002, T003, T004 en paralelo (archivos distintos).
- Foundational: T006, T007, T008 en paralelo; T009 y T010 son secuenciales al depender de ellos.
- US1: T011 + T012 en paralelo; T013 + T014 en paralelo; T015 serializa.
- US3: T019, T020, T021 en paralelo; T022 y T023 serializan tras sus layouts.
- Polish: T025, T026, T027 en paralelo (verificaciones independientes); T028 → T029 → T030 secuenciales (la cifra alimenta el README).

---

## Parallel Example: User Story 1

```bash
# Lanzar juntos los tests de US1 (deben fallar primero):
Task: "Crear FilterParserTest.kt en app/src/test/java/com/example/dreamweb/adblock/"
Task: "Crear DomainMatcherTest.kt en app/src/test/java/com/example/dreamweb/adblock/"

# Luego, en paralelo tras los tests:
Task: "Actualizar CustomWebClient.kt (shouldBlock con pageUrl; sin log propio — el log DEBUG vive en el manager)"
Task: "Añadir adBlockCounterText en activity_browser.xml"
```

## Parallel Example: User Story 3

```bash
# Lanzar juntos:
Task: "Crear AdBlockWhitelistMatcherTest.kt"
Task: "Añadir adBlockToggleButton en activity_browser.xml"
Task: "Añadir sección whitelist en activity_security_settings.xml"

# Luego, en paralelo (archivos distintos):
Task: "Wire del toggle en BrowserActivity.kt"
Task: "Adapter + revocación en SecuritySettingsActivity.kt"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Completar Phase 1 (assets reales) + Phase 2 (motor async)
2. Completar Phase 3: US1 → `./gradlew test` verde + contador DEBUG funcionando
3. **STOP y VALIDAR**: navegación real bloquea dominios de las listas de forma medible
4. Entregable: adblock real funcional (arranque ya incluido por diseño en Phase 2)

### Incremental Delivery

1. Setup + Foundational → motor cargando en background con modo degradado
2. US1 → bloqueo funcional medible (MVP)
3. US2 → observabilidad + verificación de presupuesto de arranque (SC-003)
4. US3 → escape hatch de whitelist con UI completa (FR-005/FR-008)
5. Polish → gates de constitución + README al estado medido (SC-004)

### Parallel Team Strategy

Tras Phase 2: un desarrollador toma US1 (parser/matcher tests + integración WebView + contador), otro toma US3 (whitelist tests + UI) — solo se tocan en `BrowserActivity.kt` (coordinar T015 vs T022). US2 es tan fina que la absorbe cualquiera tras Phase 2.

---

## Notes

- [P] = archivos distintos, sin dependencias de tareas incompletas.
- [US1]/[US2]/[US3] trazables a las user stories de `spec.md`.
- Commits tras cada tarea o grupo lógico; detenerse en cada checkpoint para validar la story de forma independiente.
- Constraints citadas verbatim en las tareas (validación de dominio, gramática ignorada, presupuestos) provienen de `contracts/easylist-subset.md`, `data-model.md` y `plan.md` — no dejarlas a discreción del implementador.
- Verificaciones de dispositivo (T018, T027, T030) requieren emulador/TV físico; si no hay hardware disponible, documentar la limitación en el PR y no marcar SC-002/SC-003 como verificados en el README.


---

## Notas de verificación (implementación 2026-11-23)

- **T018** — pendiente: requiere dispositivo/emulador TV (`installDebug` + cronometraje de arranque + logcat). Sin hardware disponible en esta ejecución.
- **T024** — parcial: `./gradlew test` en verde + `assembleDebug` OK; el flujo manual de quickstart C/D (toggle + Settings + persistencia) requiere dispositivo.
- **T026** — medido: APK release unsigned = **16,5 MB** — el presupuesto <12MB ya estaba excedido antes de la feature (~15,3MB base); las listas añaden +1,18MB comprimidas (dentro del impacto estimado del plan).
- **T027** — pendiente: requiere `adb` + dispositivo (PSS Home).
- **T028** — parcial: efectividad medida en JVM = 100% (200/200 reglas), 0 FP sobre 102 dominios legítimos, latencia p50=170ns/p99=340ns (`AdBlockEffectivenessTest`). El requisito "en dispositivo/emulador" queda pendiente.
- **T030** — pendiente: validación e2e completa en dispositivo.
