package com.example.dreamweb.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoUrlValidatorTest {

    @Test
    fun `accepts http and https urls with video patterns`() {
        val accepted = listOf(
            "https://cdn.example.com/master.m3u8",
            "http://stream.example.com/live/playlist.m3u8",
            "https://cdn.example.com/movie.mp4",
            "https://cdn.example.com/dash/stream.mpd",
            "https://cdn.example.com/video.mkv",
            "https://cdn.example.com/video.webm",
            "https://cdn.example.com/dash/manifest?token=abc",
            "https://cdn.example.com/media.m3u8?token=xyz",
            "https://player.zilla-networks.com/m3u8/a408900f35f23c98f4fb83e616afedb8"
        )
        accepted.forEach {
            assertTrue("expected accepted: $it", VideoUrlValidator.isVideoUrl(it))
        }
    }

    @Test
    fun `rejects beacons embedding the stream url in the query`() {
        // Caso real: el ping de jwplayer (prd.jwpltx.com) lleva la URL del
        // manifiesto en mu= — los patrones solo miran el path.
        val ping = "https://prd.jwpltx.com/v1/jwplayer6/ping.gif" +
            "?e=pa&mu=https%3A%2F%2Fplayer.zilla-networks.com%2Fm3u8%2Fabc&pp=hlsjs"
        assertFalse(VideoUrlValidator.isVideoUrl(ping))
    }

    @Test
    fun `rejects non-http schemes`() {
        val rejected = listOf(
            "javascript:alert(1)",
            "data:text/html,<h1>x</h1>",
            "file:///sdcard/video.mp4",
            "blob:https://example.com/abc-123.mp4",
            "intent://example.com/video.mp4#Intent;end",
            "vbscript:msgbox(1)"
        )
        rejected.forEach {
            assertFalse("expected rejected: $it", VideoUrlValidator.isVideoUrl(it))
        }
    }

    @Test
    fun `rejects plain text empty and malformed urls`() {
        val rejected = listOf(
            "",
            "   ",
            "just some text",
            "not a url at all.m3u8",
            "http://",
            "https://",
            "://missing-scheme.com/video.mp4"
        )
        rejected.forEach {
            assertFalse("expected rejected: '$it'", VideoUrlValidator.isVideoUrl(it))
        }
    }

    @Test
    fun `rejects https urls without video pattern`() {
        val rejected = listOf(
            "https://example.com/index.html",
            "https://example.com/image.png",
            "https://example.com/api/data.json"
        )
        rejected.forEach {
            assertFalse("expected rejected: $it", VideoUrlValidator.isVideoUrl(it))
        }
    }

    @Test
    fun `rejects segments`() {
        val rejected = listOf(
            "https://cdn.example.com/live/chunk0001.ts",
            "https://cdn.example.com/segment/001.m4s",
            "https://cdn.example.com/video.mp4?range=0-1024"
        )
        rejected.forEach {
            assertFalse("expected rejected: $it", VideoUrlValidator.isVideoUrl(it))
        }
    }

    @Test
    fun `rejects ads and trackers`() {
        val rejected = listOf(
            "https://adsystem.example.com/ad.m3u8",
            "https://adserver.example.com/banner.mp4",
            "https://analytics.example.com/beacon.mp4",
            "https://doubleclick.net/ad.m3u8",
            "https://tracker.example.com/pixel.mp4",
            "https://googlesyndication.com/ad.m3u8",
            "https://example.com/ads/pre-roll.mp4"
        )
        rejected.forEach {
            assertFalse("expected rejected: $it", VideoUrlValidator.isVideoUrl(it))
        }
    }
}
