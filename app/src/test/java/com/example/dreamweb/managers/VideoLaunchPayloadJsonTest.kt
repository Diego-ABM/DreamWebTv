package com.example.dreamweb.managers

import com.example.dreamweb.engine.SubtitleTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Esquema JSON v2 del payload navegador→player y parse dual legado/JSON
 * (contracts/playback-context-payload.md). Robolectric por org.json.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VideoLaunchPayloadJsonTest {

    private val url = "https://cdn.example.com/v/master.m3u8"

    @Test
    fun `json producer roundtrip keeps every field`() {
        val payload = VideoLaunchPayload(
            url = url,
            referer = "https://site.example.com/watch",
            userAgent = "CustomAgent/1.0",
            cookies = "session=abc; pref=tv",
            headers = mapOf("X-Requested-With" to "XMLHttpRequest", "Authorization" to "Bearer t"),
            subtitles = listOf(
                SubtitleTrack("https://cdn.example.com/s/es.vtt", "text/vtt", "es", "Español")
            ),
            startPositionMs = 41_200,
            mimeType = "application/vnd.apple.mpegurl"
        )

        val parsed = VideoLaunchPayload.parse(payload.toJson())!!
        assertEquals(payload.url, parsed.url)
        assertEquals(payload.referer, parsed.referer)
        assertEquals(payload.userAgent, parsed.userAgent)
        assertEquals(payload.cookies, parsed.cookies)
        assertEquals(payload.headers, parsed.headers)
        assertEquals(payload.subtitles, parsed.subtitles)
        assertEquals(payload.startPositionMs, parsed.startPositionMs)
        assertEquals(payload.mimeType, parsed.mimeType)
    }

    @Test
    fun `producer emits schema version 2`() {
        val json = VideoLaunchPayload(url = url).toJson()
        assertTrue(json.contains("\"v\":2"))
    }

    @Test
    fun `json with missing fields falls back to defaults`() {
        val parsed = VideoLaunchPayload.parse("""{"v":2,"url":"$url"}""")!!
        assertEquals("", parsed.referer)
        assertEquals(VideoLaunchPayload.DEFAULT_USER_AGENT, parsed.userAgent)
        assertEquals("", parsed.cookies)
        assertTrue(parsed.headers.isEmpty())
        assertTrue(parsed.subtitles.isEmpty())
        assertEquals(0, parsed.startPositionMs)
        assertNull(parsed.mimeType)
    }

    @Test
    fun `unknown json fields are ignored`() {
        val parsed = VideoLaunchPayload.parse(
            """{"v":2,"url":"$url","futureField":{"a":1},"other":[1,2]}"""
        )!!
        assertEquals(url, parsed.url)
    }

    @Test
    fun `negative startPositionMs clamps to zero`() {
        val parsed = VideoLaunchPayload.parse(
            """{"v":2,"url":"$url","startPositionMs":-500}"""
        )!!
        assertEquals(0, parsed.startPositionMs)
    }

    @Test
    fun `json headers with control characters are rejected`() {
        val parsed = VideoLaunchPayload.parse(
            """{"v":2,"url":"$url","headers":{"X-Ok":"1","X-Bad":"a\nb","Bad Name":"x"}}"""
        )!!
        assertEquals(mapOf("X-Ok" to "1"), parsed.headers)
    }

    @Test
    fun `managed header names inside headers map are dropped`() {
        val parsed = VideoLaunchPayload.parse(
            """{"v":2,"url":"$url","headers":{"Referer":"https://evil.example","Cookie":"s=1","X-Good":"y"}}"""
        )!!
        assertEquals(mapOf("X-Good" to "y"), parsed.headers)
    }

    @Test
    fun `unsupported subtitle tracks inside payload are dropped`() {
        val parsed = VideoLaunchPayload.parse(
            """{"v":2,"url":"$url","subtitles":[
                {"url":"https://cdn.example.com/a.vtt","mimeType":"text/vtt","language":"es"},
                {"url":"https://cdn.example.com/b.ass"},
                {"url":"javascript:x.vtt"}]}"""
        )!!
        assertEquals(1, parsed.subtitles.size)
        assertEquals("https://cdn.example.com/a.vtt", parsed.subtitles[0].url)
    }

    // ---------- parse dual ----------

    @Test
    fun `input starting with brace after trim goes to json parser`() {
        val parsed = VideoLaunchPayload.parse("   {\"v\":2,\"url\":\"$url\"}")!!
        assertEquals(url, parsed.url)
        assertEquals(VideoLaunchPayload.DEFAULT_USER_AGENT, parsed.userAgent)
    }

    @Test
    fun `legacy pipe format parses unchanged`() {
        val parsed = VideoLaunchPayload.parse(
            "$url|REFERER|https://ref.example.com|UA|Agent/2|COOKIES|a=1"
        )!!
        assertEquals(url, parsed.url)
        assertEquals("https://ref.example.com", parsed.referer)
        assertEquals("Agent/2", parsed.userAgent)
        assertEquals("a=1", parsed.cookies)
        // campos nuevos con defaults seguros en la rama legada
        assertTrue(parsed.headers.isEmpty())
        assertTrue(parsed.subtitles.isEmpty())
        assertEquals(0, parsed.startPositionMs)
        assertNull(parsed.mimeType)
    }

    @Test
    fun `malformed json returns null`() {
        assertNull(VideoLaunchPayload.parse("{not json"))
        assertNull(VideoLaunchPayload.parse("{}"))
    }

    @Test
    fun `json without valid http url returns null`() {
        assertNull(VideoLaunchPayload.parse("""{"v":2,"url":"javascript:alert(1)"}"""))
        assertNull(VideoLaunchPayload.parse("""{"v":2,"url":"blob:https://e.com/a.mp4"}"""))
        assertNull(VideoLaunchPayload.parse("""{"v":2}"""))
        assertNull(VideoLaunchPayload.parse("""{"v":2,"url":123}"""))
    }
}
