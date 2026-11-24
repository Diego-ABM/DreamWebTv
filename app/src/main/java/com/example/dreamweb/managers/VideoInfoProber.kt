package com.example.dreamweb.managers

import com.example.dreamweb.engine.Rendition
import com.example.dreamweb.engine.StreamFormat
import com.example.dreamweb.engine.streamFormatOf
import com.example.dreamweb.engine.AUTO_RESOLUTION_LABEL
import com.example.dreamweb.engine.VideoFinding
import com.example.dreamweb.security.PlayerHttpClientFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Headers.Companion.toHeaders
import okhttp3.Request
import java.net.URI

/**
 * Sondeo acotado de metadatos por hallazgo (FR-002, R5): UNA única petición
 * por hallazgo, gatillada solo cuando faltan duración Y resolución.
 *
 * - URL `.m3u8` → `GET` del manifiesto (pequeño) y parse de
 *   `#EXT-X-STREAM-INF` (`RESOLUTION`/`BANDWIDTH`) → variantes que alimentan
 *   las rendiciones del hallazgo.
 * - Otras URLs → `HEAD` → `Content-Type`/`Content-Length` (limitación
 *   conocida: para progresivos no recupera duración ni resolución — el
 *   hallazgo se presenta igual con los campos disponibles).
 *
 * Viaja sobre `PlayerHttpClientFactory.createClient(payload)` con los
 * headers del hallazgo: misma fidelidad y pipeline TLS por dominio que el
 * player. Nunca bloquea la presentación; `Call.cancel()` al navegar;
 * fallos de red/TLS silenciosos (el hallazgo permanece).
 */
class VideoInfoProber(private val clientFactory: PlayerHttpClientFactory) {

    data class ProbeResult(
        val contentType: String? = null,
        val contentLength: Long? = null,
        val variants: List<Rendition> = emptyList()
    )

    private val activeCalls = mutableMapOf<String, Call>()

    /**
     * Lanza el sondeo en `scope` (debe ser el lifecycleScope del caller, no
     * GlobalScope — Principio V). El callback llega en el hilo principal.
     */
    fun probeAsync(
        finding: VideoFinding,
        referer: String,
        userAgent: String,
        cookies: String,
        scope: CoroutineScope,
        onResult: (ProbeResult?) -> Unit
    ) {
        val url = finding.primaryRendition.url
        val payload = VideoLaunchPayload(
            url = url,
            referer = referer,
            userAgent = userAgent,
            cookies = cookies,
            headers = finding.headers
        )
        val client = clientFactory.createClient(payload)
        // Manifiesto HLS en cualquier forma (.m3u8 y también /m3u8/<id> sin
        // extensión, patrón usual de proxies de streaming).
        val isManifest = streamFormatOf(url, null) == StreamFormat.HLS

        val requestHeaders = clientFactory.mergedRequestHeaders(payload)
            .plus("User-Agent" to payload.userAgent)
            .toHeaders()

        val request = Request.Builder()
            .url(url)
            .headers(requestHeaders)
            .apply { if (isManifest) get() else head() }
            .build()

        val call = client.newCall(request)
        synchronized(activeCalls) { activeCalls[finding.id] = call }

        scope.launch(Dispatchers.IO) {
            val result = try {
                call.execute().use { resp ->
                    if (!resp.isSuccessful) null
                    else if (isManifest) {
                        parseMaster(resp.body?.string() ?: "", url)
                    } else {
                        ProbeResult(
                            contentType = resp.header("Content-Type"),
                            contentLength = resp.header("Content-Length")?.toLongOrNull()
                        )
                    }
                }
            } catch (e: Exception) {
                null   // fallos de red/TLS silenciosos — el hallazgo permanece
            }
            synchronized(activeCalls) { activeCalls.remove(finding.id) }
            withContext(Dispatchers.Main) { onResult(result) }
        }
    }

    /** Cancela todo sondeo pendiente (navegación de main-frame, cierre). */
    fun cancelAll() {
        synchronized(activeCalls) {
            activeCalls.values.forEach { it.cancel() }
            activeCalls.clear()
        }
    }

    // ---------- parse de manifiesto maestro HLS ----------

    /**
     * Extrae variantes `#EXT-X-STREAM-INF`: la URI de la variante es la
     * línea siguiente no-comentario; `RESOLUTION=WxH` aporta la etiqueta
     * `"{h}p"` y `BANDWIDTH=n` el bitrate. Sin variantes → lista vacía.
     */
    internal fun parseMaster(body: String, baseUrl: String): ProbeResult {
        val variants = mutableListOf<Rendition>()
        val lines = body.lines()
        for (i in lines.indices) {
            val line = lines[i].trim()
            if (!line.startsWith("#EXT-X-STREAM-INF")) continue
            // la URI es la siguiente línea no comentario
            var uriLine: String? = null
            for (j in i + 1 until lines.size) {
                val next = lines[j].trim()
                if (next.isEmpty()) continue
                if (!next.startsWith("#")) { uriLine = next }
                break
            }
            val uri = uriLine ?: continue
            val resolved = resolveRelative(baseUrl, uri) ?: continue
            val (w, h) = parseResolution(line)
            variants.add(
                Rendition(
                    url = resolved,
                    format = StreamFormat.HLS,
                    resolutionLabel = h?.let { "${it}p" } ?: AUTO_RESOLUTION_LABEL,
                    heightPx = h,
                    bandwidthBps = parseBandwidth(line)
                )
            )
        }
        return ProbeResult(contentType = "application/vnd.apple.mpegurl", variants = variants)
    }

    private fun parseResolution(streamInfLine: String): Pair<Int?, Int?> {
        val m = Regex("RESOLUTION=(\\d+)x(\\d+)").find(streamInfLine) ?: return null to null
        return m.groupValues[1].toIntOrNull() to m.groupValues[2].toIntOrNull()
    }

    private fun parseBandwidth(streamInfLine: String): Long? =
        Regex("BANDWIDTH=(\\d+)").find(streamInfLine)?.groupValues?.get(1)?.toLongOrNull()

    private fun resolveRelative(baseUrl: String, ref: String): String? {
        return try {
            if (ref.startsWith("http://") || ref.startsWith("https://")) ref
            else URI(baseUrl).resolve(ref).toString()
        } catch (e: Exception) {
            null
        }
    }
}
