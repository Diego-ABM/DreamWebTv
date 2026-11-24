package com.example.dreamweb.ui

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Regresión: en media3 1.2.0 la selección de MediaSource por mimeType es por
 * string exacto — "application/vnd.apple.mpegurl" cae a Progressive y el
 * stream falla con PARSING_CONTAINER_UNSUPPORTED (bug observado en TV).
 * El hint de lanzamiento debe usar la constante APPLICATION_M3U8.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaSourceHintTest {

    private val factory = DefaultMediaSourceFactory(RuntimeEnvironment.getApplication())
    private val opaqueHlsUri = Uri.parse("https://player.zilla-networks.com/m3u8/abc123")

    private fun sourceFor(mime: String?) = factory.createMediaSource(
        MediaItem.Builder().setUri(opaqueHlsUri).setMimeType(mime).build()
    ).javaClass

    @Test
    fun `APPLICATION_M3U8 constant selects HlsMediaSource`() {
        assertTrue(sourceFor(MimeTypes.APPLICATION_M3U8) == HlsMediaSource::class.java)
    }

    @Test
    fun `vnd apple mpegurl string does NOT select HLS in media3 1_2_0`() {
        assertTrue(sourceFor("application/vnd.apple.mpegurl") == ProgressiveMediaSource::class.java)
    }

    @Test
    fun `extensionless url without hint falls back to progressive`() {
        assertTrue(sourceFor(null) == ProgressiveMediaSource::class.java)
    }
}
