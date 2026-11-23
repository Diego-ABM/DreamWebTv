# Research: AdBlock Real

**Feature**: `002-adblock-real` | **Date**: 2026-11-22

Resolución de todas las decisiones técnicas del Technical Context. No quedan
marcadores NEEDS CLARIFICATION: las clarificaciones de producto ya están en
`spec.md > Clarifications` y las técnicas se resuelven aquí contra el código real.

---

## R1. Estructura de matching para ≥60.000 reglas, <1% FP, <1ms

**Decision**: `HashSet<String>` exacto — dos sets (`blocked`, `exceptions`) con
dominios en lowercase; cada consulta hace un *suffix walk* por las etiquetas del
host (`a.b.ads.com` → `a.b.ads.com`, `b.ads.com`, `ads.com`, `com`), máximo ~6
sondas O(1). `BloomFilter.kt` se elimina.

**Rationale** (con números):

- *FP*: el spec exige <1% de falsos positivos **sobre dominios legítimos**
  (SC-001). Un BloomFilter para n=60K a p=1% necesita
  `m = -n·ln(p)/(ln2)² ≈ 575K bits ≈ 70KB` y k≈7 hashes. Pero el matching por
  sufijo multiplica la tasa: con ~4-6 sondas por host la FP efectiva por
  consulta es ~4-6%. Para bajarla a <1% global hay que apuntar a p≈0.2%
  (m≈105KB, k≈9) y aun así queda FP>0: recursos de sitios legítimos
  cortados silenciosamente — el peor defecto de UX posible en un navegador.
  Además las excepciones `@@` necesitarían un segundo filtro, cuyos FP
  *desbloquearían* ads. El set exacto tiene FP = 0% y FN = 0% por construcción.
- *RAM*: 60K dominios, media ~18 chars. En ART/JVM: String compacto Latin-1
  ≈ 16B header + 8B char[] + ~18B datos ≈ 42-60B; nodo HashMap ≈ 32B;
  tabla ≈ 4B·capacidad. Total ≈ 60K × ~95B ≈ **5-7MB** — asumible dentro del
  presupuesto Home ≤60MB (impacto declarado ~8-10%, verificado en quickstart).
  El Bloom ahorraría ~6MB pero introduce FP y una segunda estructura para `@@`.
- *Latencia*: `String.hashCode()` está cacheado tras el primer cálculo; cada
  sonda es una búsqueda HashMap ~50-150ns → peor caso ~6 sondas ≈ <1µs,
  tres órdenes de magnitud bajo el presupuesto de 1ms. Sin asignaciones en el
  camino caliente (los sufijos se extraen con `substring`… mejor: se comparan
  por `regionMatches`/índices para no generar basura — detalle de implementación,
  la opción `substring` también cumple 1ms con margen x1000).
- *Threading*: el set se construye entero en background y se publica con una
  escritura `@Volatile` (swap atómico); lecturas concurrentes sin locks desde el
  hilo de red del WebView. Un Bloom compartiría el mismo modelo, así que no es
  factor diferenciador.

**Alternatives considered**:

- *BloomFilter dimensionado (redimensionar el actual 64Kbit/3hash)*: rechazado —
  FP efectiva por consulta ~4-6% a p=1% por multiplicación de sondas; excepciones
  `@@` inexatas; el ahorro (~6MB) no compensa perder corrección exacta dentro
  del presupuesto de RAM. El Bloom actual (64Kbit, n≈60K) saturaría a ~50%+ FP.
- *Bloom como pre-filtro delante del HashSet*: rechazado — el HashSet ya es O(1)
  y ~1µs; añadir capa probabilística solo introduce FP sin ganar velocidad ni RAM
  (el set sigue existiendo entero).
- *Trie / radix tree de etiquetas invertidas*: considerado — bajaría a ~3-4MB y
  una sola pasada por host en vez de 6 sondas, pero ~100 líneas más de lógica
  propia (nodo compartido por excepciones vs bloqueos, inserción etiqueta a
  etiqueta) sin necesidad: el set cabe con holgura. Re-evaluable si la RAM medida
  real supera lo estimado.
- *HashSet<String> de dominios + matching por host exacto (status quo ampliado)*:
  rechazado — el edge case del spec exige que `||ads.ejemplo.com^` bloquee
  `x.ads.ejemplo.com`; se necesita suffix walk sí o sí.

---

## R2. Subconjunto de sintaxis EasyList soportado en v1

**Decision**: aceptar únicamente reglas de dominio `||dominio^` (con `^` o fin de
línea como terminador — `||dominio` EOL recibe la misma semántica de
domain-suffix match que `^`; divergencia documentada respecto a EasyList
estricto, que la trata como prefijo de dominio: ver contrato §1) y excepciones
`@@||dominio^`. Ignorar explícitamente:
comentarios `!`, cabeceras `[...]`, cosméticas `##`/`#@#`/`#?#`/`#$#`, cualquier
regla que contenga `$` (opciones), `*` (wildcards), `|`/`||`+path (patrones de
URL), regex `/re/`, reglas de substring sin ancla y entradas estilo hosts-file.
La gramática completa queda en `contracts/easylist-subset.md`.

**Rationale**: FR-001 y Assumptions delimitan v1 a matching por host; el resto
de sintaxis requiere matching sobre URL completa (path, regex, opciones
`$third-party`/`$domain=` que además necesitan conocer el dominio origen y
destino — más estado y más sondas). Ignorarlas es parseo tolerante sin falsos
positivos, que es lo que mide el spec.

**Alternatives considered**: soportar `$third-party`/`$domain=` (añade dimensión
origen→destino y una segunda capa de sets: descartado por no estar en el modelo
de decisión por host de v1); tratar reglas de substring tipo `ads.js` como
dominio (FP masivo — una regla `/ad.` coincidiría con `ad.` dentro de cualquier
host: descartado).

**Cobertura esperada**: las reglas `||`+`@@||` son típicamente la fracción mayor
de reglas de red en EasyList/EasyPrivacy (~30-50K aceptadas de ~100-130K líneas
totales incluyendo comentarios y cosméticas). El parser contará
aceptadas/ignoradas (`FilterStats`) y SC-001 se mide sobre el set derivado de
reglas aceptadas.

---

## R3. Threading de carga y publicación del motor

**Decision**: `AdBlockManager.init()` pasa a `loadAsync()`:
`Executors.newSingleThreadExecutor()` (propiedad del manager, scope aplicación,
one-shot — no es "trabajo en background permanente" del Principio I; el executor
recibe `shutdown()` tras completar la carga y publicar el matcher, vida acotada
estrictamente al parseo). El hilo lee
los dos assets con `BufferedReader`, alimenta `FilterParser` línea a línea,
construye `DomainMatcher` y lo publica en `@Volatile var engine` (valor inicial
`DomainMatcher.EMPTY` → `shouldBlock` devuelve false mientras carga: modo
degradado documentado, nunca bloquea el hilo de red del WebView ni crashea).
Estado de carga observable (`isReady`, `FilterStats`) para el contador/debug.

**Rationale**: `shouldInterceptRequest` corre en un hilo de red de WebView, no en
main — la consulta ya es concurrente por naturaleza; la escritura volátil única
tras construcción da safe-publication sin locks en el camino caliente. Un
executor dedicado evita `GlobalScope` (Principio V) y mantiene la dependencia
cero. `Application.onCreate` solo lanza la tarea (µs) → arranque sin ANR (US2).

**Alternatives considered**: corrutina `Dispatchers.IO` sobre un scope de
Application (equivalente, pero un executor expresa mejor el ownership one-shot y
evita un scope que nunca se cancela); `AsyncTask`/Thread cruda (más boilerplate,
mismo efecto); `CountDownLatch`/`runBlocking` (bloquearía el hilo de red —
violación directa de US2 escenario 2).

---

## R4. Persistencia y matching de la whitelist por sitio

**Decision**: `AdBlockWhitelistStore` — SharedPreferences propio
(`adblock_whitelist`), clave = dominio normalizado, valor = epochMillis (patrón
idéntico a `TlsExceptionStore`, incluida la reutilización de su
`normalize()` companion, ya testeada por `TlsDomainNormalizationTest`). Encima
del store, un `@Volatile Set<String>` en memoria alimentado al cargar y en cada
`add`/`remove`, para que `isExcepted(pageHost)` haga el suffix walk en RAM sin
tocar disco en el hilo de red.

Semántica (de Clarifications): la whitelist se compara contra el **host de la
página** (`view.url`), no contra el host del recurso — si la página pertenece a
un dominio exceptuado (él mismo o cualquier subdominio: `ejemplo.com` cubre
`www.ejemplo.com`), ningún recurso de esa página se filtra. Consulta **antes**
del filtro (FR-005).

**Alternatives considered**: tabla SQLite nueva en `DbHelper` (válida por
constitución pero sobredimensionada para decenas de strings; además exigiría
subir `DATABASE_VERSION` y migración); `DataStore`/JSON (dependencia o formato
nuevo sin necesidad); match exacto sin subdominios (contradice Clarifications).

---

## R5. Integración con WebViewClient y contador de bloqueos

**Decision**: `AdBlockManager.shouldBlock(requestUrl, pageUrl)` — firma nueva:
`CustomWebClient.shouldInterceptRequest` pasa `request.url` y `view?.url`; en
`shouldOverrideUrlLoading` el host destino actúa como pageHost (navegar a un
dominio en whitelist no se bloquea). El bloqueo devuelve
`WebResourceResponse("text/plain","UTF-8",null)` (mecanismo actual, ya probado).

Contador: `AtomicLong blockedRequests` en `AdBlockManager` incrementada solo en
bloqueo efectivo; lectura por `blockedCount()`. Visibilidad debug (FR-006):
`TextView` en `activity_browser.xml` (`visibility="gone"`, activado solo si
`BuildConfig.DEBUG`) actualizado en `onPageStarted`/`onPageFinished`, más línea
de log por bloqueo tras `if (BuildConfig.DEBUG)` (logging continuo en producción
está prohibido por Principio I).

**Alternatives considered**: persistir el contador en prefs (métrica de sesión
suficiente para medir efectividad; persistir añade escrituras por petición);
overlay permanente en release (violaría "logging continuo en producción").

---

## R6. Empaquetado de listas: origen, licencia y peso

**Decision**: descarga única en desarrollo desde las URLs canónicas
`https://easylist.to/easylist/easylist.txt` y `https://easylist.to/easylist/easyprivacy.txt`
(listas íntegras, no recortadas — Clarifications). Se commitean en
`app/src/main/assets/filters/` junto a `NOTICE.txt` (procedencia, URL, fecha de
captura, atribución "EasyList / EasyPrivacy — easylist.to") y
`LICENSE.GPLv3.txt` (EasyList/EasyPrivacy son **dual-licensed GPLv3 / CC BY-SA
3.0** — la cabecera de las propias listas lo declara; el proyecto **elige
distribuirlas bajo GPLv3** y `NOTICE.txt` documenta esa elección junto a la
atribución). README gana una sección de atribución + la
medición real de efectividad (SC-004). Update remoto: fuera de scope v1.

Peso: ~1.5-2MB + ~0.4-0.6MB en crudo; los assets se comprimen (deflate) dentro
del APK → impacto estimado +0.7-1MB sobre APK release <12MB (**verificar** con
`assembleRelease`, es la verificación pendiente del plan). Parseo: ~100-130K
líneas, ~2.5MB de I/O+CPU en el hilo worker ≈ 1-3s en hardware TV — invisible en
arranque por ir en background.

**Alternatives considered**: descarga en primer arranque vía OkHttp (fuera de
scope, añade superficie de red+TLS+fallback); listas pre-procesadas a formato
binario en build (más tooling, sin ganancia: el parseo async ya es invisible);
subconjunto "top N dominios" (contradice Clarifications: listas completas).

---

## R7. UX: toggle en navegación + lista en Settings

**Decision**: `ImageButton` `adBlockToggleButton` en `activity_browser.xml`
(misma familia que `uaToggleButton`, D-pad vía `nativeViews` de `dispatchKeyEvent`),
que exceptúa/rehabilita el dominio del `webView.url` actual, muestra Toast y
`webView.reload()`. Lista: nueva sección "AdBlock: dominios exceptuados" en
`SecuritySettingsActivity` (header + `RecyclerView` reutilizando
`item_tls_exception.xml` + botón "quitar todas"), alimentada por
`AdBlockWhitelistStore.list()` — mismo patrón que las excepciones TLS
(revocar por ítem con `AlertDialog` de confirmación).

**Alternatives considered**: Activity de ajustes nueva (más archivos y
navegación por una lista de strings; Home ya tiene un solo botón de ajustes);
menú dentro de la página vía JS (frágil, inconsistente con la UI nativa TV).
