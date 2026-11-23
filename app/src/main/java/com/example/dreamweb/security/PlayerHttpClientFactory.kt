package com.example.dreamweb.security

import android.net.Uri
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.example.dreamweb.managers.VideoLaunchPayload
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.security.SecureRandom
import javax.net.ssl.SSLContext

/**
 * Construye el OkHttpClient del canal del reproductor nativo con validación
 * TLS real por defecto: [DomainTrustManager] + [DomainHostnameVerifier] sobre
 * la plataforma, sin trust-all (FR-001/FR-002). Conserva el comportamiento
 * funcional previo: redirects, CookieJar con las cookies del sitio y headers
 * (Referer, Origin derivado, Sec-Fetch-*, Accept, Cookie).
 *
 * La instancia [trustManager] queda expuesta para que la Activity pueda leer
 * `lastRejectedHost` y mostrar en el diálogo el host donde falló el handshake.
 */
class PlayerHttpClientFactory(store: TlsExceptionStore) {

    val trustManager = DomainTrustManager(store)

    private val hostnameVerifier = DomainHostnameVerifier(store)

    fun createDataSourceFactory(payload: VideoLaunchPayload): OkHttpDataSource.Factory {
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(trustManager), SecureRandom())

        val okHttpClient = OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier(hostnameVerifier)
            .followRedirects(true)
            .followSslRedirects(true)
            .cookieJar(object : CookieJar {
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {}
                override fun loadForRequest(url: HttpUrl): List<Cookie> {
                    val cookieList = mutableListOf<Cookie>()
                    if (payload.cookies.isNotEmpty()) {
                        payload.cookies.split(";").forEach {
                            Cookie.parse(url, it.trim())?.let { c -> cookieList.add(c) }
                        }
                    }
                    return cookieList
                }
            })
            .build()

        val dataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
            .setUserAgent(payload.userAgent)

        // Cabeceras críticas (Referer, Origin, Sec-Fetch-*)
        val headers = mutableMapOf<String, String>()
        if (payload.referer.isNotEmpty()) {
            headers["Referer"] = payload.referer
            val origin = try {
                val uri = Uri.parse(payload.referer)
                "${uri.scheme}://${uri.host}"
            } catch (ignored: Exception) {
                payload.referer
            }
            headers["Origin"] = origin
        }

        headers["Sec-Fetch-Mode"] = "cors"
        headers["Sec-Fetch-Site"] = "cross-site"
        headers["Accept"] = "*/*"

        if (payload.cookies.isNotEmpty()) {
            headers["Cookie"] = payload.cookies
        }

        dataSourceFactory.setDefaultRequestProperties(headers)
        return dataSourceFactory
    }
}
