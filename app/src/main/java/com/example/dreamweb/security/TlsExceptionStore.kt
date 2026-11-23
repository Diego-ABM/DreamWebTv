package com.example.dreamweb.security

import android.content.Context
import android.content.SharedPreferences

/**
 * Store de excepciones TLS por dominio (FR-004). Persiste en un archivo
 * SharedPreferences propio ("tls_exceptions"): clave = host normalizado,
 * valor = epoch millis de la aceptación.
 *
 * La escritura ocurre únicamente desde la acción "Aceptar" del diálogo TLS;
 * el rechazo nunca escribe (FR-005). Las consultas las hacen
 * DomainTrustManager y DomainHostnameVerifier en cada handshake.
 */
class TlsExceptionStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    /** true si el host (ya normalizado o no) tiene excepción registrada. */
    fun isExcepted(host: String): Boolean {
        val normalized = normalize(host)
        return normalized.isNotEmpty() && prefs.contains(normalized)
    }

    fun add(host: String) {
        val normalized = normalize(host)
        if (normalized.isEmpty()) return
        prefs.edit().putLong(normalized, System.currentTimeMillis()).apply()
    }

    fun remove(host: String) {
        val normalized = normalize(host)
        if (normalized.isEmpty()) return
        prefs.edit().remove(normalized).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    /** host normalizado → epochMillis de aceptación. */
    fun list(): Map<String, Long> {
        return prefs.all.mapNotNull { (k, v) ->
            (v as? Long)?.let { k to it }
        }.toMap()
    }

    companion object {
        private const val PREFS_FILE = "tls_exceptions"

        /**
         * Normaliza un host o URL a su forma canónica: lowercase, sin esquema,
         * sin userinfo, sin puerto, sin path/query/fragment y sin punto final.
         * Match exacto — un dominio exceptuado NO cubre subdominios.
         * Devuelve "" para entradas vacías/inválidas.
         */
        fun normalize(host: String): String {
            var h = host.trim().lowercase()
            if (h.isEmpty()) return ""

            // Esquema (si viene como URL completa)
            val schemeIdx = h.indexOf("://")
            if (schemeIdx >= 0) h = h.substring(schemeIdx + 3)

            // Path / query / fragment
            h = h.substringBefore('/').substringBefore('?').substringBefore('#')

            // Userinfo
            h = h.substringAfterLast('@')

            // Puerto (nota: no soportamos literales IPv6 con corchetes aquí;
            // los hosts del handshake vienen como nombre DNS o IPv4)
            if (h.startsWith("[")) {
                h = h.substringBefore(']').trim('[', ']')
            } else {
                h = h.substringBefore(':')
            }

            // Punto final DNS
            h = h.trimEnd('.')

            return h
        }
    }
}
