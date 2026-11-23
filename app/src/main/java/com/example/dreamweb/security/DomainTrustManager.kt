package com.example.dreamweb.security

import java.net.Socket
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

/**
 * TrustManager que envuelve el X509TrustManager de plataforma y delega la
 * validación real (FR-001). Solo cuando la delegación lanza
 * CertificateException, acepta si el host del peer está exceptuado en
 * [TlsExceptionStore] (por dominio exacto — FR-004). En cualquier otro caso
 * relanza la excepción. PROHIBIDA la aceptación incondicional (FR-002).
 *
 * Conscrypt invoca las firmas extendidas con SSLSocket/SSLEngine, de donde se
 * extrae el host del peer (peerHost). El último host rechazado queda en
 * [lastRejectedHost] para que el diálogo muestre el dominio donde falló el
 * handshake — que con redirects puede ser distinto al de la URL del stream.
 */
class DomainTrustManager(private val store: TlsExceptionStore) : X509ExtendedTrustManager() {

    private val delegate: X509TrustManager = run {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    /** Host del último handshake rechazado (null si desconocido). */
    @Volatile
    var lastRejectedHost: String? = null
        private set

    override fun checkServerTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
        socket: Socket?
    ) {
        try {
            delegate.checkServerTrusted(chain, authType)
        } catch (e: CertificateException) {
            // SSLSocket no expone peerHost: inetAddress.hostName devuelve el
            // nombre DNS con el que OkHttp creó el InetSocketAddress.
            val host = (socket as? SSLSocket)?.inetAddress?.hostName
            allowOnlyIfExcepted(host, e)
        }
    }

    override fun checkServerTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
        engine: SSLEngine?
    ) {
        try {
            delegate.checkServerTrusted(chain, authType)
        } catch (e: CertificateException) {
            allowOnlyIfExcepted(engine?.peerHost, e)
        }
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        try {
            delegate.checkServerTrusted(chain, authType)
        } catch (e: CertificateException) {
            lastRejectedHost = null
            throw e
        }
    }

    override fun checkClientTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
        socket: Socket?
    ) {
        delegate.checkClientTrusted(chain, authType)
    }

    override fun checkClientTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
        engine: SSLEngine?
    ) {
        delegate.checkClientTrusted(chain, authType)
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        delegate.checkClientTrusted(chain, authType)
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers

    private fun allowOnlyIfExcepted(peerHost: String?, cause: CertificateException) {
        val host = TlsExceptionStore.normalize(peerHost.orEmpty())
        if (host.isNotEmpty() && store.isExcepted(host)) {
            return // excepción por dominio aceptada previamente por el usuario
        }
        lastRejectedHost = host.ifEmpty { peerHost }
        throw cause
    }
}

/**
 * HostnameVerifier simétrico al [DomainTrustManager]: delega en el verificador
 * por defecto de la plataforma y solo retorna true extra para hosts
 * exceptuados (cubre hostname-mismatch en dominios aceptados por el usuario).
 */
class DomainHostnameVerifier(private val store: TlsExceptionStore) : javax.net.ssl.HostnameVerifier {

    private val delegate = HttpsURLConnection.getDefaultHostnameVerifier()

    override fun verify(hostname: String?, session: SSLSession?): Boolean {
        val host = hostname ?: return false
        if (session == null) return false
        if (delegate.verify(host, session)) return true
        return store.isExcepted(host)
    }
}
