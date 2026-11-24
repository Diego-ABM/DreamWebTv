package com.example.dreamweb.managers

import com.example.dreamweb.engine.SubtitleTrack
import com.example.dreamweb.engine.SubtitleTrackFilter
import com.example.dreamweb.engine.VideoReportParser
import com.example.dreamweb.engine.VideoUrlValidator
import org.json.JSONObject

/**
 * Payload del extra EXTRA_VIDEO_URL de NativeVideoActivity
 * (contracts/playback-context-payload.md).
 *
 * Formato productor único: JSON esquema v2 vía [toJson]. [parse] es dual —
 * si el primer carácter no-blank es `{` → JSON (campos ausentes → defaults,
 * desconocidos ignorados, `startPositionMs`<0→0, sin `url` http(s) → null);
 * cualquier otra entrada → parser legado `url|REFERER|UA|COOKIES` intacto.
 */
data class VideoLaunchPayload(
    val url: String,
    val referer: String = "",
    val userAgent: String = DEFAULT_USER_AGENT,
    val cookies: String = "",
    val headers: Map<String, String> = emptyMap(),
    val subtitles: List<SubtitleTrack> = emptyList(),
    val startPositionMs: Long = 0,
    val mimeType: String? = null
) {
    /** Productor único del esquema JSON v2 (contrato §1). */
    fun toJson(): String {
        val obj = JSONObject()
        obj.put("v", SCHEMA_VERSION)
        obj.put("url", url)
        obj.put("referer", referer)
        obj.put("userAgent", userAgent)
        obj.put("cookies", cookies)
        if (headers.isNotEmpty()) {
            val h = JSONObject()
            headers.forEach { (k, v) -> h.put(k, v) }
            obj.put("headers", h)
        }
        if (subtitles.isNotEmpty()) {
            val arr = org.json.JSONArray()
            subtitles.forEach { s ->
                val so = JSONObject()
                so.put("url", s.url)
                so.put("mimeType", s.mimeType)
                s.language?.let { so.put("language", it) }
                s.label?.let { so.put("label", it) }
                arr.put(so)
            }
            obj.put("subtitles", arr)
        }
        obj.put("startPositionMs", startPositionMs)
        mimeType?.let { obj.put("mimeType", it) }
        return obj.toString()
    }

    companion object {
        const val SCHEMA_VERSION = 2
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; BRAVIA 4K VH2) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36"

        /**
         * Parse dual: `{…}` → JSON v2; cualquier otra cosa → legado.
         * Devuelve `null` cuando el JSON es malformado o no trae una `url`
         * http(s) válida — el lanzamiento se aborta antes de congelar.
         */
        fun parse(videoData: String): VideoLaunchPayload? {
            val trimmed = videoData.trimStart()
            return if (trimmed.startsWith("{")) {
                parseJson(trimmed)
            } else {
                parseLegacy(videoData)
            }
        }

        private fun parseJson(json: String): VideoLaunchPayload? {
            val obj = try {
                JSONObject(json)
            } catch (e: Exception) {
                return null
            }
            val url = obj.opt("url") as? String ?: return null
            if (!VideoUrlValidator.isHttpUrl(url)) return null

            val headers = obj.optJSONObject("headers")?.let { h ->
                val raw = mutableMapOf<String, String>()
                val keys = h.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    (h.opt(k) as? String)?.let { raw[k] = it }
                }
                // Defensa en profundidad: misma sanitización que la frontera
                VideoReportParser.sanitizeHeaders(raw)
            } ?: emptyMap()

            val subtitles = mutableListOf<SubtitleTrack>()
            obj.optJSONArray("subtitles")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val s = arr.optJSONObject(i) ?: continue
                    val sUrl = (s.opt("url") as? String) ?: continue
                    SubtitleTrackFilter.filter(
                        sUrl,
                        language = s.opt("language") as? String,
                        label = s.opt("label") as? String
                    )?.let { subtitles.add(it) }
                }
            }

            val startPositionMs = (obj.opt("startPositionMs") as? Number)
                ?.toLong()?.coerceAtLeast(0) ?: 0L

            return VideoLaunchPayload(
                url = url,
                referer = (obj.opt("referer") as? String) ?: "",
                userAgent = (obj.opt("userAgent") as? String) ?: DEFAULT_USER_AGENT,
                cookies = (obj.opt("cookies") as? String) ?: "",
                headers = headers,
                subtitles = subtitles,
                startPositionMs = startPositionMs,
                mimeType = obj.opt("mimeType") as? String
            )
        }

        /** Parser legado `url|REFERER|referer|UA|ua|COOKIES|cookies` (vigente). */
        private fun parseLegacy(videoData: String): VideoLaunchPayload {
            val partsCookies = videoData.split("|COOKIES|")
            val mainWithUA = partsCookies[0]
            val cookies = if (partsCookies.size > 1) partsCookies[1] else ""

            val partsUA = mainWithUA.split("|UA|")
            val mainData = partsUA[0]
            val userAgent = if (partsUA.size > 1) partsUA[1] else DEFAULT_USER_AGENT

            val parts = mainData.split("|REFERER|")
            val url = parts[0]
            val referer = if (parts.size > 1) parts[1] else ""

            return VideoLaunchPayload(url, referer, userAgent, cookies)
        }
    }
}
