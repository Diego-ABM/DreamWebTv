package com.example.dreamweb.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM puro: consolidación de hallazgos — dedup, mutación por mediaKey,
 * agrupación de rendiciones, preferencia de formato y memoria de sesión.
 */
class VideoFindingsRegistryTest {

    private fun report(
        url: String,
        mediaKey: String? = null,
        title: String = "",
        heightPx: Int? = null,
        format: StreamFormat = StreamFormat.PROGRESSIVE,
        durationSec: Double? = null,
        positionSec: Double? = null,
        headers: Map<String, String> = emptyMap(),
        subtitles: List<SubtitleTrack> = emptyList(),
        source: DetectionSource = DetectionSource.DOM,
        pageUrl: String = "https://site.example.com/p"
    ) = VideoReport(
        url = url, pageUrl = pageUrl, title = title, mimeType = null,
        format = format, widthPx = null, heightPx = heightPx,
        durationSec = durationSec, positionSec = positionSec,
        headers = headers, subtitles = subtitles,
        mediaKey = mediaKey, source = source
    )

    // ---------- dedup por URL normalizada ----------

    @Test
    fun `same stream url via DOM and NETWORK produces one finding`() {
        val r = VideoFindingsRegistry()
        val url = "https://cdn.example.com/v/master.m3u8"
        r.submit(report(url, title = "Episodio", source = DetectionSource.DOM))
        r.submit(
            report(
                url, source = DetectionSource.NETWORK,
                headers = mapOf("X-Req" to "1")
            )
        )
        val f = r.findings().single()
        assertEquals("Episodio", f.title)
        assertEquals(mapOf("X-Req" to "1"), f.headers)
    }

    @Test
    fun `url normalization dedups scheme and host case`() {
        val r = VideoFindingsRegistry()
        r.submit(report("https://cdn.example.com/v/a.mp4"))
        r.submit(report("HTTPS://CDN.EXAMPLE.COM/v/a.mp4"))
        assertEquals(1, r.findings().size)
    }

    @Test
    fun `merge fills empty fields but never overwrites resolved ones`() {
        val r = VideoFindingsRegistry()
        val url = "https://cdn.example.com/v/a.mp4"
        r.submit(report(url, title = "Título real", durationSec = 10.0))
        r.submit(report(url, title = "Otro", durationSec = 99.0))
        val f = r.findings().single()
        assertEquals("Título real", f.title)
        assertEquals(10.0, f.durationSec!!, 0.001)
    }

    @Test
    fun `positionSec always reflects the last observed value`() {
        val r = VideoFindingsRegistry()
        val url = "https://cdn.example.com/v/a.mp4"
        r.submit(report(url, positionSec = 5.0))
        r.submit(report(url))                       // sin posición → conserva 5
        assertEquals(5.0, r.findings().single().positionSec!!, 0.001)
        r.submit(report(url, positionSec = 42.5))   // último observado gana
        assertEquals(42.5, r.findings().single().positionSec!!, 0.001)
    }

    // ---------- mutación de fuente por mediaKey ----------

    @Test
    fun `same mediaKey with different url replaces primary and resets probed`() {
        val r = VideoFindingsRegistry()
        val a = "https://cdn.example.com/ad.mp4"
        val b = "https://cdn.example.com/movie.m3u8"
        r.submit(report(a, mediaKey = "v1"))
        val id = r.findings().single().id
        r.markProbed(id)
        assertTrue(r.findings().single().probed)

        r.submit(report(b, mediaKey = "v1", format = StreamFormat.HLS))

        val f = r.findings().single()
        assertEquals(b, f.primaryRendition.url)      // el stream vigente gana
        assertFalse(f.probed)                        // se resetea el sondeo
        assertEquals("v1", f.mediaKey)
    }

    @Test
    fun `source siblings of the same mediaKey accumulate as renditions`() {
        val r = VideoFindingsRegistry()
        r.submit(report("https://cdn.example.com/v480.mp4", mediaKey = "v1", heightPx = 480))
        r.submit(report("https://cdn.example.com/v720.mp4", mediaKey = "v1", heightPx = 720))

        val f = r.findings().single()
        assertEquals(
            setOf("https://cdn.example.com/v480.mp4", "https://cdn.example.com/v720.mp4"),
            f.renditions.map { it.url }.toSet()
        )
    }

    // ---------- ciclo de vida y capacidad ----------

    @Test
    fun `clear empties the page findings`() {
        val r = VideoFindingsRegistry()
        r.submit(report("https://cdn.example.com/a.mp4"))
        r.submit(report("https://cdn.example.com/b.mp4"))
        r.clear()
        assertTrue(r.findings().isEmpty())
        // Tras clear, un mismo mediaKey vuelve a crear hallazgo nuevo
        r.submit(report("https://cdn.example.com/c.mp4", mediaKey = "v1"))
        assertEquals("https://cdn.example.com/c.mp4", r.findings().single().primaryRendition.url)
    }

    @Test
    fun `registry is bounded to 64 findings`() {
        val r = VideoFindingsRegistry()
        repeat(70) { r.submit(report("https://cdn.example.com/v$it.mp4")) }
        assertEquals(64, r.findings().size)
    }

    // ---------- agrupación por resolución ----------

    @Test
    fun `identical title heuristic groups renditions of different resolutions`() {
        val r = VideoFindingsRegistry()
        r.submit(report("https://cdn.example.com/v480.mp4", title = "Película", heightPx = 480))
        r.submit(report("https://cdn.example.com/v720.mp4", title = "Película", heightPx = 720))

        val f = r.findings().single()
        assertEquals(setOf("480p", "720p"), f.renditions.map { it.resolutionLabel }.toSet())
    }

    @Test
    fun `guard same title plus same resolution plus same format does not group`() {
        val r = VideoFindingsRegistry()
        r.submit(report("https://cdn.example.com/x720.mp4", title = "Película", heightPx = 720))
        r.submit(report("https://cdn.example.com/y720.mp4", title = "Película", heightPx = 720))
        assertEquals(2, r.findings().size)
    }

    @Test
    fun `findings with empty title never group together`() {
        val r = VideoFindingsRegistry()
        r.submit(report("https://cdn.example.com/a480.mp4", title = "", heightPx = 480))
        r.submit(report("https://cdn.example.com/b720.mp4", title = "", heightPx = 720))
        assertEquals(2, r.findings().size)
    }

    @Test
    fun `renditions without resolution group under Auto label and are never discarded`() {
        val r = VideoFindingsRegistry()
        r.submit(report("https://cdn.example.com/a.mp4", mediaKey = "v1"))
        r.submit(report("https://cdn.example.com/b.m3u8", mediaKey = "v1", format = StreamFormat.HLS))

        val f = r.findings().single()
        assertTrue(f.renditions.all { it.resolutionLabel == "Auto" })
        assertEquals(2, f.renditions.size) // sin resolución no se descartan

        val options = r.qualityOptions(f)
        assertEquals(1, options.size)               // una entrada por etiqueta
        assertEquals("Auto", options[0].resolutionLabel)
        assertEquals("https://cdn.example.com/b.m3u8", options[0].url) // HLS preferido
    }

    @Test
    fun `qualityOptions returns one option per resolution label`() {
        val r = VideoFindingsRegistry()
        r.submit(report("https://cdn.example.com/v480.mp4", mediaKey = "v1", heightPx = 480))
        r.submit(report("https://cdn.example.com/v720.mp4", mediaKey = "v1", heightPx = 720))
        r.submit(report("https://cdn.example.com/master.m3u8", mediaKey = "v1", format = StreamFormat.HLS))

        val f = r.findings().single()
        assertEquals(listOf("720p", "480p", "Auto"),
            r.qualityOptions(f).map { it.resolutionLabel })
    }

    @Test
    fun `hls is preferred over dash over progressive at equal resolution`() {
        val r = VideoFindingsRegistry()
        r.submit(report("https://cdn.example.com/v720.mp4", title = "Peli", heightPx = 720))
        r.submit(report("https://cdn.example.com/v720.mpd", title = "Peli", heightPx = 720, format = StreamFormat.DASH))
        r.submit(report("https://cdn.example.com/v720.m3u8", title = "Peli", heightPx = 720, format = StreamFormat.HLS))

        val f = r.findings().single()
        val option = r.qualityOptions(f).single { it.resolutionLabel == "720p" }
        assertEquals("https://cdn.example.com/v720.m3u8", option.url)
        assertEquals(option, r.defaultOption(f))
    }

    @Test
    fun `session quality memory proposes the default when it matches an option`() {
        var lastChoice: String? = "480p"
        val r = VideoFindingsRegistry { lastChoice }
        r.submit(report("https://cdn.example.com/v480.mp4", mediaKey = "v1", heightPx = 480))
        r.submit(report("https://cdn.example.com/v720.mp4", mediaKey = "v1", heightPx = 720))
        r.submit(report("https://cdn.example.com/master.m3u8", mediaKey = "v1", format = StreamFormat.HLS))

        val f = r.findings().single()
        assertEquals("480p", r.defaultOption(f).resolutionLabel)

        lastChoice = "no-existe"                    // sin coincidencia → preferencia formato
        assertEquals(StreamFormat.HLS, r.defaultOption(f).format)
    }

    // ---------- sondeo acotado ----------

    @Test
    fun `probe result enriches finding with duration and manifest variants`() {
        val r = VideoFindingsRegistry()
        r.submit(report("https://cdn.example.com/master.m3u8", mediaKey = "v1", format = StreamFormat.HLS))
        val id = r.findings().single().id

        r.applyProbeResult(
            id, durationSec = 95.0,
            variants = listOf(
                Rendition("https://cdn.example.com/v720.m3u8", StreamFormat.HLS, "720p", 720, 2_500_000),
                Rendition("https://cdn.example.com/v480.m3u8", StreamFormat.HLS, "480p", 480, 1_200_000)
            )
        )

        val f = r.findings().single()
        assertTrue(f.probed)
        assertEquals(95.0, f.durationSec!!, 0.001)
        assertEquals(setOf("720p", "480p", "Auto"),
            r.qualityOptions(f).map { it.resolutionLabel }.toSet())
    }
}
