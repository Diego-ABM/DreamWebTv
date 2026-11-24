package com.example.dreamweb.engine

import java.net.URI
import java.util.Locale

/**
 * Consolidación de hallazgos de video de la página actual (FR-004/005,
 * FR-016…018). Kotlin puro, JVM-testeable (Principio IV).
 *
 * - Dedup por URL de stream normalizada: el mismo video reportado por varias
 *   vías (DOM/red/player) produce UN hallazgo; el merge funde campos vacíos
 *   y nunca pisa datos ya resueltos, salvo `positionSec` que siempre refleja
 *   el último valor observado.
 * - Mutación de fuente por `mediaKey`: mismo mediaKey + URL distinta → la URL
 *   nueva pasa a ser `primaryRendition` (el stream vigente gana), se acumula
 *   como rendición y `probed` se resetea. Este mismo mecanismo acumula los
 *   `<source>` siblings de un mismo `<video>` (mismo mediaKey → misma pieza).
 * - Heurística secundaria de título idéntico NO vacío: solo agrupa cuando
 *   difieren en resolución/formato (guard: igual título + igual resolución +
 *   igual formato NO agrupa; mediaKey/título vacío nunca se agrupan entre sí).
 * - Capacidad acotada a [MAX_FINDINGS] por página (Principio I);
 *   [clear] se invoca en cada navegación de main-frame.
 *
 * @param qualityLabelProvider devuelve la etiqueta de resolución de la última
 *   elección del usuario (memoria de sesión, FR-018); inyectable en tests.
 */
class VideoFindingsRegistry(
    private val qualityLabelProvider: () -> String? = { null }
) {

    private val findingsMap = LinkedHashMap<String, VideoFinding>()
    private val byUrl = HashMap<String, String>()       // url normalizada -> id
    private val byMediaKey = HashMap<String, String>()  // mediaKey -> id
    private var nextId = 0

    /** Snapshot inmutable para la UI. */
    fun findings(): List<VideoFinding> = findingsMap.values.toList()

    fun clear() {
        findingsMap.clear()
        byUrl.clear()
        byMediaKey.clear()
    }

    /**
     * Envía un reporte al registry. Devuelve el [VideoFinding] resultante
     * (nuevo, fundido, mutado o agrupado) o `null` si se descartó por
     * capacidad — la UI lo usa para refrescar y para el sondeo acotado.
     */
    fun submit(report: VideoReport): VideoFinding? {
        val norm = normalizeUrl(report.url)
        val mk = report.mediaKey

        // 1) Dedup por URL normalizada
        byUrl[norm]?.let { id ->
            findingsMap[id]?.let { f ->
                var merged = mergeInto(f, report)
                // Si el MISMO elemento re-reporta una rendición ya conocida
                // distinta de la primaria, la fuente vigente volvió a ella
                // (el stream vigente gana también al retroceder).
                if (mk != null && mk == f.mediaKey && norm != normalizeUrl(f.primaryRendition.url)) {
                    merged.renditions.firstOrNull { normalizeUrl(it.url) == norm }
                        ?.let { merged = merged.copy(primaryRendition = it, probed = false) }
                }
                findingsMap[id] = merged
                indexMediaKey(report.mediaKey, id)
            }
            return findingsMap[id]
        }

        // 2) Mutación / acumulación de fuentes por mediaKey
        if (!mk.isNullOrEmpty()) {
            byMediaKey[mk]?.let { id ->
                findingsMap[id]?.let { f ->
                    findingsMap[id] = mutateSource(f, report)
                    byUrl[norm] = id
                }
                return findingsMap[id]
            }
        }

        // 3) Heurística de título idéntico no vacío — solo agrupa cuando
        //    difieren en resolución/formato (guard del spec)
        if (report.title.isNotEmpty()) {
            val newLabel = resolutionLabelOf(report.heightPx)
            val candidate = findingsMap.values.firstOrNull { f ->
                f.title == report.title &&
                    f.renditions.none {
                        it.resolutionLabel == newLabel && it.format == report.format
                    }
            }
            if (candidate != null) {
                val updated = addReportAsRendition(candidate, report)
                findingsMap[candidate.id] = updated.copy(
                    primaryRendition = computeDefault(updated)
                )
                byUrl[norm] = candidate.id
                indexMediaKey(mk, candidate.id)
                return findingsMap[candidate.id]
            }
        }

        // 4) Hallazgo nuevo (capacidad acotada)
        if (findingsMap.size >= MAX_FINDINGS) return null
        val id = "f${++nextId}"
        val rendition = renditionOf(report)
        findingsMap[id] = VideoFinding(
            id = id,
            pageUrl = report.pageUrl,
            title = report.title,
            primaryRendition = rendition,
            renditions = listOf(rendition),
            durationSec = report.durationSec,
            positionSec = report.positionSec,
            headers = report.headers,
            subtitles = report.subtitles,
            probed = false,
            mimeType = report.mimeType,
            mediaKey = mk
        )
        byUrl[norm] = id
        indexMediaKey(mk, id)
        return findingsMap[id]
    }

    /** Marca el hallazgo como ya sondeado (una petición acotada máx.). */
    fun markProbed(findingId: String) {
        findingsMap[findingId]?.let { findingsMap[findingId] = it.copy(probed = true) }
    }

    /**
     * Aplica el resultado del sondeo acotado (R5): completa duración si
     * faltaba, añade las variantes del manifiesto como rendiciones y marca
     * el hallazgo como sondeado. La propuesta por defecto se recalcula.
     */
    fun applyProbeResult(findingId: String, durationSec: Double?, variants: List<Rendition>) {
        val f = findingsMap[findingId] ?: return
        var updated = f.copy(
            probed = true,
            durationSec = f.durationSec ?: durationSec
        )
        for (v in variants) {
            if (updated.renditions.none { it.url == v.url }) {
                updated = updated.copy(renditions = updated.renditions + v)
                byUrl[normalizeUrl(v.url)] = findingId
            }
        }
        findingsMap[findingId] = updated.copy(primaryRendition = computeDefault(updated))
    }

    // ---------- Rendiciones / opciones de calidad ----------

    /**
     * Una opción por etiqueta de resolución (`"{height}p"` o `"Auto"`), nunca
     * por URL cruda (FR-016). Dentro del grupo gana la preferencia de formato
     * HLS > DASH > progresivo (FR-017); rendiciones sin resolución se agrupan
     * bajo "Auto" sin descartarse.
     */
    fun qualityOptions(finding: VideoFinding): List<Rendition> =
        finding.renditions
            .groupBy { it.resolutionLabel }
            .map { (_, group) -> preferredOf(group) }
            .sortedByDescending { it.heightPx ?: -1 }   // "Auto" (-1) al final

    /**
     * Opción propuesta por defecto: la última elección del usuario si sigue
     * disponible en el grupo (FR-018); si no, la preferida por formato
     * (HLS > DASH > progresivo, FR-017).
     */
    fun defaultOption(finding: VideoFinding): Rendition {
        val options = qualityOptions(finding)
        qualityLabelProvider()?.let { label ->
            options.firstOrNull { it.resolutionLabel == label }?.let { return it }
        }
        return preferredOf(options)
    }

    // ---------- Internos ----------

    private fun renditionOf(report: VideoReport) = Rendition(
        url = report.url,
        format = report.format,
        resolutionLabel = resolutionLabelOf(report.heightPx),
        heightPx = report.heightPx,
        bandwidthBps = null
    )

    private fun resolutionLabelOf(heightPx: Int?): String =
        heightPx?.let { "${it}p" } ?: AUTO_RESOLUTION_LABEL

    private fun indexMediaKey(mediaKey: String?, id: String) {
        if (!mediaKey.isNullOrEmpty()) byMediaKey[mediaKey] = id
    }

    /** Merge por dedup: funde campos vacíos, nunca pisa resueltos. */
    private fun mergeInto(f: VideoFinding, report: VideoReport): VideoFinding {
        val mergedHeaders = f.headers + report.headers.filterKeys { it !in f.headers.keys }
        val mergedSubs = f.subtitles + report.subtitles.filter { s ->
            f.subtitles.none { it.url == s.url }
        }
        // La rendición existente puede ganar metadatos del reporte (altura,
        // formato) solo cuando aún no estaban resueltos.
        val mergedRenditions = f.renditions.map { r ->
            if (r.url != report.url) r
            else r.copy(
                format = if (r.format == StreamFormat.UNKNOWN) report.format else r.format,
                resolutionLabel = if (r.heightPx == null) resolutionLabelOf(report.heightPx) else r.resolutionLabel,
                heightPx = r.heightPx ?: report.heightPx
            )
        }
        return f.copy(
            title = f.title.ifEmpty { report.title },
            pageUrl = f.pageUrl.ifEmpty { report.pageUrl },
            durationSec = f.durationSec ?: report.durationSec,
            positionSec = report.positionSec ?: f.positionSec,
            headers = mergedHeaders,
            subtitles = mergedSubs,
            mimeType = f.mimeType ?: report.mimeType,
            renditions = mergedRenditions,
            primaryRendition = f.primaryRendition.let { p ->
                mergedRenditions.firstOrNull { it.url == p.url } ?: p
            }
        )
    }

    /**
     * Mutación de fuente (FR-005): la URL nueva reemplaza a
     * `primaryRendition` y se acumula como rendición; metadatos se funden
     * como en el merge; `probed` se resetea porque el stream vigente cambió.
     */
    private fun mutateSource(f: VideoFinding, report: VideoReport): VideoFinding {
        val merged = mergeInto(f, report)
        val newRendition = renditionOf(report)
        return merged.copy(
            renditions = merged.renditions + newRendition,
            primaryRendition = newRendition,
            probed = false,
            mediaKey = report.mediaKey ?: merged.mediaKey
        )
    }

    /** Heurística de título: el reporte pasa a ser una rendición más. */
    private fun addReportAsRendition(f: VideoFinding, report: VideoReport): VideoFinding {
        val merged = mergeInto(f, report)
        return merged.copy(renditions = merged.renditions + renditionOf(report))
    }

    /** Propuesta por defecto del grupo: memoria de sesión o preferencia de formato. */
    private fun computeDefault(f: VideoFinding): Rendition = defaultOption(f)

    private fun preferredOf(list: List<Rendition>): Rendition =
        list.sortedWith(
            compareBy<Rendition> { formatRank(it.format) }
                .thenByDescending { it.heightPx ?: -1 }
                .thenByDescending { it.bandwidthBps ?: -1L }
        ).first()

    private fun formatRank(format: StreamFormat): Int = when (format) {
        StreamFormat.HLS -> 0
        StreamFormat.DASH -> 1
        StreamFormat.PROGRESSIVE -> 2
        StreamFormat.UNKNOWN -> 3
    }

    /** Normalización de URL de stream para dedup: esquema+host en minúsculas. */
    private fun normalizeUrl(url: String): String {
        return try {
            val uri = URI(url.trim())
            val scheme = uri.scheme?.lowercase(Locale.US) ?: ""
            val host = uri.host?.lowercase(Locale.US) ?: ""
            val port = if (uri.port >= 0) ":${uri.port}" else ""
            buildString {
                append(scheme).append("://").append(host).append(port)
                append(uri.rawPath ?: "")
                uri.rawQuery?.let { append("?").append(it) }
                uri.rawFragment?.let { append("#").append(it) }
            }
        } catch (e: Exception) {
            url.trim().lowercase(Locale.US)
        }
    }

    companion object {
        const val MAX_FINDINGS = 64
    }
}
