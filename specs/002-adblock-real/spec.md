# Feature Specification: AdBlock Real (Parser EasyList + Listas Reales)

**Feature Branch**: `002-adblock-real`

**Created**: 2026-11-22

**Status**: Draft

**Input**: User description: "Convertir el AdBlock decorativo actual (listas dummy, matching por host exacto) en un motor funcional que parse sintaxis EasyList real, cargue en background y bloquee de forma medible — cumpliendo Principio III (features verificables) y Principio I (presupuestos de recursos) de la constitución."

## Clarifications

### Session 2026-11-22

- Q: ¿Contra qué debe compararse un dominio de la whitelist al decidir si se filtra una petición? → A: Por sitio visitado — si la página que el usuario está viendo pertenece al dominio exceptuado (con subdominios), ningún recurso de esa página se filtra.
- Q: ¿Qué deben contener `easylist.txt` y `easyprivacy.txt` en assets? → A: Listas completas — EasyList + EasyPrivacy íntegras, incluyendo atribución y texto de licencia en la app.
- Q: ¿Cómo gestiona el usuario la whitelist de dominios? → A: Toggle en navegación + lista — un control accesible mientras se navega exceptúa/rehabilita el sitio actual, y una sección en Settings muestra y permite quitar dominios exceptuados.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Bloqueo funcional con listas reales (Priority: P1)

Como usuario, al navegar sitios con publicidad, los dominios de anuncios y trackers
conocidos se bloquean antes de descargarse, reduciendo datos, tiempo de carga y
popups — comparable a lo que el README promete.

**Why this priority**: Hoy el adblock bloquea 3 dominios de ejemplo; es la brecha
más grande entre README y realidad. Es la feature diferenciadora del proyecto.

**Independent Test**: Empaquetar listas reales (EasyList + EasyPrivacy), navegar a
una página de prueba con dominios de ads conocidos (p. ej. `doubleclick.net`,
`google-analytics.com`) y verificar que las peticiones se cortan devolviendo
una respuesta vacía, medible con un contador de bloqueos en debug.

**Acceptance Scenarios**:

1. **Given** listas EasyList/EasyPrivacy reales empaquetadas en assets, **When** la
   página solicita un recurso de un dominio listado como `||dominio^`, **Then** la
   petición se bloquea (respuesta vacía) en < 1ms de decisión.
2. **Given** una regla de excepción `@@||dominio^`, **When** se solicita recurso de
   ese dominio, **Then** NO se bloquea.
3. **Given** líneas de comentario (`!`), cabeceras `[Adblock Plus ...]` y reglas
   cosméticas (`##selector`) en la lista, **When** se parsea, **Then** se ignoran
   sin error ni falso positivo.
4. **Given** un dominio no listado, **When** se solicita, **Then** pasa sin
   bloqueo (no hay falsos positivos en el set de prueba).

---

### User Story 2 - Carga de filtros sin congelar el arranque (Priority: P2)

Como usuario, la app arranca rápido aunque las listas de filtros tengan decenas de
miles de reglas.

**Why this priority**: `AdBlockManager.init()` hoy corre en el hilo principal dentro de
`Application.onCreate`. Con listas reales (~1-2MB, 60K+ líneas) el arranque se
congelaría — contradice el presupuesto de Home (≤60MB, arranque rápido).

**Independent Test**: Medir tiempo de `Application.onCreate` → primer frame de
HomeActivity con listas reales; el parseo corre en un hilo de fondo y el filtro
queda listo antes de la primera navegación real.

**Acceptance Scenarios**:

1. **Given** listas reales de ~60K reglas, **When** la app arranca, **Then** la
   HomeActivity se muestra sin ANR y el filtro termina de cargar en background.
2. **Given** una navegación iniciada antes de completar la carga, **When** se
   consulta si un recurso debe bloquearse, **Then** se recibe respuesta consistente
   (modo degradado "no bloquear aún" documentado, nunca crash ni bloqueo del hilo).

---

### User Story 3 - Whitelist por dominio para sitios rotos (Priority: P3)

Como usuario, si el bloqueo rompe una página que necesito (reproductor que carga
video desde dominio de ads, anti-adblock wall), puedo desactivar el filtrado para
ese sitio concreto sin apagarlo globalmente — con un control accesible mientras
navego y una lista en Settings para revisar/quitar excepciones.

**Why this priority**: Consecuencia esperada de un adblock real — sin escape hatch,
un falso positivo o un muro anti-adblock deja al usuario sin opción.

**Independent Test**: Marcar `ejemplo.com` como exceptuado → navegar a ese dominio
muestra que sus recursos pasan sin filtrar; otros dominios siguen bloqueados.

**Acceptance Scenarios**:

1. **Given** un dominio en whitelist, **When** el usuario navega una página de
   ese dominio (o subdominio suyo), **Then** no se bloquea ningún recurso de esa
   página.
2. **Given** whitelist persistida, **When** se reinicia la app, **Then** las
   excepciones siguen activas.
3. **Given** un dominio en whitelist, **When** el usuario lo quita de la lista
   (desde Settings o el control de navegación), **Then** el bloqueo se restablece
   en la siguiente navegación.

---

### Edge Cases

- Reglas con regex complejo (`/ads\d+/`), reglas de path (`||dom.com/banner/`),
  reglas de terceros (`$third-party`), wildcard (`*`) — definir cuáles se soportan
  en v1 y cuáles se ignoran explícitamente.
- Lista corrupta, truncada o con formato inesperado: parseo tolerante, nunca crash;
  línea inválida se ignora.
- Estructura de matching saturada: con ≥60K reglas un filtro probabilístico de
  tamaño fijo (p. ej. 64K bits) produce falsos positivos masivos → dimensionar la
  estructura según nº de reglas y tasa FP objetivo, o sustituir por una estructura
  exacta si el presupuesto de RAM lo permite mejor.
- Hosts con subdominios: `||ads.ejemplo.com^` debe bloquear `x.ads.ejemplo.com`
  (matching por sufijo de dominio, no solo host exacto).
- Actualización de listas: en v1 las listas van empaquetadas en assets; update
  remoto queda fuera de scope.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: El sistema MUST parsear sintaxis EasyList: reglas `||dominio^` →
  dominio bloqueado (con matching por sufijo de subdominio); excepciones `@@`;
  ignorar comentarios `!`, cabeceras `[...]`, cosméticas `##`/`#@#` y reglas con
  opciones no soportadas (`$...`).
- **FR-002**: `easylist.txt` y `easyprivacy.txt` en assets MUST contener las
  listas EasyList y EasyPrivacy completas, con su procedencia, atribución y
  texto de licencia incluidos en la app/README según exijan sus términos.
- **FR-003**: La carga y parseo de listas MUST ejecutarse fuera del hilo principal;
  la consulta de bloqueo MUST ser segura para llamada concurrente y definir
  comportamiento mientras la carga está en curso.
- **FR-004**: La estructura de matching MUST dimensionarse para ≥60.000 reglas con
  tasa de falsos positivos < 1% y decisión por petición < 1ms en hardware objetivo;
  si una estructura probabilística no cumple, sustituir por estructura exacta
  justificada.
- **FR-005**: MUST existir whitelist por dominio persistida, comparada contra el
  host de la página visitada (con coincidencia por subdominio) y consultada antes
  del filtro; un dominio exceptuado desactiva el filtrado de todos los recursos
  de esa página.
- **FR-006**: MUST existir contador de peticiones bloqueadas accesible en debug
  (base para medir efectividad real que el README reporte).
- **FR-007**: La lógica de decisión de bloqueo MUST ser una unidad testeable sin
  WebView (función pura sobre host/url), per Principio IV.
- **FR-008**: El usuario MUST poder exceptuar/rehabilitar el sitio actual desde
  un control accesible durante la navegación, y ver/quitar dominios exceptuados
  desde una sección en Settings.

### Key Entities *(include if feature involves data)*

- **FilterRule**: regla parseada. Atributos: tipo (domain-block | exception),
  dominio patrón, flags soportados. Origen: línea de lista.
- **FilterList**: conjunto cargado. Atributos: fuente (nombre de asset), nº de
  reglas aceptadas/ignoradas, estado de carga.
- **WhitelistedDomain**: dominio exceptuado por el usuario. Persistencia local,
  consultado antes del matching.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: ≥ 95% de los dominios de un set de prueba derivado de las propias
  listas son bloqueados correctamente; < 1% de falsos positivos sobre un set de
  dominios legítimos.
- **SC-002**: Decisión de bloqueo < 1ms por petición (medido en el dispositivo
  objetivo o emulador TV).
- **SC-003**: Arranque a primer frame de HomeActivity sin incremento perceptible
  respecto a build actual con listas dummy (carga en background verificada):
  el delta medido de `Application.onCreate` → primer frame entre el build con
  listas reales y el build actual con listas dummy MUST ser ≤ 200ms (o estar
  dentro del ruido de medición).
- **SC-004**: README actualizado con efectividad medida real (reemplaza el "~90%"
  aspiracional) y etiqueta de la feature según estado verificado.
- **SC-005**: Suite de tests unitarios del parser/matcher ejecutable con
  `./gradlew test` cubriendo: `||dom^`, `@@`, `!`, `##`, `[...]`, subdominios y
  líneas inválidas.

## Assumptions

- Las listas se empaquetan en `assets/`; actualización remota es trabajo futuro.
- v1 solo implementa reglas de dominio (`||`) y excepciones (`@@||`); reglas
  cosméticas, de path con patrón y opciones `$` quedan fuera y se ignoran
  explícitamente.
- El match se hace por host (con sufijos de subdominio), no por URL completa.
- EasyList/EasyPrivacy se redistribuyen completas bajo sus licencias
  (GPLv3 / CC BY-SA); la app incluye la atribución y el texto de licencia
  correspondiente.
