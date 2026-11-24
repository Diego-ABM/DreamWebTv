# Feature Specification: Captura y Reproducción de Video Web

**Feature Branch**: `004-video-capture-playback`

**Created**: 2026-11-23

**Status**: Implemented — manual TV validation (quickstart E1a–E6) pending

**Input**: User description: "Captura y reproducción de video para DreamWeb (navegador Android TV): detectar streams de video en las páginas lo antes posible con un hallazgo estructurado por video (URL, título, tipo/formato, resolución, duración, headers/cookies de contexto), presentar los videos detectados al usuario y lanzar su reproducción en un reproductor dedicado full-screen propio que reciba el contexto completo (headers arbitrarios + referer/origen + cookies + subtítulos), liberar el motor de navegación mientras el player está activo y restaurar la navegación exacta al volver, agrupar rendiciones para selección de calidad con preferencia HLS, y soportar subtítulos detectados en la página."

## Clarifications

### Session 2026-11-23

- Q: ¿Cómo se presentan los videos detectados sin interrumpir la navegación? →
  A: Indicador no intrusivo visible cuando existe al menos un hallazgo; la
  lista completa se abre bajo demanda del usuario en un panel navegable con
  D-pad. Nada de diálogos automáticos ni bloqueos de la página.
- Q: ¿La reproducción arranca sola al detectarse un video o solo por acción
  del usuario? → A: Siempre manual — el lanzamiento es una acción del usuario
  desde el panel de hallazgos; ningún video detectado se auto-reproduce.
- Q: Cuando la página no expone duración ni resolución, ¿se permite una
  petición de sondeo al stream para completarlas? → A: Sí, una única petición
  acotada por hallazgo como máximo; nunca bloquea la presentación del
  hallazgo y se cancela si el usuario navega a otra página.
- Q: ¿La preferencia de calidad del usuario se recuerda entre sesiones? →
  A: Solo dentro de la misma sesión; no se persiste en disco.
- Q: ¿El reproductor retoma la posición donde el usuario iba en la página? →
  A: Sí — el hallazgo incluye la posición de reproducción del medio cuando es
  observable y el player la usa como punto de inicio; si no se conoce, inicia
  desde el principio.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Descubrir el video de la página y lanzarlo al reproductor (Priority: P1)

Como usuario de DreamWeb en mi TV, quiero que mientras navego una página con
video el navegador lo detecte lo antes posible —con sus datos: título,
formato, resolución, duración— me lo presente y me deje lanzarlo en el
reproductor nativo a pantalla completa, sin depender de que el reproductor
embebido de la página funcione bien con el mando.

**Why this priority**: Es el bucle central de la feature y su principal
propuesta de valor: la detección proactiva
convierte cualquier página con video en reproducible con el player nativo
optimizado para TV. Sin detección + presentación + lanzamiento no hay valor
para el resto de historias.

**Independent Test**: Navegar a una página con un stream accesible → el video
aparece presentado como hallazgo con sus metadatos → al lanzarlo, se
reproduce en el reproductor propio a pantalla completa.

**Acceptance Scenarios**:

1. **Given** el usuario navega a una página cuyo video está declarado en el
   HTML inicial, **When** la página termina de cargar, **Then** ya existe al
   menos un hallazgo de video presentado con su URL, título y formato.
2. **Given** una página que crea su elemento de video dinámicamente (sin
   video en el HTML inicial), **When** el stream empieza a existir o a
   solicitarse, **Then** el hallazgo aparece sin requerir recargar la página.
3. **Given** al menos un video detectado en la página actual, **When** el
   usuario lo selecciona para reproducir, **Then** se abre el reproductor
   dedicado a pantalla completa por encima de la navegación y el video
   empieza a reproducirse.
4. **Given** una página sin contenido de video, **When** el usuario navega,
   **Then** no se presenta ningún hallazgo (sin falsos positivos de
   publicidad ni trackers).
5. **Given** una página con varios videos distintos, **When** se detectan,
   **Then** todos se presentan y el usuario puede elegir cuál reproducir.
6. **Given** al menos un video detectado en la página actual, **When** el
   hallazgo existe, **Then** se muestra un indicador no intrusivo; la lista
   de hallazgos solo aparece cuando el usuario la abre —sin diálogo
   automático ni lanzamiento automático—.

---

### User Story 2 - Reproducción fiel al contexto de la página (Priority: P2)

Como usuario, quiero que el reproductor reciba el contexto completo con el
que la página accedía al stream —headers arbitrarios, referer/origen, cookies
de sesión— para que los videos que solo cargan "dentro de la página" también
se reproduzcan en el reproductor nativo.

**Why this priority**: Gran parte de los streams de la web real rechazan una
URL "desnuda" (403, 401, validación de referer, tokens de sesión). Sin
fidelidad de contexto la detección funciona pero la reproducción falla en los
sitios que más importan.

**Independent Test**: Un stream que responde 403 sin el header adecuado y 200
con él → reproduce en el player; un stream que exige cookies de sesión de la
página → reproduce con esa sesión.

**Acceptance Scenarios**:

1. **Given** un video detectado cuyo stream exige un referer/origen
   específico, **When** el usuario lo lanza, **Then** el reproductor envía
   esos datos de origen y el stream reproduce (no devuelve acceso denegado).
2. **Given** un video detectado en una página con sesión basada en cookies,
   **When** se lanza al reproductor, **Then** las cookies de la página
   acompañan las peticiones del stream.
3. **Given** un hallazgo cuyo contexto incluye headers arbitrarios capturados
   en la página (más allá de referer y cookies), **When** llega al
   reproductor, **Then** todos los headers capturados se aplican a las
   peticiones del stream, incluidos manifiestos y segmentos subsecuentes.
4. **Given** un stream servido bajo un certificado no confiable, **When**
   falla la validación de seguridad, **Then** se ofrece al usuario la
   decisión de excepción por dominio y nunca se acepta el certificado de
   forma automática ni global.

---

### User Story 3 - La navegación queda liberada y se restaura al volver (Priority: P2)

Como usuario con una TV de memoria limitada, quiero que mientras veo el video
la página web no consuma recursos, y que al volver del reproductor la
navegación esté exactamente donde la dejé —misma página, misma posición— para
no perder mi sitio.

**Why this priority**: Es la razón de ser del offloading a player nativo y un
principio constitucional del proyecto (economía de RAM). Hoy la navegación ya
se suspende, pero la restauración "exacta" de posición/estado no está
garantizada; el valor nuevo es salir y volver sin coste ni pérdida.

**Independent Test**: Con el player activo, el consumo de RAM de la app se
mantiene dentro del presupuesto; al pulsar atrás, la página reaparece en el
mismo estado y posición en que se dejó.

**Acceptance Scenarios**:

1. **Given** el usuario está viendo un video en el reproductor dedicado,
   **When** el player está activo, **Then** el motor de navegación está
   suspendido (no renderiza ni ejecuta temporizadores) y la RAM total de la
   app se mantiene dentro del presupuesto de reproducción.
2. **Given** el player activo, **When** el usuario pulsa atrás/salir, **Then**
   vuelve a la misma página, en la misma posición y estado en que la dejó,
   sin recarga completa cuando sea evitable.
3. **Given** que el sistema destruyó el proceso del navegador mientras el
   player estaba activo, **When** el usuario vuelve, **Then** se restaura al
   menos la última URL navegada —degradación aceptable y documentada, nunca
   crash ni pantalla en blanco—.

---

### User Story 4 - Elegir la calidad entre las rendiciones del video (Priority: P2)

Como usuario, quiero que cuando la página ofrezca el mismo video en varias
calidades o formatos se me presenten agrupadas y pueda elegir la resolución
—con preferencia por el formato de streaming adaptativo (HLS) cuando el mismo
contenido existe en varios formatos— para aprovechar mi conexión y pantalla.

**Why this priority**: Las páginas de streaming reales exponen el mismo
contenido como mp4 en varias resoluciones y/o un manifiesto adaptativo. Sin
agrupación el usuario ve URLs duplicadas sin sentido; con selección gana
control real sobre calidad y consumo de datos.

**Independent Test**: Una página que expone el mismo video como mp4 480p/720p
y como manifiesto HLS → el selector muestra las calidades agrupadas (una
entrada por resolución) y la elección se aplica al reproducir.

**Acceptance Scenarios**:

1. **Given** un video con varias rendiciones detectadas, **When** se presenta
   al usuario, **Then** las opciones aparecen agrupadas por resolución —una
   entrada por calidad, no una por URL cruda—.
2. **Given** el mismo contenido disponible como mp4 y como manifiesto HLS a
   la misma resolución, **When** se ofrecen las opciones, **Then** la
   rendición HLS es la propuesta preferida por defecto.
3. **Given** varias calidades disponibles, **When** el usuario elige una y
   reproduce, **Then** el stream reproducido corresponde a la calidad
   seleccionada.
4. **Given** un video con una sola rendición, **When** se presenta, **Then**
   no se interrumpe al usuario con un selector de una única opción.
5. **Given** que el usuario ya eligió una calidad en esta sesión, **When**
   lanza otro video con las mismas calidades, **Then** su última elección se
   propone como opción por defecto.

---

### User Story 5 - Subtítulos de la página disponibles en el reproductor (Priority: P3)

Como usuario, quiero que los subtítulos que la página declara para el video
lleguen al reproductor nativo y pueda activarlos, para no perder
accesibilidad al sacar el video de la página.

**Why this priority**: Completa la fidelidad "igual que en la página". Es P3
porque su ausencia no bloquea la reproducción, pero en contenido extranjero o
para accesibilidad es significativo.

**Independent Test**: Una página con pistas de subtítulos declaradas → en el
reproductor nativo aparecen como pistas seleccionables y se muestran
correctamente sobre el video.

**Acceptance Scenarios**:

1. **Given** un video detectado cuya página declara subtítulos, **When** se
   lanza al reproductor, **Then** las pistas detectadas están disponibles
   para selección en el player.
2. **Given** un video sin subtítulos declarados, **When** se reproduce,
   **Then** la reproducción funciona sin ofrecer pistas vacías ni provocar
   errores.
3. **Given** una pista de subtítulos en formato no soportado por el
   reproductor, **When** se procesa el hallazgo, **Then** esa pista se omite
   sin afectar la reproducción del video ni el resto de pistas.

---

### Edge Cases

- Página con varios videos distintos (listado con varios reproductores):
  todos se detectan y se presentan; el usuario elige cuál reproducir.
- El mismo video reportado por varias vías (red, etiqueta de video, player
  embebido): se consolida en un único hallazgo, no se duplica en la
  presentación.
- URL de stream firmada/temporal que caduca entre la detección y el
  lanzamiento: el fallo se comunica con mensaje claro y la navegación queda
  intacta para reintentar tras recargar.
- Segmentos sueltos (.ts), trackers y URLs de publicidad observados durante
  la carga: nunca se presentan como hallazgo de video.
- Navegación a otra página con hallazgos pendientes: los hallazgos
  anteriores se descartan y se sustituyen por los de la página actual.
- Página que muta la fuente de su video (tras un anuncio o una cuenta
  atrás): el hallazgo se actualiza al stream vigente.
- Hallazgo sin metadatos resolubles (sin duración ni resolución): se presenta
  igualmente con los campos disponibles; la ausencia de un dato no bloquea
  la reproducción.
- Rendiciones sin resolución declarable: se agrupan bajo la etiqueta
  genérica "Auto" en lugar de descartarse.
- Stream que solo reproduce dentro del reproductor embebido por DRM
  comercial: fuera de alcance; el intento degrada con mensaje claro, nunca
  con crash.
- Fallo al lanzar el reproductor (estado no válido): la navegación se
  reactiva y el usuario puede reintentar, sin quedar en estado suspendido.

## Requirements *(mandatory)*

### Functional Requirements

#### Detección

- **FR-001**: El sistema MUST detectar streams de video presentes en las
  páginas durante la navegación —etiquetas de video, reproductores embebidos
  y URLs de medios directas— lo antes posible en el ciclo de vida de la
  página, sin esperar al fin de carga cuando el medio ya es observable, y
  continuar detectando medios que aparezcan mientras la página esté activa.
- **FR-002**: Cada video detectado MUST producir un hallazgo estructurado que
  incluya: URL del stream, URL de la página origen, título (de página o de
  medio), tipo/formato del stream, resolución, duración, posición de
  reproducción actual del medio cuando sea observable, y el contexto de
  petición capturado (headers y cookies). Los campos no resolubles pueden
  quedar vacíos sin invalidar el hallazgo. Cuando duración o resolución no
  sean observables, el sistema MAY emitir una única petición acotada de
  sondeo al stream para completarlas —nunca bloqueando la presentación y
  cancelada si el usuario navega a otra página—.
- **FR-003**: El sistema MUST validar y filtrar los candidatos detectados:
  solo esquemas http/https, con descarte de segmentos sueltos, publicidad y
  dominios de tracking.
- **FR-004**: El sistema MUST consolidar hallazgos duplicados —el mismo video
  reportado por varias vías— en una única entrada presentable, y mantener los
  hallazgos acotados a la página actual: una navegación de marco principal
  invalida los hallazgos previos.
- **FR-005**: El sistema MUST actualizar un hallazgo cuando la página cambia
  la fuente del medio detectado (el hallazgo refleja el stream vigente).

#### Presentación

- **FR-006**: El sistema MUST presentar los videos detectados de la página
  actual mediante un indicador no intrusivo visible mientras exista al menos
  un hallazgo, más una lista completa —con metadatos disponibles: título,
  formato, resolución, duración— que el usuario abre bajo demanda en un panel
  navegable con el mando/D-pad. PROHIBIDO mostrar diálogos automáticos,
  bloquear la página ni auto-reproducir al detectar.
- **FR-007**: El usuario MUST poder lanzar la reproducción de cualquiera de
  los videos detectados presentados; el lanzamiento es siempre una acción
  explícita del usuario desde el panel de hallazgos.

#### Reproducción

- **FR-008**: La reproducción MUST ocurrir en un reproductor dedicado propio
  de la app, a pantalla completa y montado sobre la navegación (no dentro de
  la página).
- **FR-009**: El reproductor MUST recibir el contexto completo del hallazgo
  —URL del stream, headers arbitrarios capturados, URL/referer de la página
  origen, cookies cuando existan, posición de reproducción del medio cuando
  sea observable y subtítulos detectados— y aplicarlo a todas las peticiones
  del stream (manifiesto y segmentos subsecuentes); cuando exista posición de
  origen, la reproducción inicia desde ella.
- **FR-010**: La validación TLS del canal de reproducción MUST mantenerse
  íntegra: prohibido aceptar todo certificado de forma global; las
  excepciones siguen siendo por dominio, con consentimiento explícito del
  usuario en tiempo de ejecución y revocables.
- **FR-011**: Mientras el reproductor esté activo, el motor de navegación
  MUST permanecer suspendido para liberar memoria; al volver, la navegación
  MUST restaurarse a la misma página y posición, degradando a la última URL
  conocida solo si el sistema destruyó el estado.
- **FR-012**: Un fallo de reproducción (URL caducada, acceso denegado, error
  de red) MUST comunicarse con un mensaje claro y devolver al usuario a la
  navegación intacta.
- **FR-013**: Ninguna URL ni metadato de video detectado MUST enviarse a un
  backend o servicio externo: toda la detección ocurre y permanece en el
  dispositivo.
- **FR-014**: El reproductor MUST permanecer interno a la app: no se expone a
  otras aplicaciones ni se declara invocable desde fuera.
- **FR-015**: La feature MUST NOT introducir anuncios ni monetización en el
  flujo de detección o de reproducción.

#### Calidad / rendiciones

- **FR-016**: Cuando la página expone varias rendiciones del mismo video, el
  sistema MUST agruparlas por resolución y ofrecer selección de calidad como
  parte del lanzamiento.
- **FR-017**: Cuando el mismo contenido existe en varios formatos a la misma
  calidad, el sistema MUST preferir la rendición de streaming adaptativo
  (HLS/m3u8) como propuesta por defecto.
- **FR-018**: La última calidad elegida por el usuario SHOULD proponerse como
  opción por defecto en la siguiente reproducción de la misma sesión; la
  preferencia vive solo en memoria de sesión y MUST NOT persistirse en disco.

#### Subtítulos

- **FR-019**: El sistema MUST detectar los subtítulos que la página declara
  para el video y entregarlos al reproductor como pistas seleccionables.
- **FR-020**: Las pistas de subtítulos en formatos no soportados MUST
  omitirse sin afectar la reproducción del video ni el resto de pistas.

### Key Entities

- **Hallazgo de video**: representación de un stream detectado en la página
  actual. Atributos: URL del medio, URL de la página origen, título (de
  página o de medio), tipo/formato, resolución, duración, posición de
  reproducción actual del medio, headers de contexto, cookies y subtítulos
  asociados. Puede agrupar varias rendiciones.
- **Rendición**: variante del mismo video a una resolución y formato
  determinados; miembro del grupo de calidades de un hallazgo.
- **Contexto de reproducción**: conjunto transportado al reproductor —
  URL + headers + referer/origen + cookies + subtítulos + posición de
  reproducción — suficiente para que el stream se comporte como si lo
  pidiera la propia página y retome donde el usuario iba.
- **Pista de subtítulos**: pista declarada por la página, con URL del
  archivo, formato e idioma/etiqueta cuando existan.
- **Sesión de reproducción**: estado suspendido del navegador junto con el
  punto de restauración (página y posición) para el regreso del reproductor.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: En el conjunto de páginas de prueba con video, el 100% de los
  videos declarados en el HTML inicial aparecen como hallazgo a más tardar
  cuando la página termina de cargar; los medios creados dinámicamente se
  detectan en ≤2 segundos desde que el stream empieza a solicitarse.
- **SC-002**: En páginas de prueba con streams que exigen contexto (referer,
  headers o cookies obligatorios), ≥95% reproducen correctamente en el
  reproductor dedicado —paridad con reproducir dentro de la página—.
- **SC-003**: El 100% de los retornos desde el reproductor restauran la misma
  página y posición cuando el proceso del navegador sobrevivió; cuando no
  sobrevivió, siempre se restaura al menos la última URL navegada (0 crashes
  ni pantallas en blanco).
- **SC-004**: La memoria total de la app durante la reproducción se mantiene
  ≤180 MB en el hardware objetivo (presupuesto vigente del proyecto).
- **SC-005**: En páginas con múltiples rendiciones, el usuario ve las
  calidades agrupadas (una opción por resolución) y la calidad elegida es la
  que se reproduce en el 100% de los casos.
- **SC-006**: Cero hallazgos presentados correspondientes a URLs de
  publicidad, trackers o segmentos sueltos en el conjunto de prueba.
- **SC-007**: Las pistas de subtítulos declaradas por la página aparecen
  seleccionables en el reproductor en el 100% de los casos de prueba que las
  incluyen.
- **SC-008**: Cero peticiones salientes de la app hacia servidores ajenos al
  sitio navegado que transporten URLs o metadatos de videos detectados
  (verificable por inspección del tráfico de la app).

## Assumptions

- El reproductor nativo dedicado ya existe y reproduce streaming adaptativo y
  progresivo; esta feature amplía el contexto que recibe y la detección que
  lo alimenta —no se reemplaza el motor de reproducción.
- La suspensión del motor de navegación durante la reproducción ya existe;
  esta feature la mantiene y exige además la restauración exacta (página y
  posición) al volver.
- El usuario interactúa con mando/D-pad de TV; la presentación de hallazgos y
  el selector de calidad deben ser operables sin pantalla táctil.
- Las páginas objetivo sirven streams reproducibles por el player nativo; el
  contenido con DRM comercial de licencia propietaria queda fuera de alcance
  y se degrada con mensaje claro, nunca con crash.
- Las URLs de stream firmadas temporalmente pueden caducar entre la
  detección y el lanzamiento; se asume que el usuario puede reintentar tras
  recargar la página.
- El contexto de petición se captura con el alcance que la página expone al
  navegador; headers que ni la propia página puede observar no forman parte
  del hallazgo.
- Exclusiones explícitas de alcance (registradas para futuras decisiones, no
  implementables en esta feature): telemetría de URLs detectadas hacia un
  backend, suite IPTV/Xtream, inserción de anuncios en el player y exposición
  del reproductor a otras aplicaciones.

## Dependencies

- Reutilización obligada de los componentes existentes del proyecto de
  detección de video, validación de URLs, lanzamiento, reproducción nativa y
  seguridad del canal (incluida la confianza TLS por dominio y las
  excepciones revocables): se amplían, no se duplican ni se reemplazan.
- El diseño toma como contexto motivador un conjunto de técnicas objetivo:
  detección en fase temprana del documento con hallazgo estructurado,
  contexto por video como mapa de headers + subtítulos, player dedicado
  embebido —paridad parcial ya existente—, agrupación por resolución con
  preferencia HLS y detección de subtítulos del DOM. Son referencia de
  comportamiento, no diseño prescriptivo.
- La constitución del proyecto (.specify/memory/constitution.md) fija las
  restricciones duras que esta feature respeta: presupuestos de RAM/APK, TLS
  verificable con excepciones solo por dominio, tests en la lógica crítica,
  ownership simétrico de recursos y prohibición de trabajo en background sin
  justificación.
