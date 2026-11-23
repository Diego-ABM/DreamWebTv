# Data Model: Deuda Técnica

**Feature**: `003-deuda-tecnica` | **Date**: 2026-11-23

Entidades del dominio, su representación y las reglas derivadas de `spec.md`.
El esquema SQLite **no cambia** (DATABASE_VERSION sigue = 1); los cambios son
de acceso (conexión única), de lógica de escritura (dedup, rotación) y de
superficie (id en los listados). Clasificadores nuevos son Kotlin puro para
tests JVM (Principio IV).

---

## Entidades persistidas

### HistoryEntry

```kotlin
data class HistoryEntry(
    val id: Long,          // rowid SQLite (COLUMN_ID, INTEGER PRIMARY KEY)
    val url: String,
    val title: String,     // puede quedar "" si la página no expone <title>
    val timestamp: Long    // epoch millis
)
```

- **Tabla**: `history` (sin cambios de esquema: `id, url, title, timestamp`).
- **Escritura (FR-001)**: `HistoryManager.addOrUpdateLatest(url, title)` —
  si la fila con `MAX(timestamp)` tiene la misma `url` → `UPDATE` title y
  timestamp; si no → `INSERT`. Deduplicación **solo consecutiva**: una misma
  URL puede existir en filas no consecutivas (válido).
- **Rotación (FR-011)**: tras cada escritura, `DELETE FROM history WHERE id
  NOT IN (SELECT id FROM history ORDER BY timestamp DESC, id DESC LIMIT 500)`.
  **Límite fijado: 500 entradas, FIFO por timestamp** (default asumido del
  spec, confirmado como valor final).
- **Borrado (FR-010)**: `deleteById(id)` — por id, no por url (ver research R9).
- **Validación**: `url` non-blank se exige en el punto de llamada
  (`onPageFinished`/`onReceivedTitle` filtran `about:blank` y nulls); `title`
  nullable → se normaliza a `""` en lectura (comportamiento actual conservado).

### Bookmark

```kotlin
data class Bookmark(
    val id: Long,
    val url: String,
    val title: String,
    val timestamp: Long
)
```

- **Tabla**: `bookmarks` (mismo esquema, sin cambios).
- **Escritura**: `addBookmark(url, title)` — INSERT simple (sin dedup en v1,
  igual que hoy).
- **Borrado (FR-010)**: `deleteById(id)`.
- **Lectura**: `getAllBookmarks(): List<Bookmark>` ordenada por título ASC
  (orden actual conservado).

### DatabaseConnection (= DbHelper único)

```kotlin
// AppContainer:
val dbHelper = DbHelper(context)                 // única instancia
val historyManager = HistoryManager(dbHelper)
val bookmarkManager = BookmarkManager(dbHelper)
```

- **Relación**: `HistoryEntry` y `Bookmark` viven en la misma base
  `dreamweb.db`, servida por **un** `DbHelper` inyectado a ambos managers
  (FR-009, research R8). `SQLiteOpenHelper` cachea la `SQLiteDatabase` → una
  sola conexión física.
- **Migración**: ninguna requerida — versión 1 sin cambios de DDL. Bases
  existentes siguen funcionando (migración conservadora: cero pasos).

## Entidades de runtime (no persistidas)

### DetectedVideo (estado de BrowserActivity)

```text
lastDetectedVideoUrl: String?   // payload "url|REFERER|ref" (formato existente)
```

- **Estados**: `null` (sin video vigente → botón GONE) | `payload` (vigente →
  botón VISIBLE cuando no hay fullscreen).
- **Transiciones**:
  - `onVideoFound(payload)` → set + botón VISIBLE (prioridad m3u8 > mp4
    conservada de `BrowserActivity.onVideoFound`).
  - `onPageStarted(main-frame)` → `null` + botón GONE (invalidación por
    navegación — FR-002/R3).
  - `onFullscreenChanged(false)` → botón VISIBLE **iff** `lastDetectedVideoUrl
    != null` (restauración condicionada — US1 escenarios 3 y 4).

### ExternalAction (clasificación de URL, Kotlin puro)

```kotlin
sealed interface ExternalAction {
    data object WebLoad : ExternalAction            // http, https, about, data, blob
    data class External(val scheme: String) : ExternalAction  // mailto, tel, market,
                                                            // intent, otros ^[a-z][a-z0-9+.-]*:
}
```

- **Reglas**: sin esquema o esquema web → `WebLoad`; cualquier otro esquema
  válido URI → `External`. La resolución (Intent, fallback `intent://`, Toast)
  vive en el glue Android de `CustomWebClient` — ver
  `contracts/external-url-schemes.md`.

### VideoLaunchPayload (existente, sin cambios)

`url|REFERER|UA|COOKIES` — parseado por `VideoLaunchPayload.parse`; su formato
es contrato estable cubierto por `VideoLaunchPayloadTest`.

### DpadAcceleration (lógica pura nueva)

```kotlin
object DpadAcceleration {
    const val BASE_STEP = 25f
    const val MAX_STEP = 150f
    fun stepFor(repeatCount: Int): Float
}
```

- `repeatCount` viene de `KeyEvent` (0 en primera pulsación, crece en
  auto-repeat, resetea al soltar/cambiar dirección). Monótona creciente acotada
  en `MAX_STEP` — FR-015, testeable en JVM puro.

## Reglas de estado derivadas

| Regla | Origen | Dónde se aplica |
|-------|--------|-----------------|
| Historial guarda título definitivo | FR-001 / SC-001 | `addOrUpdateLatest` desde `onPageFinished` + `onReceivedTitle` |
| 1 entrada consecutiva por URL | FR-001 / SC-001 | dedup en `addOrUpdateLatest` |
| Máx. 500 entradas (FIFO) | FR-011 / SC-006 | rotación en `addOrUpdateLatest` |
| `isPlaying` solo tras contexto válido | FR-003 / SC-003 | `VideoPlayerManager.launchPlayer` reordenado |
| Botón externo ⇔ video vigente | FR-002 / SC-002 | `DetectedVideo` + callback fullscreen |
| Una conexión DB | FR-009 / SC-006 | `DbHelper` único en `AppContainer` |
| Monitor solo DEBUG + `stop()` | FR-005 / SC-005 | `BrowserApp` + foreground counter |
