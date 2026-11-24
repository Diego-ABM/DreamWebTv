package com.example.dreamweb.ui

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.example.dreamweb.BrowserApp
import com.example.dreamweb.BuildConfig
import com.example.dreamweb.databinding.ActivityNativeVideoBinding
import com.example.dreamweb.managers.VideoLaunchPayload
import com.example.dreamweb.security.PlayerHttpClientFactory
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import java.security.cert.CertPathValidatorException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

@OptIn(UnstableApi::class)
class NativeVideoActivity : AppCompatActivity() {
    private lateinit var binding: ActivityNativeVideoBinding
    private var player: ExoPlayer? = null
    private var videoData: String? = null
    private var httpClientFactory: PlayerHttpClientFactory? = null
    private var tlsDialogShowing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNativeVideoBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val data = intent.getStringExtra("EXTRA_VIDEO_URL") ?: run {
            finish()
            return
        }
        videoData = data
        setupPlayer(data)
    }

    private fun setupPlayer(videoData: String) {
        val app = application as BrowserApp
        val factory = PlayerHttpClientFactory(app.container.tlsExceptionStore)
        httpClientFactory = factory

        val payload = VideoLaunchPayload.parse(videoData)
        if (payload == null) {
            // Payload no parseable: nunca se congeló el WebView (T026 lo
            // valida antes), vuelta intacta con aviso claro (FR-012).
            Toast.makeText(this, "No se pudo reproducir el video", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "URL: ${payload.url}")
            Log.d(TAG, "Referer: ${payload.referer}")
            Log.d(TAG, "UA: ${payload.userAgent}")
        }

        val dataSourceFactory = factory.createDataSourceFactory(payload)

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(dataSourceFactory))
            .build().apply {
            val mediaItem = buildMediaItem(payload)
            // Posición de origen solo si >0 — sin seekTo(0) redundante.
            if (payload.startPositionMs > 0) {
                setMediaItem(mediaItem, payload.startPositionMs)
            } else {
                setMediaItem(mediaItem)
            }
            prepare()
            playWhenReady = true
        }

        binding.playerView.player = player

        player?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                binding.loadingIndicator.visibility = if (playbackState == Player.STATE_BUFFERING) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                binding.loadingIndicator.visibility = View.GONE
                Log.e(TAG, "ExoPlayer Error: ${error.errorCodeName} (${error.errorCode})", error)
                Log.e(TAG, "Cause: ${error.cause?.message}")

                val tlsCategory = findTlsCause(error)
                if (tlsCategory != null) {
                    showTlsDialog(tlsCategory, payload)
                } else if (isDrmError(error)) {
                    Toast.makeText(
                        this@NativeVideoActivity,
                        "contenido protegido no soportado",
                        Toast.LENGTH_LONG
                    ).show()
                    finish()
                } else {
                    // Stream caducado/denegado/red: mensaje claro + vuelta
                    // intacta a la navegación para reintentar (FR-012).
                    Toast.makeText(
                        this@NativeVideoActivity,
                        "no se pudo reproducir (caducado o sin acceso); recarga la página",
                        Toast.LENGTH_LONG
                    ).show()
                    finish()
                }
            }
        })
    }

    /** MediaItem con hint de mimeType y pistas de subtítulos del payload. */
    private fun buildMediaItem(payload: VideoLaunchPayload): MediaItem {
        val builder = MediaItem.Builder()
            .setUri(payload.url)
            .setMimeType(
                payload.mimeType ?: when {
                    payload.url.contains(".m3u8") -> MimeTypes.APPLICATION_M3U8
                    payload.url.contains(".mpd") -> MimeTypes.APPLICATION_MPD
                    else -> null
                }
            )

        if (payload.subtitles.isNotEmpty()) {
            builder.setSubtitleConfigurations(
                payload.subtitles.map { track ->
                    MediaItem.SubtitleConfiguration.Builder(Uri.parse(track.url))
                        .setMimeType(track.mimeType)
                        .apply { track.language?.let { setLanguage(it) } }
                        .apply { track.label?.let { setLabel(it) } }
                        .build()
                }
            )
        }
        return builder.build()
    }

    /** Errores de DRM comercial (60xx) → degradación documentada del spec. */
    private fun isDrmError(error: PlaybackException): Boolean =
        error.errorCode in 6000..6099

    /**
     * Recorre la cadena de causas buscando fallos TLS y los mapea a una
     * categoría legible (contracts/tls-exceptions-ui.md §2). Devuelve null si
     * el error no es de certificado/handshake.
     */
    private fun findTlsCause(error: PlaybackException): String? {
        var cause: Throwable? = error
        var sawTlsError = false
        var sawPeerUnverified = false
        var sawValidator = false
        while (cause != null) {
            when (cause) {
                is CertificateExpiredException,
                is CertificateNotYetValidException ->
                    return "el certificado está expirado o aún no es válido"
                is SSLPeerUnverifiedException -> {
                    sawTlsError = true
                    sawPeerUnverified = true
                }
                is CertPathValidatorException -> {
                    sawTlsError = true
                    sawValidator = true
                }
                is SSLHandshakeException,
                is CertificateException -> sawTlsError = true
            }
            cause = cause.cause
        }
        return when {
            sawPeerUnverified -> "el certificado no corresponde al dominio"
            sawValidator -> "el certificado es autofirmado o la cadena no es confiable"
            sawTlsError -> "problema con el certificado del servidor"
            else -> null
        }
    }

    private fun showTlsDialog(problem: String, payload: VideoLaunchPayload) {
        if (isFinishing || isDestroyed || tlsDialogShowing) return

        // Host donde falló el handshake, propagado desde DomainTrustManager;
        // con redirects puede diferir del host de la URL del stream.
        val failedHost = httpClientFactory?.trustManager?.lastRejectedHost
            ?: runCatching { Uri.parse(payload.url).host }.getOrNull()
            ?: payload.url

        tlsDialogShowing = true
        var accepted = false

        AlertDialog.Builder(this)
            .setTitle("Certificado no confiable")
            .setMessage(
                "El dominio \"$failedHost\" presenta un problema de seguridad: $problem.\n\n" +
                    "¿Continuar solo para este dominio? La excepción quedará guardada y podrás revocarla desde Seguridad."
            )
            .setPositiveButton("Aceptar (solo este dominio)") { _, _ ->
                accepted = true
                (application as BrowserApp).container.tlsExceptionStore.add(failedHost)
                // Reintento automático: liberar el player y recrear
                player?.release()
                player = null
                videoData?.let { setupPlayer(it) }
            }
            .setNegativeButton("Rechazar") { dialog, _ ->
                dialog.dismiss()
            }
            .setOnDismissListener {
                tlsDialogShowing = false
                if (!accepted) {
                    // Rechazo (botón, Back/dismiss): no persiste nada (FR-005)
                    Toast.makeText(
                        this@NativeVideoActivity,
                        "Reproducción cancelada: certificado no confiable",
                        Toast.LENGTH_LONG
                    ).show()
                    finish()
                }
            }
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
        httpClientFactory = null
    }

    companion object {
        private const val TAG = "NativeVideo"
    }
}
