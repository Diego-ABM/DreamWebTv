# Contract: Esquemas de URL externos (FR-004)

**Feature**: `003-deuda-tecnica` | **Fecha**: 2026-11-23

Contrato de comportamiento de `CustomWebClient.shouldOverrideUrlLoading` ante
URLs que no son páginas web. Regla de oro: **el WebView nunca navega a un
esquema ajeno** — o se resuelve con una app externa, o se avisa al usuario; en
ambos casos el retorno es `true` (navegación cancelada).

## Clasificación (capa JVM pura — `ExternalUrlClassifier.classify(url)`)

| URL de entrada | ExternalAction | Comportamiento en el glue |
|---|---|---|
| `http://…`, `https://…` | `WebLoad` | Flujo actual: check AdBlock → `return false` |
| `about:blank`, `about:*` | `WebLoad` | `return false` (WebView los maneja) |
| `data:…`, `blob:…` | `WebLoad` | `return false` (recursos inline legítimos) |
| `javascript:…` | `WebLoad` | `return false` (el WebView ya filtra su ejecución por URL-bar; llegar aquí por enlace implica click intencional en página) |
| Sin esquema (`www.foo.com`, texto) | `WebLoad` | `return false` (el WebView/error del renderer decide) |
| `intent://…` / `intent:…` | `External("intent")` | Ver tabla de resolución ↓ |
| `mailto:…`, `tel:…`, `sms:…`, `smsto:…`, `geo:…`, `market:…` | `External(scheme)` | `ACTION_VIEW` + `Uri.parse(url)` → resolver o aviso |
| Cualquier otro esquema `^[a-z][a-z0-9+.-]*:` (`whatsapp:`, `fb:`, `vnd.youtube:`, …) | `External(scheme)` | `ACTION_VIEW` + `Uri.parse(url)` → resolver o aviso |

## Resolución de `External` (glue Android — no testeable en JVM)

| Esquema | Intent | App resuelve | Nadie resuelve / excepción |
|---|---|---|---|
| `intent:` | `Intent.parseUri(url, URI_INTENT_SCHEME)` → `startActivity` | Abre la app destino | **1.** Lee extra `browser_fallback_url` del intent: si existe y es http(s) → `view.loadUrl(fallback)` y `return true`. **2.** Sin fallback → Toast "Ninguna app puede abrir este enlace" |
| `mailto:`, `tel:`, `sms:`, `smsto:`, `geo:` | `ACTION_VIEW` + Uri | Abre la app | Toast de aviso |
| `market:` | `ACTION_VIEW` + Uri | Abre Play Store | Toast de aviso (sin fallback sintetizado en v1) |
| Otros | `ACTION_VIEW` + Uri | Abre la app | Toast de aviso |

Excepciones cubiertas: `ActivityNotFoundException`, `SecurityException`,
`URISyntaxException` (intent malformado) → mismo aviso, `return true` siempre.
El usuario nunca ve la página de error interna del WebView por estos esquemas
(SC-004: 0 fallos silenciosos).

## Ejemplos de aceptación (US1 escenario 5)

- Click en `mailto:a@b.com` con cliente de correo → se abre; sin cliente →
  Toast, página intacta.
- Click en `intent://scan/#Intent;scheme=zxing;package=com.x;S.browser_fallback_url=https%3A%2F%2Fexample.com;end`
  sin la app → carga `https://example.com` en el WebView.
- Click en `intent://malformado` (parseo falla) → Toast, sin navegación.

## Tests del contrato

`ExternalUrlClassifierTest` (JVM puro) cubre la primera tabla completa. El glue
(resolución PackageManager, Toast, `loadUrl` de fallback) es frontera Android —
verificación manual en quickstart, escenario E2.
