# Notas de validación — 004-video-capture-playback

Fecha: sesión de implementación. Rama `004-video-capture-playback`.

## E0 — Compilación y suite unitaria ✅

- `./gradlew assembleDebug` → **BUILD SUCCESSFUL**
- `./gradlew test` (testDebugUnitTest) → **134 tests, 0 fallos**
  (incluye `VideoUrlValidatorTest`, `SnifferBridgeTest` actualizado,
  `VideoLaunchPayloadTest` legado + `VideoLaunchPayloadJsonTest`,
  `VideoReportParserTest`, `VideoFindingsRegistryTest`,
  `SubtitleTrackFilterTest`, `PlayerHttpClientFactoryTest`,
  `HistoryManagerTest`, `BookmarkManagerTest`, ad-block).
- Corrección de infraestructura: `maxHeapSize = "2g"` en los tests JVM —
  Conscrypt agota los 512 MB por defecto al cargar el truststore en cada
  `DomainTrustManager` (OOM ambiental, no fallo de lógica).

## E1a–E6 — Escenarios manuales (pendientes para el usuario)

Dispositivo detectado: Android TV conectado por adb en LAN.
**Bloqueo**: `adb install` falla con `INSTALL_FAILED_UPDATE_INCOMPATIBLE` —
la app ya instalada en el TV está firmada con otra clave debug. Desinstalar
borraría favoritos/historial del usuario, por lo que NO se hizo.

**Para ejecutarlos**: `adb uninstall com.example.dreamweb` (pierde datos de
la app) o instalar con la misma keystore que la build vigente, y luego:

| Escenario | Qué verificar | Estado |
|---|---|---|
| E1a | Indicador `▶ N` sin diálogo/auto-play; filas en panel; ≤2 s en P-B; 0 FP en P-E; multi-video | ⏳ pendiente |
| E1b | Fallback sin `DOCUMENT_START_SCRIPT` detecta en `onPageFinished` | ⏳ pendiente |
| E2 | Referer/Origin/cookies/custom headers viajan; diálogo TLS por dominio | ⏳ pendiente |
| E3 | `dumpsys meminfo` ≤180 MB; BACK restaura; "Don't keep activities"; toast FR-012 | ⏳ pendiente |
| E4 | Sub-fila `▸ Calidad:` Auto/720p/480p; default HLS; memoria de sesión | ⏳ pendiente |
| E5 | Pista "Español" seleccionable; `.ass` omitida | ⏳ pendiente |
| E6 | Mutación de `src` sin duplicar; URL caducada → mensaje claro | ⏳ pendiente |
| SC-008 | 0 peticiones con metadatos a hosts ajenos | ⏳ pendiente |

## Cobertura automatizada equivalente (verde)

- E0 + parsing/frontera, dedup/mutación/agrupación/memoria de sesión,
  dual-parse payload, merge de headers, filtro de subtítulos, bridge JSON.
- SC-006 parcial: filtro de `VideoUrlValidator` en tests (red no cubre
  ads first-party — documentado).
