# Contract: Subconjunto EasyList soportado (v1)

Contrato de entrada/salida del motor de filtros. Es la referencia para
`FilterParser`, `DomainMatcher` y sus tests — define qué líneas producen reglas
y con qué semántica se aplican. Todo lo no cubierto explícitamente se **ignora**
(parseo tolerante: nunca crash, nunca falso positivo por línea mal formada).

## 1. Gramática aceptada

```ebnf
line        = comment | header | cosmetic | exceptionRule | domainRule | ignored ;

comment     = "!" , *any ;                                (* ignorada *)
header      = "[" , *any , "]" ;                          (* ignorada: [Adblock Plus ...] *)
cosmetic    = *any , ("##" | "#@#" | "#?#" | "#$#") , *any ; (* ignorada *)

exceptionRule = "@@" , domainRule ;
domainRule  = "||" , domain , terminator ;
terminator  = "^" , end-of-line | end-of-line ;

domain      = label , 1*( "." , label ) ;
label       = alnum , *( alnum | "-" ) ;
alnum       = "a".."z" | "0".."9" ;   (* tras lowercase; punycode "xn--" válido *)
```

**Producción**: cada `domainRule` → `FilterRule.DomainBlock(domain)`; cada
`exceptionRule` → `FilterRule.Exception(domain)`; el resto → contador `ignored`.

> **`||dominio` sin `^` (fin de línea)**: se acepta y recibe la **misma
> semántica de domain-suffix match** que `||dominio^` — bloquea el dominio y
> todos sus subdominios (§3). **Divergencia documentada respecto a EasyList
> estricto**: la sintaxis completa trata `||dominio` como *prefijo* de dominio
> (coincidiría además con `dominio.evil.net` o `dominiofoo.com`). Nuestra
> interpretación es más conservadora y consistente con el matching por host de
> v1: el mismo conjunto de dominio+subdominios bloqueado, sin los falsos
> positivos que produciría el prefijo.

## 2. Líneas ignoradas explícitamente (v1)

| Patrón | Motivo |
|--------|--------|
| Contiene `$` tras el `^` (`||d^$third-party`, `||d^$domain=x`) | opciones no soportadas — el matching v1 es por host de recurso, sin dimensión origen |
| Contiene `*` (`||ads.*`, `*/banner/*`) | wildcard ⇒ matching por URL, fuera de scope |
| `||d` seguido de `^` y path (`||d.com^/banner`) | patrón de path ⇒ matching por URL |
| Anclas de URL `|http`, `\|\|`+path, regex `/re/` | matching por URL |
| Substring/hosts-style (`ads.example`, `0.0.0.0 d.com`) | sin ancla `||`; tratarlos como dominio crearía FP |
| Líneas vacías / whitespace / cualquier cosa no listada | tolerancia a formato inesperado |

> Nota de cobertura: ignorar `||d^$options` también descarta reglas que
> hubieran bloqueado — es la decisión 2 de `plan.md`; el parser la hace
> medible vía `FilterStats.ignored`.

## 3. Semántica de matching (salida)

Entrada: `requestHost` normalizado (lowercase; sin esquema, userinfo, puerto,
path ni punto final — mismo `normalize()` que `TlsExceptionStore`).

`DomainMatcher.isBlocked(requestHost)`:

1. **Sufijos**: la consulta se hace sobre `requestHost` y cada dominio padre
   (`a.b.ads.com` → `a.b.ads.com`, `b.ads.com`, `ads.com`, `com`).
2. **Excepciones primero**: cualquier sufijo en `exceptions` ⇒ `false`.
3. **Bloqueo**: cualquier sufijo en `blocked` ⇒ `true`.
4. Sin coincidencia ⇒ `false`.

Esto implementa la semántica `^` de EasyList al nivel de dominio:
`||ads.ejemplo.com^` bloquea `ads.ejemplo.com` y `x.ads.ejemplo.com`. La misma
semántica aplica a las reglas `||dominio` sin `^` (ver nota de divergencia en §1).

## 4. Semántica de whitelist (capa previa)

`AdBlockWhitelistStore.isExcepted(pageHost)` aplica el **mismo suffix walk**
sobre el host de la página visitada contra el set de dominios exceptuados por
el usuario. Se consulta antes del matcher; `true` ⇒ ningún recurso de esa
página se filtra (incluida la navegación hacia ese dominio).

## 5. Casos de aceptación mínimos (→ tests en quickstart)

| Entrada | Resultado esperado |
|---------|--------------------|
| `||doubleclick.net^` | `DomainBlock("doubleclick.net")` |
| `||doubleclick.net` (EOL, sin `^`) | `DomainBlock("doubleclick.net")` |
| `@@||ads.ejemplo.com^` | `Exception("ads.ejemplo.com")` |
| `! comentario`, `[Adblock Plus 3.0]` | ignorada |
| `example.com##.ad-banner`, `example.com#@#.ad` | ignorada |
| `||d.com^$third-party` | ignorada |
| `||ads.*^`, `|http://x/`, `/ads\d+/`, `||d.com^/path` | ignorada |
| `||sub` (sin punto), `||`, `||-bad-.com^` | ignorada (dominio inválido) |
| host `x.ads.ejemplo.com` vs regla `ads.ejemplo.com` | BLOCK |
| host `ads.ejemplo.com` con `@@||ads.ejemplo.com^` | ALLOW |
| pageHost `www.ejemplo.com` con whitelist `ejemplo.com` | ALLOW todo |
