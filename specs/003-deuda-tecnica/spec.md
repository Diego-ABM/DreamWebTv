# Feature Specification: Deuda Técnica (Bugs, Higiene, Lifecycle, Tests Bootstrap)

**Feature Branch**: `003-deuda-tecnica`

**Created**: 2026-11-23

**Status**: Draft

**Input**: User description: "Saldar la deuda técnica restante del análisis de DreamWeb no cubierta por los specs 001-security-hardening ni 002-adblock-real: bugs de UX (historial con título nulo, botón de player externo perdido al salir de fullscreen, flag de reproducción latcheado, esquemas no-http caídos al WebView), higiene y background (dependencias sin uso, monitor de rendimiento perpetuo, intervalo JS perpetuo, lifecycle del WebView no reenviado), datos (doble conexión a la misma DB, sin borrado, sin rotación de historial), manifest/build (cleartext explícito, release sin optimización, strings del template, cursor D-pad) y bootstrap de tests unitarios — cumpliendo Principios I, IV y V de la constitución."

## Clarifications

### Session 2026-11-23

- Q: ¿El tráfico HTTP en claro se mantiene habilitado globalmente o se restringe por dominio? → A: Se mantiene habilitado globalmente (`usesCleartextTraffic="true"`) con su justificación documentada en el manifiesto; DreamWeb navega sitios arbitrarios e IPTV http legado y una restricción por dominio rompería dominios desconocidos.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Navegación sin glitches visibles (Priority: P1)

Como usuario del navegador en la TV, el historial muestra el título real de cada
página, el botón de "reproducir en player externo" vuelve a aparecer al salir de
pantalla completa, y los enlaces que apuntan a apps externas (correo, teléfono,
tienda, intents) abren la app correspondiente o muestran un aviso claro — nunca
una página de error dentro del navegador.

**Why this priority**: Son los defectos directamente visibles por el usuario en
cada sesión de navegación. El historial con entradas sin título y el botón que
desaparecen para siempre degradan funciones centrales del producto.

**Independent Test**: Navegar a una página → la entrada de historial guarda su
título final. Detectar un video → entrar y salir de fullscreen → el botón
externo sigue disponible. Pulsar un enlace `mailto:` en un dispositivo sin app
de correo → aviso al usuario; con app instalada → se abre la app.

**Acceptance Scenarios**:

1. **Given** una página cuyo título llega tras `onPageStarted`, **When** termina
   de cargar, **Then** el historial guarda la URL con su título definitivo, no
   nulo ni el de la página anterior.
2. **Given** varias cargas consecutivas de la misma URL (redirect, refresh),
   **When** se consulta el historial, **Then** existe una sola entrada
   consecutiva por URL.
3. **Given** un video detectado y el botón externo visible, **When** el usuario
   entra a fullscreen y sale, **Then** el botón vuelve a ser visible.
4. **Given** un video detectado y el botón externo visible, **When** el video
   detectado deja de estar disponible y el usuario sale de fullscreen, **Then**
   el botón no reaparece.
5. **Given** un enlace `mailto:`, `tel:`, `market:` o `intent://`, **When** el
   usuario lo activa, **Then** se intenta resolver con una app externa; si
   ninguna lo maneja, se muestra un aviso y el WebView no navega a ese esquema.
6. **Given** el reproductor nativo sin contexto válido (activity destruida),
   **When** se intenta lanzar reproducción, **Then** el estado interno no queda
   marcado como "reproduciendo" y un intento posterior con contexto válido
   funciona.

---

### User Story 2 - Cero trabajo en background sin justificación (Priority: P2)

Como usuario, la app no gasta CPU, batería ni memoria en tareas perpetuas que no
me aportan nada: el monitor de rendimiento solo corre bajo depuración, el sondeo
de detección de video se apaga cuando ya cumplió su función, y al pausar la app
el navegador realmente se pausa.

**Why this priority**: El Principio I exige apagado explícito de todo trabajo en
background y prohíbe logging continuo en producción. En hardware de ~2GB RAM,
intervalos perpetuos y logging por frame son deuda directa contra los
presupuestos del producto.

**Independent Test**: Build de release → ningún log periódico de jank/memoria en
logcat. Página con video → tras la detección, no queda intervalo JS repitiéndose.
Enviar la app a background → el WebView queda pausado; volver → reanuda.

**Acceptance Scenarios**:

1. **Given** build sin flag de depuración, **When** la app corre, **Then** el
   monitor de rendimiento no registra jank por frame ni memoria periódica, y no
   consume ciclos de monitorización.
2. **Given** flag de depuración activo, **When** el monitor corre, **Then**
   existe un método de parada que libera sus callbacks/recursos.
3. **Given** una página en la que el sniffer ya detectó un video, **When**
   transcurre el tiempo, **Then** no hay sondeo periódico ejecutándose (el
   mecanismo de detección continua restante es por eventos/mutaciones, no por
   intervalo fijo perpetuo).
4. **Given** la actividad del navegador visible, **When** pasa a pausa y vuelve,
   **Then** el WebView recibe la pausa/reanudación correspondiente y los
   temporizadores globales solo se congelan durante el offloading al reproductor
   nativo, restaurándose al volver.

---

### User Story 3 - Datos consistentes y controlables (Priority: P3)

Como usuario, puedo borrar entradas concretas del historial y de mis favoritos
desde la interfaz, y el historial no crece sin límite; como mantenedor, existe
una única conexión a la base de datos compartida por todos los managers.

**Why this priority**: Integridad de datos y control del usuario sobre su
información. La doble conexión a la misma base es una fuente de
inconsistencias/bloqueos; la falta de borrado viola la expectativa básica de un
navegador; un historial sin límite contradice el presupuesto de recursos.

**Independent Test**: Abrir historial/favoritos → borrar una entrada → no
reaparece. Insertar más entradas que el límite documentado → las más antiguas se
rotan. Inspección de la app → los managers de historial y favoritos comparten la
misma conexión.

**Acceptance Scenarios**:

1. **Given** entradas en el historial, **When** el usuario elimina una desde la
   UI, **Then** desaparece de la lista y de la persistencia; lo mismo aplica a
   favoritos.
2. **Given** el límite de historial documentado alcanzado, **When** se añade una
   entrada nueva, **Then** la más antigua se descarta automáticamente.
3. **Given** la app en ejecución, **When** historial y favoritos operan,
   **Then** ambos usan una única conexión a la base de datos provista por el
   contenedor de la aplicación.

---

### User Story 4 - Build de release honesto y artefactos limpios (Priority: P4)

Como mantenedor, el build de release sale optimizado y dentro del presupuesto de
tamaño, las dependencias declaradas corresponden a las realmente usadas, el
manifiesto declara explícitamente su política de tráfico en claro, y la UI solo
contiene recursos realmente usados (cursor propio, sin strings del template).

**Why this priority**: Menos visible para el usuario final pero determinante
para los presupuestos del Principio I (APK < 12MB) y la higiene declarada por la
constitución (dependencias explícitas, artefactos sin uso eliminados).

**Independent Test**: `./gradlew assembleRelease` → APK optimizado < 12MB.
Auditoría de `libs.versions.toml` → toda dependencia declarada tiene uso en
código. Navegación con D-pad mantenida → el cursor acelera progresivamente.

**Acceptance Scenarios**:

1. **Given** el build de release, **When** se compila, **Then** la optimización
   está activada, las clases expuestas a JavaScript y las APIs reflexivas del
   reproductor quedan protegidas por reglas de retención, y el APK resultante
   pesa menos de 12MB.
2. **Given** la navegación por D-pad, **When** el usuario mantiene pulsada una
   dirección, **Then** el cursor acelera de forma progresiva en lugar de moverse
   a velocidad fija.
3. **Given** los recursos empaquetados, **When** se auditan, **Then** no quedan
   strings ni recursos del template original sin uso, y el cursor virtual usa un
   asset propio del proyecto.
4. **Given** el manifiesto, **When** se audita, **Then** la política de tráfico
   en claro está declarada explícitamente con su justificación.
5. **Given** el catálogo de dependencias, **When** se audita, **Then** cada
   dependencia declarada tiene uso real en el código y toda dependencia usada
   está declarada explícitamente (sin dependencias solo transitivas).

---

### User Story 5 - Lógica crítica con tests automatizados (Priority: P5)

Como mantenedor, la lógica determinista de alto riesgo que exige el Principio IV
queda cubierta por tests unitarios ejecutables sin dispositivo, y el proyecto
compila y pasa tests de forma verificable.

**Why this priority**: El bootstrap de tests ya existe en `app/src/test/` para
parte de la lógica (detección de video, payload, filtros); este item completa la
cobertura mínima restante (managers de datos SQLite) y blinda las
transformaciones de este spec contra regresiones.

**Independent Test**: `./gradlew test` se ejecuta sin dispositivo y pasa,
incluyendo tests de la decisión del sniffer, del parser del payload
`url|REFERER|UA|COOKIES`, del matcher de filtros y de los managers SQLite.

**Acceptance Scenarios**:

1. **Given** el código tras los cambios de este spec, **When** se ejecuta
   `./gradlew test`, **Then** pasan tests que verifican: detección de video
   (m3u8 detectado, `.ts` y dominios de ads excluidos, prioridad m3u8 > mp4),
   parseo del payload `url|REFERER|UA|COOKIES`, matching de filtros y
   operaciones de los managers de datos sobre una base de datos de prueba.
2. **Given** un refactor de la lógica de deduplicación de historial o del
   parseo de esquemas externos, **When** existe lógica determinista nueva,
   **Then** viene acompañada de su test o de una justificación explícita.

---

### Edge Cases

- Página tipo SPA que cambia la URL o el título varias veces sin recarga
  completa: ¿qué cuenta como entrada de historial? (Regla razonable: una
  entrada por URL cargada, con el último título recibido antes de abandonarla;
  deduplicación solo entre entradas consecutivas idénticas.)
- Enlace `intent://` con URI malformado o sin app que lo resuelva: aviso al
  usuario y fallback documentado (p. ej. URL de respaldo incluida en el propio
  intent cuando exista).
- El video detectado deja de ser válido mientras el usuario está en fullscreen:
  al salir, el botón solo se restaura si sigue habiendo un video detectado
  vigente.
- Reproducción solicitada justo cuando la activity se está destruyendo: sin
  estado latcheado ni crash; el intento se descarta limpiamente.
- Borrado de la entrada de historial correspondiente a la página abierta
  actualmente: la UI se mantiene consistente sin afectar la navegación en
  curso.
- Base de datos corrupta o con esquema antiguo en la migración a conexión
  única: migración conservadora sin pérdida de datos existentes.
- Reglas de retención insuficientes en release: interfaz JS o player que deja
  de funcionar solo en el build optimizado — verificación manual obligada del
  build release.

## Requirements *(mandatory)*

### Functional Requirements

**Corrección de bugs de UX**

- **FR-001**: El registro en historial MUST producirse cuando la página termina
  de cargar o cuando se recibe su título, de modo que cada entrada guarde la
  URL con su título definitivo; entradas consecutivas de la misma URL MUST
  deduplicarse.
- **FR-002**: Al salir de pantalla completa, el botón de reproductor externo
  MUST restaurarse si el video detectado sigue vigente; si ya no lo está,
  permanece oculto.
- **FR-003**: El estado "reproduciendo" del gestor de video MUST asignarse solo
  tras validar que existe contexto válido; un intento fallido MUST dejar el
  estado desmarcado y reintentable.
- **FR-004**: Los enlaces con esquemas ajenos a la web (`intent:`, `mailto:`,
  `tel:`, `market:` y equivalentes) MUST intentarse resolver mediante apps
  externas; sin resolvedor disponible, MUST mostrarse un aviso al usuario y la
  navegación del WebView MUST cancelarse.

**Higiene de dependencias y trabajo en background**

- **FR-005**: El monitor de rendimiento MUST permanecer inactivo salvo flag de
  depuración, y MUST exponer un apagado que libere sus callbacks; el logging
  continuo en producción queda prohibido.
- **FR-006**: El sondeo periódico de detección de video MUST cesar una vez
  detectado un video (o ser sustituido por detección basada en eventos); no se
  permiten intervalos JS perpetuos sin condición de apagado.
- **FR-007**: La actividad del navegador MUST reenviar pausa, reanudación y
  destrucción al WebView, coordinado con el gestor de video; la congelación
  global de temporizadores MUST limitarse al patrón de offloading al
  reproductor nativo.
- **FR-008**: Toda dependencia usada MUST estar declarada explícitamente en el
  catálogo de versiones; toda dependencia declarada sin uso en código MUST
  eliminarse (manteniendo la declaración de capacidad de hardware TV en el
  manifiesto, que no depende de la librería).

**Datos**

- **FR-009**: Debe existir una única conexión/base de datos compartida, servida
  desde el contenedor de la aplicación, consumida por los managers de historial
  y favoritos.
- **FR-010**: El usuario MUST poder borrar entradas individuales del historial
  y de los favoritos desde la UI correspondiente.
- **FR-011**: El historial MUST tener un límite con rotación (las entradas más
  antiguas se descartan al superarlo); el límite y la política MUST quedar
  documentados.

**Manifest, build y recursos**

- **FR-012**: La política de tráfico en claro MUST estar declarada
  explícitamente en el manifiesto junto a su justificación (streaming http
  legado); su alcance es global (habilitado para todo dominio), decisión
  registrada en Clarifications.
- **FR-013**: El build de release MUST tener la optimización de código
  activada, con reglas de retención que protejan las interfaces expuestas a
  JavaScript y las APIs reflexivas del reproductor; el APK resultante MUST
  cumplir el presupuesto < 12MB.
- **FR-014**: Los recursos/strings heredados del template sin uso MUST
  eliminarse; el cursor virtual MUST usar un asset propio del proyecto.
- **FR-015**: El cursor por D-pad MUST acelerar progresivamente mientras la
  dirección se mantiene pulsada.

**Tests (Principio IV)**

- **FR-016**: La lógica determinista crítica MUST cubrirse con tests unitarios
  ejecutables con `./gradlew test` sin dispositivo: decisión del sniffer,
  parser del payload `url|REFERER|UA|COOKIES`, matcher de filtros y managers
  de datos SQLite.
- **FR-017**: Toda lógica determinista nueva introducida por este spec MUST
  venir con su test unitario o con justificación explícita de por qué no es
  testeable.

### Key Entities *(include if feature involves data)*

- **HistoryEntry**: entrada del historial. Atributos: URL, título definitivo de
  la página, marca temporal. Sujeta a deduplicación consecutiva por URL y a
  rotación por límite documentado. Soporta borrado individual.
- **Bookmark**: entrada de favoritos. Atributos: URL, título. Soporta borrado
  individual.
- **DatabaseConnection**: acceso único compartido a la persistencia local,
  provisto por el contenedor de la aplicación y consumido por los managers.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100% de las entradas nuevas del historial registran el título
  definitivo de la página (0 entradas con título nulo o de la página anterior);
  N cargas consecutivas de la misma URL producen 1 sola entrada.
- **SC-002**: En 10 ciclos de entrar/salir de fullscreen con video detectado
  vigente, el botón de reproductor externo reaparece las 10 veces.
- **SC-003**: Un intento de reproducción con contexto inválido seguido de uno
  con contexto válido siempre permite reproducir (0 estados latcheados).
- **SC-004**: 100% de los enlaces con esquema externo probados (`mailto:`,
  `tel:`, `market:`, `intent:`) se resuelven con app externa o muestran aviso;
  0 fallos silenciosos dentro del WebView.
- **SC-005**: En build de producción, 0 mensajes periódicos de
  jank/memoria en logcat durante 5 minutos de uso y 0 intervalos de sondeo
  activos tras la detección de un video.
- **SC-006**: Una sola conexión a la base de datos en toda la vida de la app;
  borrado individual funcional en historial y favoritos; historial acotado al
  límite documentado.
- **SC-007**: APK de release optimizado < 12MB verificable con el build de
  release; auditoría de dependencias sin entradas huérfanas ni dependencias
  solo transitivas en uso.
- **SC-008**: `./gradlew assembleDebug` compila y `./gradlew test` pasa con la
  cobertura mínima del Principio IV (sniffer, payload, matcher, managers
  SQLite) ejecutándose sin dispositivo.

## Assumptions

- La política de tráfico en claro queda habilitada globalmente con
  justificación documentada en el manifiesto (decisión del dueño registrada en
  Clarifications); no se introduce `networkSecurityConfig` por dominio.
- Límite de historial por defecto propuesto: 500 entradas con rotación FIFO de
  las más antiguas; valor final a fijar y documentar en planificación.
- El bootstrap de tests (`app/src/test/`) y parte de la cobertura ya existen
  tras los specs 001/002; este spec exige completar la cobertura mínima
  restante (notablemente managers de datos), no rehacer lo existente.
- La dependencia de corrutinas para Android ya está declarada explícitamente;
  el requisito de higiene se centra en eliminar las declaradas sin uso (p. ej.
  librería de carga de imágenes y la librería de UI para TV del template),
  manteniendo la feature de hardware TV declarada en el manifiesto.
- Detección de esquemas externos: el comportamiento por defecto es intentar la
  resolución con el sistema y avisar al usuario si nadie la resuelve; `intent:`
  puede usar su URL de fallback interna cuando exista.
- Alcance de borrado v1: borrado individual por entrada (no borrado masivo por
  dominio ni "borrar todo"), salvo que la planificación decida añadirlo.
- El cursor D-pad ya existe; el cambio es añadir aceleración por pulsación
  mantenida, no rediseñar la navegación.
