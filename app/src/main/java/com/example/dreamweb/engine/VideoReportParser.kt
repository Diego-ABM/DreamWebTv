package com.example.dreamweb.engine

import org.json.JSONObject
import java.util.Locale

/**
 * Parser de frontera no confiable JS→Kotlin (Principio II,
 * contracts/video-detection-bridge.md §3).
 *
 * Aplica la cadena de validación del contrato en orden: tamaño ≤64 KB,
 * objeto bien formado, `url` validada por [VideoUrlValidator.isVideoUrl],
 * `pageUrl` http(s) (si falta/es inválida se conserva la URL de la página
 * actual como referer — nunca una inyectada), tipos estrictos con rangos,
 * headers sanitizados y subtítulos filtrados por [SubtitleTrackFilter].
 *
 * Todo rechazo es loggable solo en DEBUG vía [logger].
 */
object VideoReportParser {

    const val MAX_PAYLOAD_BYTES = 64 * 1024
    const val MAX_TITLE_CHARS = 512
    const val MAX_HEADERS = 32
    const val MAX_SUBTITLES = 8
    const val MIN_DIMENSION = 16
    const val MAX_DIMENSION = 8192
    private const val MAX_COORD = 100_000.0

    /** Log opcional de rechazos (asignar solo en builds DEBUG). */
    var logger: ((String) -> Unit)? = null

    private val HEADER_NAME = Regex("^[A-Za-z0-9-]+$")

    /** Nombres de la capa gestionada (R8): jamás aceptados del reporte. */
    private val MANAGED_HEADERS = setOf(
        "host", "content-length", "range",
        "user-agent", "referer", "cookie", "origin"
    )

    fun parse(json: String, fallbackPageUrl: String = ""): ParsedMessage? {
        // 1. Tamaño y forma
        if (json.length > MAX_PAYLOAD_BYTES ||
            json.toByteArray(Charsets.UTF_8).size > MAX_PAYLOAD_BYTES) {
            log("report discarded: payload over 64KB")
            return null
        }
        val obj = try {
            JSONObject(json)
        } catch (e: Exception) {
            log("report discarded: malformed json")
            return null
        }

        // Mensajes de estado del elemento (playstate) no llevan url.
        if (optString(obj, "type") == "playstate") {
            return parsePlayState(obj, fallbackPageUrl)
        }

        // 2. url obligatoria. La frontera DOM no exige patrón de formato:
        // el contexto del elemento/player ya es evidencia de medio y los
        // streams reales llegan muchas veces sin extensión (p. ej.
        // `/m3u8/<id>`). Siguen aplicando http(s) + filtros segmento/ads.
        val url = optString(obj, "url")
        if (url == null || !VideoUrlValidator.isDomMediaUrl(url)) {
            log("report discarded: url rejected")
            return null
        }

        // 3. pageUrl http(s); si falta/invalida → la página actual (referer)
        val rawPageUrl = optString(obj, "pageUrl")
        val pageUrl = if (rawPageUrl != null && VideoUrlValidator.isHttpUrl(rawPageUrl)) {
            rawPageUrl
        } else {
            fallbackPageUrl
        }

        // 4. Campos opcionales con tipos estrictos y rangos
        val title = (optString(obj, "title") ?: "").take(MAX_TITLE_CHARS)
        val mimeType = optString(obj, "mimeType")
        val width = optInt(obj, "width")?.takeIf { it in MIN_DIMENSION..MAX_DIMENSION }
        val height = optInt(obj, "height")?.takeIf { it in MIN_DIMENSION..MAX_DIMENSION }
        val durationSec = optDouble(obj, "durationSec")?.takeIf { it >= 0 }
        val positionSec = optDouble(obj, "positionSec")?.takeIf { it >= 0 }
        val mediaKey = optString(obj, "mediaKey")

        // 5. headers sanitizados
        val headers = obj.optJSONObject("headers")?.let { headersObj ->
            val raw = mutableMapOf<String, String>()
            val keys = headersObj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = headersObj.opt(k)
                if (v is String) raw[k] = v
            }
            sanitizeHeaders(raw)
        } ?: emptyMap()

        // 6. subtítulos filtrados por formato/url (omisión silenciosa)
        val subtitles = mutableListOf<SubtitleTrack>()
        obj.optJSONArray("subtitles")?.let { arr ->
            for (i in 0 until arr.length()) {
                if (subtitles.size >= MAX_SUBTITLES) break
                val s = arr.optJSONObject(i) ?: continue
                val sUrl = optString(s, "url") ?: continue
                SubtitleTrackFilter.filter(
                    sUrl,
                    language = optString(s, "language"),
                    label = optString(s, "label")
                )?.let { subtitles.add(it) }
            }
        }

        return VideoReport(
            url = url,
            pageUrl = pageUrl,
            title = title,
            mimeType = mimeType,
            format = streamFormatOf(url, mimeType),
            widthPx = width,
            heightPx = height,
            durationSec = durationSec,
            positionSec = positionSec,
            headers = headers,
            subtitles = subtitles,
            mediaKey = mediaKey,
            source = DetectionSource.DOM
        )
    }

    /**
     * Parse del mensaje `type:"playstate"`: estado del `<video>` que suena.
     * Campos acotados con las mismas reglas de tipo/rango que el reporte;
     * `rect` solo se acepta completo y con tamaño positivo.
     */
    private fun parsePlayState(obj: JSONObject, fallbackPageUrl: String): PlayState? {
        val playing = obj.opt("playing") as? Boolean
        if (playing == null) {
            log("playstate discarded: missing playing flag")
            return null
        }

        val rawPageUrl = optString(obj, "pageUrl")
        val pageUrl = if (rawPageUrl != null && VideoUrlValidator.isHttpUrl(rawPageUrl)) {
            rawPageUrl
        } else {
            fallbackPageUrl
        }

        val rect = obj.optJSONObject("rect")?.let { r ->
            val x = optDouble(r, "x")
            val y = optDouble(r, "y")
            val w = optDouble(r, "w")
            val h = optDouble(r, "h")
            if (x != null && y != null && w != null && h != null &&
                w > 0 && h > 0 &&
                x in -MAX_COORD..MAX_COORD && y in -MAX_COORD..MAX_COORD &&
                w <= MAX_COORD && h <= MAX_COORD
            ) {
                ElementRect(x, y, w, h)
            } else null
        }

        return PlayState(
            playing = playing,
            mediaKey = optString(obj, "mediaKey"),
            rect = rect,
            viewportWidth = optInt(obj, "vw")?.takeIf { it in 1..MAX_DIMENSION },
            viewportHeight = optInt(obj, "vh")?.takeIf { it in 1..MAX_DIMENSION },
            inIframe = (obj.opt("inIframe") as? Boolean) ?: false,
            pageUrl = pageUrl
        )
    }

    /**
     * Sanitización de headers reutilizable por el canal de red
     * (`request.requestHeaders`): nombres `^[A-Za-z0-9-]+$`, valores sin
     * CR/LF/NUL, máx. [MAX_HEADERS] entradas; la capa gestionada
     * (`Host`/`Content-Length`/`Range`/`Sec-Fetch-*`/`User-Agent`/`Referer`/
     * `Cookie`/`Origin`) se excluye siempre.
     */
    fun sanitizeHeaders(raw: Map<String, String>): Map<String, String> {
        val out = LinkedHashMap<String, String>(MAX_HEADERS)
        for ((name, value) in raw) {
            if (out.size >= MAX_HEADERS) break
            if (!HEADER_NAME.matches(name)) continue
            val lower = name.lowercase(Locale.US)
            if (lower in MANAGED_HEADERS || lower.startsWith("sec-fetch-")) continue
            if (value.any { it == '\r' || it == '\n' || it == '\u0000' }) continue
            out[name] = value
        }
        return out
    }

    private fun optString(obj: JSONObject, key: String): String? =
        obj.opt(key) as? String

    private fun optInt(obj: JSONObject, key: String): Int? =
        (obj.opt(key) as? Number)?.toInt()

    private fun optDouble(obj: JSONObject, key: String): Double? =
        (obj.opt(key) as? Number)?.toDouble()

    private fun log(msg: String) {
        logger?.invoke("VideoReportParser: $msg")
    }
}
