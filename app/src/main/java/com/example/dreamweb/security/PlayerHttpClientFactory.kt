package com.example.dreamweb.security

import android.net.Uri
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.example.dreamweb.engine.VideoReportParser
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
 * la plataforma, sin trust-all (FR-001/FR-002/FR-010).
 *
 * Headers por capas (contracts/playback-context-payload.md §3, R8 — la
 * última capa gana):
 * 1. `payload.headers` capturados, sanitizados (sin nombres gestionados).
 * 2. Gestionados autoritativos: `User-Agent` (vía `setUserAgent`),
 *    `Referer` + `Origin` derivado, `Cookie` de `payload.cookies`,
 *    `Sec-Fetch-Mode/Site` y `Accept` fijos.
 * 3. `CookieJar` vigente por request (cubre redirects a otros hosts).
 *
 * La instancia [trustManager] queda expuesta para que la Activity lea
 * `lastRejectedHost` en el diálogo TLS.
 */
class PlayerHttpClientFactory(store: TlsExceptionStore) {

    val trustManager = DomainTrustManager(store)

    private val hostnameVerifier = DomainHostnameVerifier(store)

    /**
     * Merge de headers testeable (capa 1 + capa 2). Los nombres gestionados
     * (`User-Agent`, `Referer`, `Origin`, `Cookie`, `Sec-Fetch-*`, `Host`,
     * `Content-Length`, `Range`) NUNCA pueden ser pisados por los capturados.
     */
    fun mergedRequestHeaders(payload: VideoLaunchPayload): Map<String, String> {
        // Capa 1: capturados — defensa en profundidad, misma sanitización
        // de la frontera aunque el productor ya la aplicó.
        val headers = LinkedHashMap(VideoReportParser.sanitizeHeaders(payload.headers))

        // Capa 2: gestionados autoritativos (provienen del navegador, no del JS)
        if (payload.referer.isNotEmpty()) {
            headers["Referer"] = payload.referer
            headers["Origin"] = deriveOrigin(payload.referer)
        }
        headers["Sec-Fetch-Mode"] = "cors"
        // Sec-Fetch-Site real: servidores/CDNs (p. ej. Cloudflare en
        // player.zilla-networks.com) rechazan cross-site cuando la petición
        // original sale del propio iframe del player — derivar del par
        // url del stream vs referer.
        headers["Sec-Fetch-Site"] = secFetchSite(payload.url, payload.referer)
        headers["Sec-Fetch-Dest"] = "empty"
        headers["Accept"] = "*/*"
        if (payload.cookies.isNotEmpty()) {
            headers["Cookie"] = payload.cookies
        }
        return headers
    }

    /**
     * OkHttpClient del canal del player — pipeline TLS por dominio intacto +
     * CookieJar con las cookies del payload. Reutilizable por
     * `VideoInfoProber` (R5: el sondeo viaja con la misma fidelidad).
     */
    fun createClient(payload: VideoLaunchPayload): OkHttpClient {
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(trustManager), SecureRandom())

        return OkHttpClient.Builder()
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
    }

    fun createDataSourceFactory(payload: VideoLaunchPayload): OkHttpDataSource.Factory {
        val dataSourceFactory = OkHttpDataSource.Factory(createClient(payload))
            .setUserAgent(payload.userAgent)

        dataSourceFactory.setDefaultRequestProperties(mergedRequestHeaders(payload))
        return dataSourceFactory
    }

    private fun deriveOrigin(referer: String): String = try {
        val uri = Uri.parse(referer)
        "${uri.scheme}://${uri.host}"
    } catch (ignored: Exception) {
        referer
    }

    /**
     * Sec-Fetch-Site del fetch de medios, igual que lo emitiría la página:
     * `same-origin` si host+esquema coinciden (stream servido por el propio
     * host del player iframe), `same-site` para el mismo dominio
     * registrable (aprox. últimas 2 etiquetas), `cross-site` en otro caso.
     */
    internal fun secFetchSite(streamUrl: String, referer: String): String {
        if (referer.isEmpty()) return "cross-site"
        return try {
            val s = Uri.parse(streamUrl)
            val r = Uri.parse(referer)
            val sh = s.host?.lowercase() ?: return "cross-site"
            val rh = r.host?.lowercase() ?: return "cross-site"
            when {
                sh == rh && s.scheme.equals(r.scheme, ignoreCase = true) -> "same-origin"
                registrableDomain(sh) == registrableDomain(rh) -> "same-site"
                else -> "cross-site"
            }
        } catch (e: Exception) {
            "cross-site"
        }
    }

    /** Aproximación eTLD+1: últimas dos etiquetas (suficiente para el hint). */
    private fun registrableDomain(host: String): String {
        val labels = host.split('.')
        return if (labels.size <= 2) host else labels.takeLast(2).joinToString(".")
    }
}
