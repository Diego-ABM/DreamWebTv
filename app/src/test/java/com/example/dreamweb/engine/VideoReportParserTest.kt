package com.example.dreamweb.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Frontera no confiable JS→Kotlin: VideoReportParser.parse (Robolectric por
 * org.json, patrón de HistoryManagerTest).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VideoReportParserTest {

    private val videoUrl = "https://cdn.example.com/v/master.m3u8"
    private val pageUrl = "https://site.example.com/watch"

    private fun parseReport(json: String, fallback: String = ""): VideoReport? =
        VideoReportParser
            .parse(json, fallback) as? VideoReport

    // ---------- validez básica ----------

    @Test
    fun `minimal report with url and pageUrl is valid`() {
        val r = parseReport("""{"url":"$videoUrl","pageUrl":"$pageUrl"}""")
        assertNotNull(r)
        r!!
        assertEquals(videoUrl, r.url)
        assertEquals(pageUrl, r.pageUrl)
        assertEquals("", r.title)
        assertNull(r.mimeType)
        assertNull(r.widthPx)
        assertNull(r.heightPx)
        assertNull(r.durationSec)
        assertNull(r.positionSec)
        assertTrue(r.headers.isEmpty())
        assertTrue(r.subtitles.isEmpty())
        assertNull(r.mediaKey)
        assertEquals(DetectionSource.DOM, r.source)
    }

    @Test
    fun `full schema report parses every field`() {
        val json = """
        {
          "url": "$videoUrl",
          "pageUrl": "$pageUrl",
          "title": "Episodio 1",
          "mimeType": "application/vnd.apple.mpegurl",
          "width": 1280, "height": 720,
          "durationSec": 1320.5,
          "positionSec": 41.2,
          "headers": {"X-Client": "tv", "Authorization": "Bearer abc"},
          "subtitles": [{"url":"https://cdn.example.com/s/es.vtt","language":"es","label":"Español"}],
          "mediaKey": "v3"
        }
        """.trimIndent()
        val r = parseReport(json)!!
        assertEquals("Episodio 1", r.title)
        assertEquals("application/vnd.apple.mpegurl", r.mimeType)
        assertEquals(StreamFormat.HLS, r.format)
        assertEquals(1280, r.widthPx)
        assertEquals(720, r.heightPx)
        assertEquals(1320.5, r.durationSec!!, 0.001)
        assertEquals(41.2, r.positionSec!!, 0.001)
        assertEquals(mapOf("X-Client" to "tv", "Authorization" to "Bearer abc"), r.headers)
        assertEquals(1, r.subtitles.size)
        assertEquals("es", r.subtitles[0].language)
        assertEquals("Español", r.subtitles[0].label)
        assertEquals("v3", r.mediaKey)
    }

    @Test
    fun `format is derived from extension when mimeType missing`() {
        val mp4 = parseReport(
            """{"url":"https://cdn.example.com/v/clip.mp4","pageUrl":"$pageUrl"}"""
        )!!
        assertEquals(StreamFormat.PROGRESSIVE, mp4.format)
        val mpd = parseReport(
            """{"url":"https://cdn.example.com/v/manifest.mpd","pageUrl":"$pageUrl"}"""
        )!!
        assertEquals(StreamFormat.DASH, mpd.format)
    }

    // ---------- tamaño y forma ----------

    @Test
    fun `payload over 64KB is discarded`() {
        val bigTitle = "x".repeat(70 * 1024)
        val json = """{"url":"$videoUrl","pageUrl":"$pageUrl","title":"$bigTitle"}"""
        assertTrue(json.toByteArray().size > 64 * 1024)
        assertNull(parseReport(json))
    }

    @Test
    fun `malformed or non-object json returns null`() {
        assertNull(parseReport("not json"))
        assertNull(parseReport("[1,2,3]"))
        assertNull(parseReport("\"a string\""))
        assertNull(parseReport("42"))
        assertNull(parseReport("{"))
        assertNull(parseReport(""))
    }

    @Test
    fun `url failing the DOM boundary rejects the report`() {
        listOf(
            "https://cdn.example.com/seg_001.ts",          // segmento
            "https://ads.doubleclick.com/banner.mp4",      // ad/tracker
            "https://adsystem.example.com/roll.mp4",       // ad
            "javascript:alert(1)",
            "blob:https://x.example.com/abc.mp4",
            "https://cdn.example.com/segs/abc/004.html"    // segmento disfrazado
        ).forEach { bad ->
            assertNull("should reject $bad",
                parseReport("""{"url":"$bad","pageUrl":"$pageUrl"}"""))
        }
    }

    @Test
    fun `DOM channel accepts media urls without extension hint`() {
        // El contexto del elemento/player es la evidencia; muchos manifiestos
        // reales llegan opacos: /m3u8/<id>, endpoints de sesión, .html…
        listOf(
            "https://player.zilla-networks.com/m3u8/1a2b3c4d",
            "https://cdn.example.com/session/abc123",
            "https://cdn.example.com/v/master.m3u"
        ).forEach { url ->
            assertNotNull("should accept $url",
                parseReport("""{"url":"$url","pageUrl":"$pageUrl"}"""))
        }
        // Y el formato se infiere del contenido de la URL, no del literal.
        val hls = parseReport(
            """{"url":"https://player.zilla-networks.com/m3u8/1a2b3c","pageUrl":"$pageUrl"}"""
        )!!
        assertEquals(StreamFormat.HLS, hls.format)
    }

    // ---------- pageUrl / referer ----------

    @Test
    fun `non-http pageUrl keeps the current page url as referer`() {
        val current = "https://site.example.com/current"
        listOf("javascript:x", "blob:https://e.com/a", "ftp://e.com/x", "", "notaurl")
            .forEach { injected ->
                val r = parseReport(
                    """{"url":"$videoUrl","pageUrl":"$injected"}""", current
                )!!
                assertEquals("pageUrl inyectada '$injected' no debe ganar",
                    current, r.pageUrl)
            }
    }

    @Test
    fun `missing pageUrl keeps the current page url`() {
        val current = "https://site.example.com/current"
        val r = parseReport("""{"url":"$videoUrl"}""", current)!!
        assertEquals(current, r.pageUrl)
    }

    // ---------- tipos estrictos y rangos ----------

    @Test
    fun `wrong types omit the field without invalidating the report`() {
        val r = parseReport("""
        {"url":"$videoUrl","pageUrl":"$pageUrl",
         "title":123,"mimeType":{},"width":"big","height":true,
         "durationSec":"x","positionSec":[],"mediaKey":5}
        """.trimIndent())!!
        assertEquals("", r.title)
        assertNull(r.mimeType)
        assertNull(r.widthPx)
        assertNull(r.heightPx)
        assertNull(r.durationSec)
        assertNull(r.positionSec)
        assertNull(r.mediaKey)
    }

    @Test
    fun `title longer than 512 chars is truncated`() {
        val r = parseReport(
            """{"url":"$videoUrl","pageUrl":"$pageUrl","title":"${"t".repeat(600)}"}"""
        )!!
        assertEquals(512, r.title.length)
    }

    @Test
    fun `width and height outside 16 to 8192 are omitted`() {
        val r = parseReport(
            """{"url":"$videoUrl","pageUrl":"$pageUrl","width":8,"height":9000}"""
        )!!
        assertNull(r.widthPx)
        assertNull(r.heightPx)
        val ok = parseReport(
            """{"url":"$videoUrl","pageUrl":"$pageUrl","width":16,"height":8192}"""
        )!!
        assertEquals(16, ok.widthPx)
        assertEquals(8192, ok.heightPx)
    }

    @Test
    fun `negative duration and position are omitted`() {
        val r = parseReport(
            """{"url":"$videoUrl","pageUrl":"$pageUrl","durationSec":-1.5,"positionSec":-0.1}"""
        )!!
        assertNull(r.durationSec)
        assertNull(r.positionSec)
    }

    // ---------- headers ----------

    @Test
    fun `managed and malformed headers are dropped`() {
        val json = """
        {"url":"$videoUrl","pageUrl":"$pageUrl",
         "headers":{
           "Host":"evil.example","Content-Length":"5","Range":"bytes=0-1",
           "User-Agent":"fake","Referer":"https://evil.example","Cookie":"s=1",
           "Origin":"https://evil.example",
           "Sec-Fetch-Mode":"navigate","Sec-Fetch-Site":"none",
           "X-Good":"yes","Bad Name":"x","Bad@Name":"x",
           "X-Evil":"a\rb\n","X-Nul":"a\u0000b"}}
        """.trimIndent()
        val r = parseReport(json)!!
        assertEquals(mapOf("X-Good" to "yes"), r.headers)
    }

    @Test
    fun `headers are capped at 32 entries`() {
        val entries = (1..40).joinToString(",") { """"X-H$it":"v$it"""" }
        val r = parseReport(
            """{"url":"$videoUrl","pageUrl":"$pageUrl","headers":{$entries}}"""
        )!!
        assertEquals(32, r.headers.size)
    }

    @Test
    fun `sanitizeHeaders is reusable for the network channel`() {
        val raw = mapOf(
            "Accept" to "*/*",
            "X-Requested-With" to "XMLHttpRequest",
            "User-Agent" to "should-not-pass",
            "Sec-Fetch-Dest" to "video",
            "Cookie" to "s=1",
            "Host" to "h",
            "Range" to "bytes=0-1",
            "Content-Length" to "9",
            "Referer" to "r",
            "Origin" to "o",
            "X-Bad Space" to "x",
            "X-Evil" to "a\nb"
        )
        val out = VideoReportParser.sanitizeHeaders(raw)
        assertEquals(
            mapOf("Accept" to "*/*", "X-Requested-With" to "XMLHttpRequest"),
            out
        )
    }

    // ---------- subtítulos ----------

    @Test
    fun `unsupported and non-http subtitle tracks are dropped without error`() {
        val json = """
        {"url":"$videoUrl","pageUrl":"$pageUrl",
         "subtitles":[
           {"url":"https://cdn.example.com/s/1.vtt","language":"es"},
           {"url":"https://cdn.example.com/s/x.ass"},
           {"url":"javascript:x.vtt"},
           {"url":"ftp://cdn.example.com/s/y.srt"},
           {"url":"https://cdn.example.com/s/ok.srt","label":"OK"}]}
        """.trimIndent()
        val r = parseReport(json)!!
        assertEquals(2, r.subtitles.size)
        assertEquals("https://cdn.example.com/s/1.vtt", r.subtitles[0].url)
        assertEquals("es", r.subtitles[0].language)
        assertEquals("https://cdn.example.com/s/ok.srt", r.subtitles[1].url)
        assertEquals("OK", r.subtitles[1].label)
    }

    @Test
    fun `subtitles are capped at 8`() {
        val subs = (1..10).joinToString(",") {
            """{"url":"https://cdn.example.com/s/$it.vtt"}"""
        }
        val r = parseReport(
            """{"url":"$videoUrl","pageUrl":"$pageUrl","subtitles":[$subs]}"""
        )!!
        assertEquals(8, r.subtitles.size)
        assertEquals("https://cdn.example.com/s/8.vtt", r.subtitles.last().url)
    }

    @Test
    fun `supported subtitle formats keep medias mime types`() {
        val json = """
        {"url":"$videoUrl","pageUrl":"$pageUrl",
         "subtitles":[
           {"url":"https://cdn.example.com/a.vtt"},
           {"url":"https://cdn.example.com/b.srt"},
           {"url":"https://cdn.example.com/c.ttml"},
           {"url":"https://cdn.example.com/d.dfxp"}]}
        """.trimIndent()
        val r = parseReport(json)!!
        assertEquals(4, r.subtitles.size)
        assertEquals("text/vtt", r.subtitles[0].mimeType)
        assertEquals("application/x-subrip", r.subtitles[1].mimeType)
        assertEquals("application/ttml+xml", r.subtitles[2].mimeType)
        assertEquals("application/ttml+xml", r.subtitles[3].mimeType)
    }

    // ---------- playstate (estado del <video>) ----------

    @Test
    fun `playstate message parses as PlayState with rect and viewport`() {
        val m = VideoReportParser.parse("""
        {"type":"playstate","playing":true,"mediaKey":"v2",
         "rect":{"x":10.5,"y":20.0,"w":640,"h":360},
         "vw":1280,"vh":720,"inIframe":false,"pageUrl":"$pageUrl"}
        """.trimIndent())
        val s = m as? PlayState
        assertNotNull(s)
        s!!
        assertTrue(s.playing)
        assertEquals("v2", s.mediaKey)
        assertEquals(10.5, s.rect!!.x, 0.001)
        assertEquals(20.0, s.rect!!.y, 0.001)
        assertEquals(640.0, s.rect!!.w, 0.001)
        assertEquals(360.0, s.rect!!.h, 0.001)
        assertEquals(1280, s.viewportWidth)
        assertEquals(720, s.viewportHeight)
        assertTrue(!s.inIframe)
        assertEquals(pageUrl, s.pageUrl)
    }

    @Test
    fun `playstate without playing flag is discarded`() {
        assertNull(VideoReportParser.parse(
            """{"type":"playstate","mediaKey":"v1"}"""))
        assertNull(VideoReportParser.parse(
            """{"type":"playstate","playing":"yes"}"""))
    }

    @Test
    fun `playstate tolerates missing rect and wrong types`() {
        val s = VideoReportParser.parse("""
        {"type":"playstate","playing":false,"mediaKey":7,
         "rect":{"x":1,"y":"bad","w":100,"h":100},
         "vw":"wide","inIframe":"yes","pageUrl":"javascript:x"}
        """.trimIndent(), "https://fallback.example.com/p") as? PlayState
        assertNotNull(s)
        s!!
        assertTrue(!s.playing)
        assertNull(s.mediaKey)
        assertNull(s.rect)
        assertNull(s.viewportWidth)
        assertTrue(!s.inIframe)
        assertEquals("https://fallback.example.com/p", s.pageUrl)
    }

    @Test
    fun `playstate rejects degenerate or huge rect`() {
        val s = VideoReportParser.parse("""
        {"type":"playstate","playing":true,
         "rect":{"x":0,"y":0,"w":0,"h":-5},"pageUrl":"$pageUrl"}
        """.trimIndent()) as? PlayState
        assertNotNull(s)
        assertNull(s!!.rect)
    }
}
