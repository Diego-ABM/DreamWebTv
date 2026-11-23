# Quickstart: Validación de Security Hardening

**Feature**: `001-security-hardening` | Guía de verificación end-to-end de los
dos frentes: TLS verificado con excepción por dominio, y bridge JS validado y
sin leaks. Ver [contracts/](contracts/) para el detalle de comportamiento y
[data-model.md](data-model.md) para entidades.

## Prerequisitos

- JDK toolchain del proyecto + Android SDK (compileSdk 36, minSdk 28).
- Emulador Android TV o dispositivo con build debug.
- Para la prueba TLS negativa: un servidor HTTPS con certificado autofirmado
  sirviendo un stream, o una URL de `https://self-signed.badssl.com` como
  probe. Opción local rápida:
  `openssl req -x509 -newkey rsa:2048 -keyout key.pem -out cert.pem -days 365 -nodes -subj "/CN=<IP-LAN>"`
  y servir un `.m3u8`/`.mp4` con cualquier servidor HTTPS estático.

## Build y tests automatizados

```bash
./gradlew assembleDebug          # Debe compilar (quality gate)
./gradlew test                   # Tests JVM nuevos
```

Tests esperados (lógica crítica, Principio IV):
- `VideoUrlValidatorTest`: acepta `.m3u8/.mpd/.mp4` http(s); rechaza
  `javascript:`, `data:`, `file:`, `blob:`, texto plano, segmentos y ad/tracker.
- `VideoLaunchPayloadTest`: parse de `url|REFERER|UA|COOKIES` completo y
  parcial (campos ausentes → defaults).
- `TlsDomainNormalizationTest`: host lowercase sin puerto; `example.com` no
  cubre `sub.example.com`.
- `SnifferBridgeTest`: URLs maliciosas no invocan el callback; URL válida sí;
  tras `release()` no invoca aunque la URL sea válida.

Verificación estática (SC-002):

```bash
grep -rn "checkServerTrusted\|hostnameVerifier\|trustAll" app/src/main/java/
# Debe mostrar SOLO security/DomainTrustManager.kt y security/PlayerHttpClientFactory.kt
# y ninguna aceptación incondicional.
```

## Escenario 1 — TLS verificado con escape hatch (US1)

1. Stream HTTPS con certificado válido → reproduce **sin diálogo** (SC-001).
2. Stream HTTPS con certificado autofirmado (host no exceptuado) → aparece
   diálogo nombrando el dominio. **Aceptar** → el stream reintenta solo y
   reproduce (SC-005). Volver a reproducir del mismo dominio → sin diálogo.
3. Repetir el punto 2 en sesión nueva y **Rechazar** → no reproduce; al
   relanzar, el diálogo vuelve a aparecer (rechazo no persistido).
4. Home → "Seguridad" → se lista el dominio exceptuado con su fecha → revocar
   (o "Borrar todas") → relanzar el stream → el diálogo vuelve a aparecer.

## Escenario 2 — Bridge JS endurecido (US2)

1. Página de prueba local con consola accesible (o inyectar vía
   `evaluateJavascript`): llamar
   `DreamWebSniffer.onVideoFound("javascript:alert(1)")` y variantes
   (`data:`, `file:`, `blob:`, texto plano, URL https sin patrón) →
   `lastDetectedVideoUrl` no cambia, el botón de player no aparece (SC-004).
2. Llamar con `https://example.com/live/master.m3u8` → el botón aparece,
   igual que con una detección legítima.

## Escenario 3 — Sin retención de Activity (FR-008, SC-003)

1. Abrir BrowserActivity → volver a Home → repetir ×10.
2. `adb shell dumpsys meminfo com.example.dreamweb | grep Activities` debe
   reportar solo las Activities vivas esperadas; opcionalmente heap dump en
   Android Studio Profiler → 0 instancias retenidas de `BrowserActivity`.
   (LeakCanary en debug es opcional, no requerido.)

## Escenario 4 — Edge cases

- Rotación durante el diálogo TLS: no aplica recreación (`configChanges` en
  manifest); el diálogo sigue visible sin duplicar excepción.
- Redirect a host con certificado inválido: un stream cuya URL apunta a un host
  con cert válido pero redirige (30x) a otro host con cert inválido → el
  diálogo nombra el host del redirect (donde falló el handshake, propagado por
  `DomainTrustManager`), no el de la URL original; al aceptar, la excepción
  queda registrada para el host del redirect.
- Cerrar la Activity con el diálogo pendiente (Back) → no crash; no se
  persiste excepción.
- Servidor LAN con autofirmado estable: exceptuar una vez, no vuelve a
  preguntar entre sesiones.
