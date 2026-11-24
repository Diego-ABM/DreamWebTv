package com.example.dreamweb.managers

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoLaunchPayloadTest {

    private val defaultUa =
        "Mozilla/5.0 (Linux; Android 10; BRAVIA 4K VH2) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36"

    // El parse dual devuelve nullable; la rama legada nunca produce null.
    private fun parse(videoData: String) = VideoLaunchPayload.parse(videoData)!!

    @Test
    fun `parses full payload with url referer ua and cookies`() {
        val payload = parse(
            "https://cdn.example.com/master.m3u8|REFERER|https://site.example.com|UA|CustomAgent/1.0|COOKIES|session=abc; token=xyz"
        )
        assertEquals("https://cdn.example.com/master.m3u8", payload.url)
        assertEquals("https://site.example.com", payload.referer)
        assertEquals("CustomAgent/1.0", payload.userAgent)
        assertEquals("session=abc; token=xyz", payload.cookies)
    }

    @Test
    fun `parses url only with defaults`() {
        val payload = parse("https://cdn.example.com/movie.mp4")
        assertEquals("https://cdn.example.com/movie.mp4", payload.url)
        assertEquals("", payload.referer)
        assertEquals(defaultUa, payload.userAgent)
        assertEquals("", payload.cookies)
    }

    @Test
    fun `parses url and referer without ua or cookies`() {
        val payload = parse(
            "https://cdn.example.com/live.m3u8|REFERER|https://ref.example.com"
        )
        assertEquals("https://cdn.example.com/live.m3u8", payload.url)
        assertEquals("https://ref.example.com", payload.referer)
        assertEquals(defaultUa, payload.userAgent)
        assertEquals("", payload.cookies)
    }

    @Test
    fun `parses url and ua without referer or cookies`() {
        val payload = parse(
            "https://cdn.example.com/live.m3u8|UA|AnotherAgent/2.0"
        )
        assertEquals("https://cdn.example.com/live.m3u8", payload.url)
        assertEquals("", payload.referer)
        assertEquals("AnotherAgent/2.0", payload.userAgent)
        assertEquals("", payload.cookies)
    }

    @Test
    fun `parses url and cookies without referer or ua`() {
        val payload = parse(
            "https://cdn.example.com/live.m3u8|COOKIES|a=1"
        )
        assertEquals("https://cdn.example.com/live.m3u8", payload.url)
        assertEquals("", payload.referer)
        assertEquals(defaultUa, payload.userAgent)
        assertEquals("a=1", payload.cookies)
    }

    @Test
    fun `parses referer and cookies without ua`() {
        val payload = parse(
            "https://cdn.example.com/live.m3u8|REFERER|https://ref.example.com|COOKIES|b=2"
        )
        assertEquals("https://cdn.example.com/live.m3u8", payload.url)
        assertEquals("https://ref.example.com", payload.referer)
        assertEquals(defaultUa, payload.userAgent)
        assertEquals("b=2", payload.cookies)
    }
}
