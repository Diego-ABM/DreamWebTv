# Quickstart: AdBlock Real

Guía de validación end-to-end de la feature `002-adblock-real`. Requisitos:
JDK 17, Android SDK, repo en la rama `002-adblock-real`.

## 0. Preparar los assets (una vez)

Las listas reales aún no están en el repo (los assets actuales son dummies de
4 líneas). Obtenerlas antes de implementar:

```bash
mkdir -p app/src/main/assets/filters
curl -L https://easylist.to/easylist/easylist.txt      -o app/src/main/assets/filters/easylist.txt
curl -L https://easylist.to/easylist/easyprivacy.txt   -o app/src/main/assets/filters/easyprivacy.txt
# + escribir NOTICE.txt (procedencia, fecha) y LICENSE.GPLv3.txt — ver research.md R6
rm app/src/main/assets/easylist.txt app/src/main/assets/easyprivacy.txt   # dummies
```

Verificación: `wc -l app/src/main/assets/filters/*.txt` → decenas de miles de
líneas, no 4.

## 1. Tests unitarios (Principio IV, SC-005)

```bash
./gradlew test
```

Esperado — nuevas suites en `app/src/test/.../adblock/`:

- `FilterParserTest`: cada fila de la tabla de `contracts/easylist-subset.md` §5
  (`||d^`, `@@||d^`, `!`, `[`, `##`/`#@#`, `$`, `*`, regex, path, inválidas) +
  `FilterStats` coherente (aceptadas+ignoradas = líneas leídas).
- `DomainMatcherTest`: host exacto, subdominio por sufijo, no-match,
  precedencia `@@`, sufijos de TLD no explotan.
- `AdBlockWhitelistMatcherTest`: página en dominio exceptuado y subdominios;
  dominio no exceptuado sigue filtrando.

Todos verdes = parser + matcher correctos sin dispositivo (FR-007).

## 2. Compilación y presupuestos (Principio I)

```bash
./gradlew assembleDebug assembleRelease
ls -la app/build/outputs/apk/release/*.apk    # < 12MB — impacto listas ~+1MB
```

## 3. Validación en emulador/dispositivo TV

```bash
./gradlew installDebug
adb logcat -s DreamWebAdBlock PerformanceMonitor
```

**Escenario A — arranque (US2/SC-003)**: arranque en frío → HomeActivity se
muestra sin congelación; el motor termina de cargar en background
(log `DreamWebAdBlock: engine ready, N blocked / M exceptions`).

**Escenario B — bloqueo real (US1/SC-001/SC-002)**:

1. En Home, navegar a un sitio con publicidad conocida (p. ej. un periódico
   online) o a una página que incruste `doubleclick.net`/`googlesyndication.com`.
2. En build DEBUG el overlay `adBlockCounterText` muestra el contador crecer;
   logcat `DreamWebAdBlock` lista cada bloqueo.
3. Comprobar en logcat que el contador sube solo con dominios de las listas.

**Escenario C — whitelist en navegación (US3/FR-008)**:

1. Navegar a `ejemplo.com` (o el sitio que se rompa).
2. D-pad → botón de escudo `adBlockToggleButton` → Toast "Ads permitidos en
   ejemplo.com" → la página recarga sin filtrar (contador deja de subir).
3. Repetir el toggle → "Ads bloqueados en ejemplo.com" → reload.

**Escenario D — whitelist en Settings (US3 escenarios 2-3)**:

1. Home → "Seguridad" → sección "AdBlock: dominios exceptuados" lista
   `ejemplo.com` con fecha.
2. Pulsar el ítem → confirmar revocación → desaparece de la lista.
3. Reiniciar la app con una excepción activa → sigue listada (persistencia).

**Escenario E — RAM Home (SC-003/Principio I)**: en Home tras arranque,
`adb shell dumpsys meminfo com.example.dreamweb | grep TOTAL` → PSS ≤ 60MB
(el motor añade ~5-7MB sobre la base actual — medir delta real).

## 4. Medición de efectividad para README (SC-001/SC-002/SC-004)

1. Muestrear ~200 dominios del set de reglas ya parseadas por `FilterParser`
   (más fiel; si se usa grep sobre el asset, la regex debe cubrir también las
   reglas `||dominio` sin `^` aceptadas por el contrato:
   `grep -oP '^\|\|[a-z0-9.-]+(?=\^?$)' filters/easylist.txt | shuf -n 200`)
   y verificar con el matcher que ≥95% devuelven bloqueo (test o script JVM).
2. Probar ~100 dominios legítimos (top sites) → 0 bloqueados (FP <1% objetivo;
   con estructura exacta es 0 por construcción salvo que la lista los contenga).
3. Micro-benchmark de latencia de `isBlocked`/`shouldBlock` (SC-002): 10.000
   consultas sobre hosts aleatorios del corpus en dispositivo/emulador →
   reportar p50/p99; exigir p99 < 1ms.
4. Actualizar README: sustituir "~90%" aspiracional por la cifra medida y
   reclasificar AdBlock de "experimental" según el resultado; añadir sección
   de atribución EasyList/EasyPrivacy + licencia.
