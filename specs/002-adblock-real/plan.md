# Implementation Plan: AdBlock Real (Parser EasyList + Listas Reales)

**Branch**: `002-adblock-real` | **Date**: 2026-11-22 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/002-adblock-real/spec.md`

## Summary

Convertir el AdBlock decorativo actual (3 hosts dummy + BloomFilter de 64 Kbit con
matching por host exacto) en un motor funcional: parser de sintaxis EasyList real
(subconjunto v1: `||dominio^` y excepciones `@@||dominio^`), listas EasyList +
EasyPrivacy completas empaquetadas en `assets/` con atribución/licencia, carga y
parseo en hilo de fondo con swap atómico, matching exacto por sufijo de dominio
(HashSet, 0% FP), whitelist por sitio persistida en SharedPreferences (toggle en
BrowserActivity + sección en la pantalla de Seguridad) y contador de bloqueos
visible en builds debug.

La decisión estructural clave (ver `research.md`): **estructura exacta
`HashSet<String>`** en lugar del BloomFilter actual — un Bloom dimensionado para
60K reglas y <1% FP global necesita ~100KB y aún deja falsos positivos
multiplicados por las ~4-6 sondas de sufijo por host, mientras que el set exacto
cuesta ~5-7MB de heap (dentro del presupuesto de Home ≤60MB), da FP = 0% y
soporta excepciones `@@` de forma exacta.

## Technical Context

**Language/Version**: Kotlin (JVM target 11) sobre AGP 9.2.1 (built-in Kotlin), minSdk 28

**Primary Dependencies**: stack existente solamente — `androidx.webkit` (WebViewClient/shouldInterceptRequest), `kotlinx-coroutines-android` (ya usada en managers), JUnit4 (tests). **Sin dependencias nuevas** (parser y matching se implementan a mano; `java.net.URI` para host parsing es JVM puro).

**Storage**: listas de filtros como assets empaquetados (`app/src/main/assets/filters/`); whitelist de dominios en `SharedPreferences` propio (`adblock_whitelist`, patrón idéntico a `TlsExceptionStore`); contador en memoria (`AtomicLong`).

**Testing**: JUnit4 en `app/src/test` (`./gradlew test`, sin dispositivo). El núcleo parser/matcher se diseña como clases Kotlin puras sin imports `android.*` para ser testeable en JVM (Principio IV).

**Target Platform**: Android TV / leanback, Android 9+ (minSdk 28)

**Project Type**: mobile-app (módulo único `app/`)

**Performance Goals**: decisión de bloqueo < 1ms por petición en el hilo de red del WebView; parseo de ~100K líneas completo en background sin afectar el arranque; FP < 1% (objetivo real: 0% con estructura exacta).

**Constraints**: APK release < 12MB (las listas añaden ~0.7-1MB comprimidas — verificar); RAM Home ≤ 60MB (el motor añade ~5-7MB de heap, ver `research.md`); cero trabajo en hilo principal; logging de bloqueos solo tras `BuildConfig.DEBUG` (Principio I).

**Scale/Scope**: ~60.000+ reglas de dominio aceptadas entre EasyList + EasyPrivacy (de ~100-130K líneas totales); whitelist acotada por uso de usuario (decenas de entradas).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principio | Evaluación | Resultado |
|-----------|------------|-----------|
| I. Eficiencia de recursos | +5-7MB heap (set exacto) y +~1MB APK (listas) declarados como impacto estimado <10% de los presupuestos (60MB Home / 12MB APK). Carga one-shot en un único hilo worker, sin loops permanentes. Log por petición bloqueada solo en `DEBUG`. Sin WebViews nuevos. | PASS (impacto declarado y verificable) |
| II. Seguridad/privacidad | El bloqueo devuelve `WebResourceResponse` vacío (mismo mecanismo actual); no toca TLS ni añade `JavascriptInterface`. La whitelist solo desactiva filtrado de ads, nunca validación TLS. | PASS |
| III. Features verificables | Reemplaza exactamente el stub señalado por la constitución. Listas reales completas + contador medible + tests de efectividad. README se actualiza con medición real (SC-004). | PASS |
| IV. Tests en lógica crítica | Parser y matcher como clases JVM puras con suite JUnit4 dedicada (`FilterParserTest`, `DomainMatcherTest`, whitelist matching). | PASS |
| V. Ciclo de vida/ownership | `AdBlockManager` vive en `AppContainer` (scope aplicación); su executor es propiedad del manager, one-shot — recibe `shutdown()` tras completar la carga y el swap del matcher — sin referencias a Activity. `CustomWebClient` sigue liberándose vía `releaseWebView` (patrón existente). Sin `GlobalScope`. | PASS |

**Post-Phase-1 re-check**: sin violaciones; la única complejidad nueva (un hilo
worker one-shot + ~2MB de assets) queda justificada arriba frente a Principios I y V.
No se requiere entrada en Complexity Tracking.

## Project Structure

### Documentation (this feature)

```text
specs/002-adblock-real/
├── plan.md              # This file (/speckit-plan command output)
├── research.md          # Phase 0 output (/speckit-plan command)
├── data-model.md        # Phase 1 output (/speckit-plan command)
├── quickstart.md        # Phase 1 output (/speckit-plan command)
├── contracts/           # Phase 1 output (/speckit-plan command)
│   └── easylist-subset.md   # Gramática soportada + semántica de matching
└── tasks.md             # Phase 2 output (/speckit-tasks command - NOT created by /speckit-plan)
```

### Source Code (repository root)

```text
app/src/main/
├── assets/filters/
│   ├── easylist.txt                 # EasyList COMPLETA (descargada de easylist.to)
│   ├── easyprivacy.txt              # EasyPrivacy COMPLETA
│   ├── NOTICE.txt                   # Procedencia, versión/fecha, atribución
│   └── LICENSE.GPLv3.txt            # Texto de licencia (FR-002)
├── java/com/example/dreamweb/
│   ├── adblock/
│   │   ├── FilterParser.kt          # NUEVO — Kotlin puro: línea → regla | ignora
│   │   ├── DomainMatcher.kt         # NUEVO — Kotlin puro: HashSets + sufijos
│   │   ├── AdBlockManager.kt        # REESCRITO — orquesta carga async, swap
│   │   │                            #   atómico, contador, integra whitelist
│   │   ├── AdBlockWhitelistStore.kt # NUEVO — SharedPreferences + set volátil
│   │   ├── BloomFilter.kt           # ELIMINADO (reemplazado por DomainMatcher)
│   │   └── PrivacyEngine.kt         # sin cambios
│   ├── engine/CustomWebClient.kt    # pasa pageHost a shouldBlock; sin log en
│   │                                #   release (ya DEBUG-gated donde aplica)
│   ├── ui/
│   │   ├── BrowserActivity.kt       # botón toggle whitelist + contador debug
│   │   └── SecuritySettingsActivity.kt  # +sección "AdBlock: dominios exceptuados"
│   └── res/layout/
│       ├── activity_browser.xml     # +adBlockToggleButton +contador debug
│       └── activity_security_settings.xml  # +sección whitelist
└── (item_tls_exception.xml se reutiliza para filas de whitelist)

app/src/test/java/com/example/dreamweb/
└── adblock/
    ├── FilterParserTest.kt          # NUEVO — gramática: ||d^, @@, !, ##, [, $, inválidas
    ├── DomainMatcherTest.kt         # NUEVO — sufijos, subdominios, precedencia @@
    └── AdBlockWhitelistMatcherTest.kt # NUEVO — whitelist por sitio con subdominios
```

**Structure Decision**: módulo único existente; el núcleo del motor queda en
`adblock/` como clases Kotlin puras (sin `android.*`) para tests JVM por
Principio IV; `AdBlockManager` conserva su posición en `AppContainer` y el único
punto de integración con WebView sigue siendo `CustomWebClient`. La whitelist se
persiste con el mismo patrón SharedPreferences ya usado por `TlsExceptionStore`
y su UI se añade como sección a `SecuritySettingsActivity` (ya es "la pantalla
de ajustes" alcanzable desde Home) en lugar de crear una Activity nueva.

## Decisiones tomadas (documentadas — sin clarificador disponible)

1. **Matching exacto `HashSet<String>` en vez de BloomFilter** — rationale y
   números en `research.md` R1. `BloomFilter.kt` se elimina.
2. **Reglas con cualquier opción `$...` se ignoran enteras en v1** — FR-001 pide
   ignorar "opciones no soportadas"; soportar un subconjunto (`third-party`,
   `domain=`) añade estado y tests sin cambiar el modelo de matching por host.
   Medible vía stats del parser (SC-001 se evalúa sobre reglas aceptadas).
3. **Whitelist = SharedPreferences, no SQLite** — decenas de entradas máximo,
   lectura caliente por petición (set volátil en memoria sobre el store); el
   patrón ya existe en `TlsExceptionStore` y no justifica tabla nueva.
4. **Sección de whitelist dentro de `SecuritySettingsActivity`** — Home ya tiene
   un solo punto de entrada ("Seguridad"); una Activity nueva añadiría navegación
   sin valor. La pantalla pasa a cubrir "Seguridad y privacidad".
5. **Mientras el motor carga: modo degradado "no bloquear"** — `shouldBlock`
   devuelve false contra un matcher vacío publicado vía `@Volatile`; nunca
   bloquea el hilo de red del WebView (US2 escenario 2).
6. **Navegación a dominio en whitelist → permitida** — en
   `shouldOverrideUrlLoading` el host de destino actúa como "page host": un
   dominio exceptuado ni se bloquea como recurso ni como destino de navegación.
7. **Obtención de listas**: descarga única desde `https://easylist.to/easylist/easylist.txt`
   y `.../easyprivacy.txt` en tiempo de desarrollo, commit a `assets/filters/`
   con `NOTICE.txt` (versión/fecha de captura). Update remoto fuera de scope (spec).

## Advertencias / verificaciones pendientes

- **APK <12MB**: hay que medir `assembleRelease` tras empaquetar ~2MB de listas
  (comprimen a ~0.7-1MB pero el margen real depende del tamaño actual del APK).
- **RAM Home ≤60MB**: verificar PSS tras cargar el set (~5-7MB estimados).
- **Las listas hay que descargarlas** (paso manual del implementador o script);
  el repo aún contiene los assets dummy de 4 líneas.
- El filtrado por host no bloqueará ads servidos desde el dominio propio de la
  página (first-party ads); la efectividad medida (SC-001/SC-004) debe evaluarse
  contra dominios `||` de las listas, no contra "toda la publicidad".
