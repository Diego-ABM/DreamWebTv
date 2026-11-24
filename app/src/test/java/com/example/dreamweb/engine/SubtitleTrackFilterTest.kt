package com.example.dreamweb.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM puro: filtro de pistas de subtítulos soportadas por Media3 (FR-020).
 */
class SubtitleTrackFilterTest {

    @Test
    fun `supported formats survive with their media3 mimeType`() {
        val vtt = SubtitleTrackFilter.filter("https://cdn.example.com/s/es.vtt")!!
        assertEquals("text/vtt", vtt.mimeType)
        val srt = SubtitleTrackFilter.filter("https://cdn.example.com/s/es.srt")!!
        assertEquals("application/x-subrip", srt.mimeType)
        val ttml = SubtitleTrackFilter.filter("https://cdn.example.com/s/es.ttml")!!
        assertEquals("application/ttml+xml", ttml.mimeType)
        val dfxp = SubtitleTrackFilter.filter("https://cdn.example.com/s/es.dfxp")!!
        assertEquals("application/ttml+xml", dfxp.mimeType)
    }

    @Test
    fun `query and fragment do not break extension detection`() {
        val t = SubtitleTrackFilter.filter("https://cdn.example.com/s/es.vtt?lang=es#frag")!!
        assertEquals("text/vtt", t.mimeType)
    }

    @Test
    fun `unsupported formats are omitted silently`() {
        listOf(
            "https://cdn.example.com/s/es.ass",
            "https://cdn.example.com/s/es.ssa",
            "https://cdn.example.com/s/es.sub",
            "https://cdn.example.com/s/es",            // sin extensión
            "https://cdn.example.com/s/es.txt"
        ).forEach {
            assertNull("should omit $it", SubtitleTrackFilter.filter(it))
        }
    }

    @Test
    fun `non-http schemes and invalid urls are omitted`() {
        listOf(
            "javascript:alert(1)",
            "data:text/vtt,WEBVTT",
            "blob:https://e.com/x.vtt",
            "ftp://cdn.example.com/s/es.vtt",
            "file:///sdcard/es.vtt",
            "",
            "notaurl.vtt"
        ).forEach {
            assertNull("should omit $it", SubtitleTrackFilter.filter(it))
        }
    }

    @Test
    fun `language and label are preserved when present`() {
        val t = SubtitleTrackFilter.filter(
            "https://cdn.example.com/s/es.vtt",
            language = "es", label = "Español"
        )!!
        assertEquals("es", t.language)
        assertEquals("Español", t.label)
    }

    @Test
    fun `language and label may be absent`() {
        val t = SubtitleTrackFilter.filter("https://cdn.example.com/s/es.vtt")!!
        assertNull(t.language)
        assertNull(t.label)
        assertNotNull(t.url)
    }
}
