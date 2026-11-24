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

    private var received: ParsedMessage? = null
    private lateinit var bridge: SnifferBridge

    @Before
    fun setUp() {
        received = null
        bridge = SnifferBridge { msg -> received = msg }
        bridge.currentPageUrl = "https://site.example.com/current"
    }

    @Test
    fun `json report with valid video url invokes callback`() {
        bridge.onVideoFound(
            """{"url":"https://cdn.example.com/live/master.m3u8","pageUrl":"https://site.example.com/p","title":"Ep","mediaKey":"v1"}"""
        )
        val r = received as? VideoReport
        assertNotNull(r)
        assertEquals("https://cdn.example.com/live/master.m3u8", r!!.url)
        assertEquals("Ep", r.title)
        assertEquals("v1", r.mediaKey)
    }

    @Test
    fun `opaque media url without extension invokes callback`() {
        // Caso real: players que sirven el manifiesto como /m3u8/<id> sin
        // extensión literal (player.zilla-networks.com).
        bridge.onVideoFound(
            """{"url":"https://player.zilla-networks.com/m3u8/1a2b3c","pageUrl":"https://e.com"}"""
        )
        assertNotNull(received as? VideoReport)
    }

    @Test
    fun `playstate message invokes callback as PlayState`() {
        bridge.onVideoFound(
            """{"type":"playstate","playing":true,"mediaKey":"v1","inIframe":true,"pageUrl":"https://e.com"}"""
        )
        val s = received as? PlayState
        assertNotNull(s)
        assertEquals(true, s!!.playing)
        assertEquals("v1", s.mediaKey)
        assertEquals(true, s.inIframe)
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
