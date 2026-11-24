package com.example.dreamweb.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Firma JSON del bridge (contrato video-detection-bridge §2): el callback
 * solo se invoca cuando el reporte supera la validación de frontera.
 * Robolectric porque el parser usa org.json del framework.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SnifferBridgeTest {

    private var received: VideoReport? = null
    private lateinit var bridge: SnifferBridge

    @Before
    fun setUp() {
        received = null
        bridge = SnifferBridge { report -> received = report }
        bridge.currentPageUrl = "https://site.example.com/current"
    }

    @Test
    fun `json report with valid video url invokes callback`() {
        bridge.onVideoFound(
            """{"url":"https://cdn.example.com/live/master.m3u8","pageUrl":"https://site.example.com/p","title":"Ep","mediaKey":"v1"}"""
        )
        assertNotNull(received)
        assertEquals("https://cdn.example.com/live/master.m3u8", received!!.url)
        assertEquals("Ep", received!!.title)
        assertEquals("v1", received!!.mediaKey)
    }

    @Test
    fun `malformed json does not invoke callback`() {
        bridge.onVideoFound("{broken")
        bridge.onVideoFound("not json at all")
        bridge.onVideoFound("")
        assertNull(received)
    }

    @Test
    fun `json with rejected video url does not invoke callback`() {
        bridge.onVideoFound("""{"url":"https://cdn.example.com/seg_1.ts","pageUrl":"https://e.com"}""")
        bridge.onVideoFound("""{"url":"https://ads.doubleclick.com/banner.mp4","pageUrl":"https://e.com"}""")
        assertNull(received)
    }

    @Test
    fun `malicious urls inside json do not invoke callback`() {
        val malicious = listOf(
            "javascript:alert(1)",
            "data:text/html,<h1>x</h1>",
            "file:///sdcard/x.mp4",
            "blob:https://example.com/abc.mp4"
        )
        malicious.forEach {
            received = null
            bridge.onVideoFound("""{"url":"$it","pageUrl":"https://e.com"}""")
            assertNull("callback must not fire for: $it", received)
        }
    }

    @Test
    fun `callback not invoked after release even for valid report`() {
        bridge.release()
        bridge.onVideoFound(
            """{"url":"https://cdn.example.com/live/master.m3u8","pageUrl":"https://site.example.com/p"}"""
        )
        assertNull(received)
    }
}
