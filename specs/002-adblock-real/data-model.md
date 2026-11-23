# Data Model: AdBlock Real

**Feature**: `002-adblock-real` | **Date**: 2026-11-22

Entidades del dominio, su representación en código y las reglas de validación
derivadas de `spec.md`. El núcleo (`FilterParser`, `DomainMatcher`) es Kotlin
puro sin dependencias `android.*` para tests JVM (Principio IV, FR-007).

---

## Entidades

### FilterRule (runtime, no persistida)

Regla parseada de una línea de lista. Solo existen dos tipos en v1:

```kotlin
sealed interface FilterRule {
    val domain: String   // dominio normalizado (lowercase, sin '^', sin esquema)
    data class DomainBlock(override val domain: String) : FilterRule   // "||d^"
    data class Exception(override val domain: String) : FilterRule     // "@@||d^"
}
```

- **Validación del dominio**: `^[a-z0-9]([a-z0-9.-]*[a-z0-9])?$`, debe contener
  al menos un `.`, sin `*` ni `$`, longitud ≥ 4. Punycode (`xn--`) entra por el
  charset. Una línea que no encaja en la gramática (`contracts/easylist-subset.md`)
  no produce `FilterRule` — produce `ignored++`.
- **Transición**: línea → `FilterRule` → entrada en `DomainMatcher`. No hay
  mutación posterior.

### DomainMatcher (runtime, inmutable tras construcción)

La estructura de matching publicada vía `@Volatile` (ver `research.md` R3):

```kotlin
class DomainMatcher(
    private val blocked: Set<String>,      // HashSet — reglas "||d^"
    private val exceptions: Set<String>    // HashSet — reglas "@@||d^"
) {
    fun isBlocked(host: String): Boolean   // exceptions primero, luego blocked
    val ruleCount: Int
    companion object { val EMPTY = DomainMatcher(emptySet(), emptySet()) }
}
```

- **Matching**: suffix walk por etiquetas de `host` (él mismo y cada padre:
  `a.b.c` → `a.b.c`,`b.c`,`c`). `host` ya viene normalizado.
- **Precedencia**: `exceptions` se consulta antes que `blocked` en la misma
  pasada; la primera coincidencia de excepción gana (semántica `@@`).
- **`EMPTY`**: matcher vacío publicado al arranque → `isBlocked` = false
  (modo degradado documentado, US2 escenario 2).

### FilterList (runtime, metadatos)

```kotlin
data class FilterStats(
    val source: String,        // "easylist.txt" | "easyprivacy.txt"
    val linesRead: Int,
    val acceptedBlocked: Int,  // reglas "||d^" aceptadas
    val acceptedExceptions: Int, // reglas "@@||d^" aceptadas
    val ignored: Int           // comentarios, cabeceras, cosméticas, $, *, inválidas
)

enum class LoadState { LOADING, READY, FAILED }
```

- Estado expuesto por `AdBlockManager` (`isReady`, `stats`) para diagnóstico en
  debug y para la medición de efectividad (SC-001). `FAILED` solo si el asset no
  se puede abrir; líneas individuales corruptas nunca tumban el parseo (edge
  case del spec) — cuentan como `ignored`.

### WhitelistedDomain (persistida)

```text
SharedPreferences file: "adblock_whitelist"
  key   = dominio normalizado (mismo normalize() que TlsExceptionStore:
          lowercase, sin esquema/userinfo/puerto/path/punto final)
  value = Long epochMillis de cuándo se exceptuó (para ordenar/mostrar fecha)
```

- **Validación**: `normalize()` devolviendo `""` → no se escribe.
- **Semántica de match** (Clarifications): comparada contra el **host de la
  página** visitada con suffix walk — `ejemplo.com` exceptuado cubre
  `sub.ejemplo.com`. Consulta anterior al filtro (FR-005).
- **Caché**: `@Volatile Set<String>` en `AdBlockWhitelistStore`, rehidratada en
  construcción y actualizada en cada `add`/`remove`/`clear` → lecturas O(1) por
  sonda sin I/O en el hilo de red.

### BlockedRequestsCounter (runtime)

`AtomicLong` en `AdBlockManager`; `+1` únicamente cuando una petición se bloquea
de verdad (no cuando whitelist/excepción permite). Legible vía `blockedCount()`.
Sin persistencia (métrica de sesión suficiente para FR-006/SC-004).

---

## Relaciones y flujo de decisión

```text
shouldInterceptRequest(request, view):
  pageHost    = normalize(view.url)
  requestHost = normalize(request.url)

  1. whitelist.isExcepted(pageHost)   → true ⇒ ALLOW (nada se filtra en esta página)
  2. engine.isBlocked(requestHost):               (engine = matcher volátil)
       a. suffix walk sobre exceptions → match ⇒ ALLOW
       b. suffix walk sobre blocked    → match ⇒ BLOCK + counter++
       c. sin match ⇒ ALLOW
```

Navegación (`shouldOverrideUrlLoading`): mismo flujo con
`pageHost = requestHost` (el destino es la página) — un dominio en whitelist o
no listado navega libre; uno bloqueado se corta (retorna `true`).

---

## Persistencia — resumen

| Dato | Store | Volumen | Migración |
|------|-------|---------|-----------|
| Listas de filtros | `assets/filters/` (read-only, empaquetado) | ~2.5MB crudo | se reemplaza con el APK |
| Whitelist | SharedPreferences `adblock_whitelist` | decenas de claves | n/a (formato clave estable) |
| Estadísticas de carga | memoria (`FilterStats`) | 2 instancias | n/a |
| Contador de bloqueos | memoria (`AtomicLong`) | 1 long | n/a |

Sin cambios en `DbHelper` (no hay tabla nueva — ver `research.md` R4) y sin
nuevas dependencias.
