package com.example.dreamweb.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class SnifferBridgeTest {

    private var received: String? = null
    private lateinit var bridge: SnifferBridge

    @Before
    fun setUp() {
        received = null
        bridge = SnifferBridge { url -> received = url }
    }

    @Test
    fun `malicious urls do not invoke callback`() {
        val malicious = listOf(
            "javascript:alert(1)",
            "data:text/html,<h1>x</h1>",
            "file:///sdcard/x.mp4",
            "blob:https://example.com/abc.mp4",
            "plain text with no scheme",
            "",
            "https://example.com/page.html",          // https sin patrón de video
            "https://example.com/api/config.json"     // https sin patrón de video
        )
        malicious.forEach {
            received = null
            bridge.onVideoFound(it)
            assertNull("callback must not fire for: $it", received)
        }
    }

    @Test
    fun `valid https video url invokes callback`() {
        bridge.onVideoFound("https://cdn.example.com/live/master.m3u8")
        assertEquals("https://cdn.example.com/live/master.m3u8", received)
    }

    @Test
    fun `callback not invoked after release even for valid url`() {
        bridge.release()
        bridge.onVideoFound("https://cdn.example.com/live/master.m3u8")
        assertNull(received)
    }
}
