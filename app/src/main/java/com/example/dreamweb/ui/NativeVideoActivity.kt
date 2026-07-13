package com.example.dreamweb.ui

import android.os.Bundle
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.example.dreamweb.databinding.ActivityNativeVideoBinding
import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

@OptIn(UnstableApi::class)
class NativeVideoActivity : AppCompatActivity() {
    private lateinit var binding: ActivityNativeVideoBinding
    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNativeVideoBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val videoUrl = intent.getStringExtra("EXTRA_VIDEO_URL") ?: run {
            finish()
            return
        }

        setupPlayer(videoUrl)
    }

    private fun setupPlayer(videoData: String) {
        // Extraer URL, Referer y UserAgent
        val partsUA = videoData.split("|UA|")
        val mainData = partsUA[0]
        val customUA = if (partsUA.size > 1) partsUA[1] else "Mozilla/5.0 (Linux; Android 10; BRAVIA 4K VH2) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36"

        val parts = mainData.split("|REFERER|")
        val url = parts[0]
        val referer = if (parts.size > 1) parts[1] else ""

        // Configuración de OkHttp con Referer y SSL laxo
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })

        val sslContext = SSLContext.getInstance("SSL")
        sslContext.init(null, trustAllCerts, SecureRandom())

        val okHttpClient = OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        val dataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
            .setUserAgent(customUA)
        
        // Agregar cabeceras críticas (Referer, Origin, etc)
        val headers = mutableMapOf<String, String>()
        if (referer.isNotEmpty()) {
            headers["Referer"] = referer
            val origin = try {
                val uri = android.net.Uri.parse(referer)
                "${uri.scheme}://${uri.host}"
            } catch (ignored: Exception) {
                referer
            }
            headers["Origin"] = origin
        }
        // Algunas CDNs requieren estas cabeceras para streaming
        headers["Sec-Fetch-Mode"] = "cors"
        headers["Sec-Fetch-Site"] = "cross-site"
        headers["Accept"] = "*/*"
        
        dataSourceFactory.setDefaultRequestProperties(headers)

        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(dataSourceFactory)

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .build().apply {
            val mediaItem = MediaItem.Builder()
                .setUri(url)
                // Intentar detectar el tipo de contenido por la URL
                .setMimeType(when {
                    url.contains(".m3u8") -> androidx.media3.common.MimeTypes.APPLICATION_M3U8
                    url.contains(".mpd") -> androidx.media3.common.MimeTypes.APPLICATION_MPD
                    else -> null
                })
                .build()
            setMediaItem(mediaItem)
            prepare()
            playWhenReady = true
        }
        
        binding.playerView.player = player
        
        player?.addListener(object : androidx.media3.common.Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                binding.loadingIndicator.visibility = if (playbackState == androidx.media3.common.Player.STATE_BUFFERING) {
                    android.view.View.VISIBLE
                } else {
                    android.view.View.GONE
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                binding.loadingIndicator.visibility = android.view.View.GONE
                android.util.Log.e("NativeVideo", "ExoPlayer Error: ${error.message}", error)
                android.widget.Toast.makeText(this@NativeVideoActivity, "Error de reproducción: El servidor denegó el acceso o el formato no es compatible", android.widget.Toast.LENGTH_LONG).show()
                finish()
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }
}
