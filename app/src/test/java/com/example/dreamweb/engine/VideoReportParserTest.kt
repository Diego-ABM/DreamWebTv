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

    // ---------- validez básica ----------

    @Test
    fun `minimal report with url and pageUrl is valid`() {
        val r = VideoReportParser.parse("""{"url":"$videoUrl","pageUrl":"$pageUrl"}""")
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
        val r = VideoReportParser.parse(json)!!
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
        val mp4 = VideoReportParser.parse(
            """{"url":"https://cdn.example.com/v/clip.mp4","pageUrl":"$pageUrl"}"""
        )!!
        assertEquals(StreamFormat.PROGRESSIVE, mp4.format)
        val mpd = VideoReportParser.parse(
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
        assertNull(VideoReportParser.parse(json))
    }

    @Test
    fun `malformed or non-object json returns null`() {
        assertNull(VideoReportParser.parse("not json"))
        assertNull(VideoReportParser.parse("[1,2,3]"))
        assertNull(VideoReportParser.parse("\"a string\""))
        assertNull(VideoReportParser.parse("42"))
        assertNull(VideoReportParser.parse("{"))
        assertNull(VideoReportParser.parse(""))
    }

    @Test
    fun `url failing VideoUrlValidator rejects the report`() {
        listOf(
            "https://cdn.example.com/seg_001.ts",          // segmento
            "https://ads.doubleclick.com/banner.mp4",      // ad/tracker
            "https://adsystem.example.com/roll.mp4",       // ad
            "javascript:alert(1)",
            "blob:https://x.example.com/abc.mp4",
            "https://example.com/index.html"               // no es video
        ).forEach { bad ->
            assertNull("should reject $bad",
                VideoReportParser.parse("""{"url":"$bad","pageUrl":"$pageUrl"}"""))
        }
    }

    // ---------- pageUrl / referer ----------

    @Test
    fun `non-http pageUrl keeps the current page url as referer`() {
        val current = "https://site.example.com/current"
        listOf("javascript:x", "blob:https://e.com/a", "ftp://e.com/x", "", "notaurl")
            .forEach { injected ->
                val r = VideoReportParser.parse(
                    """{"url":"$videoUrl","pageUrl":"$injected"}""", current
                )!!
                assertEquals("pageUrl inyectada '$injected' no debe ganar",
                    current, r.pageUrl)
            }
    }

    @Test
    fun `missing pageUrl keeps the current page url`() {
        val current = "https://site.example.com/current"
        val r = VideoReportParser.parse("""{"url":"$videoUrl"}""", current)!!
        assertEquals(current, r.pageUrl)
    }

    // ---------- tipos estrictos y rangos ----------

    @Test
    fun `wrong types omit the field without invalidating the report`() {
        val r = VideoReportParser.parse("""
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
        val r = VideoReportParser.parse(
            """{"url":"$videoUrl","pageUrl":"$pageUrl","title":"${"t".repeat(600)}"}"""
        )!!
        assertEquals(512, r.title.length)
    }

    @Test
    fun `width and height outside 16 to 8192 are omitted`() {
        val r = VideoReportParser.parse(
            """{"url":"$videoUrl","pageUrl":"$pageUrl","width":8,"height":9000}"""
        )!!
        assertNull(r.widthPx)
        assertNull(r.heightPx)
        val ok = VideoReportParser.parse(
            """{"url":"$videoUrl","pageUrl":"$pageUrl","width":16,"height":8192}"""
        )!!
        assertEquals(16, ok.widthPx)
        assertEquals(8192, ok.heightPx)
    }

    @Test
    fun `negative duration and position are omitted`() {
        val r = VideoReportParser.parse(
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
        val r = VideoReportParser.parse(json)!!
        assertEquals(mapOf("X-Good" to "yes"), r.headers)
    }

    @Test
    fun `headers are capped at 32 entries`() {
        val entries = (1..40).joinToString(",") { """"X-H$it":"v$it"""" }
        val r = VideoReportParser.parse(
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
        val r = VideoReportParser.parse(json)!!
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
        val r = VideoReportParser.parse(
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
        val r = VideoReportParser.parse(json)!!
        assertEquals(4, r.subtitles.size)
        assertEquals("text/vtt", r.subtitles[0].mimeType)
        assertEquals("application/x-subrip", r.subtitles[1].mimeType)
        assertEquals("application/ttml+xml", r.subtitles[2].mimeType)
        assertEquals("application/ttml+xml", r.subtitles[3].mimeType)
    }
}
