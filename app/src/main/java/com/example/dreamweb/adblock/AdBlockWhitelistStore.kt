package com.example.dreamweb.adblock

import android.content.Context
import android.content.SharedPreferences
import com.example.dreamweb.security.TlsExceptionStore

/**
 * Whitelist de dominios para AdBlock (FR-005/FR-008): sitios donde el
 * usuario desactiva el filtrado porque la página se rompe.
 *
 * Persiste en un SharedPreferences propio ("adblock_whitelist"):
 * clave = dominio normalizado (mismo `TlsExceptionStore.normalize()`),
 * valor = epochMillis de cuándo se exceptuó. Patrón idéntico a
 * `TlsExceptionStore` (research R4).
 *
 * Sobre el store hay un `@Volatile Set<String>` en memoria rehidratado en
 * construcción y actualizado en `add`/`remove`/`clear`: `isExcepted` hace
 * el suffix walk en RAM sin I/O en el hilo de red del WebView.
 *
 * Semántica (Clarifications): se compara contra el **host de la página**
 * con coincidencia por subdominio — `ejemplo.com` exceptuado cubre
 * `www.ejemplo.com` y `a.b.ejemplo.com` (mismo helper `suffixMatches`
 * del núcleo que usa `DomainMatcher`).
 */
class AdBlockWhitelistStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    @Volatile
    private var domains: Set<String> = prefs.all.keys.toSet()

    /**
     * true si el host de página (normalizado o no) está exceptuado por
     * sufijo de dominio. Sin I/O — lee el set volátil.
     */
    fun isExcepted(pageHost: String): Boolean {
        val normalized = TlsExceptionStore.normalize(pageHost)
        if (normalized.isEmpty()) return false
        return suffixMatches(normalized, domains)
    }

    fun add(host: String) {
        val normalized = TlsExceptionStore.normalize(host)
        if (normalized.isEmpty()) return
        prefs.edit().putLong(normalized, System.currentTimeMillis()).apply()
        domains = prefs.all.keys.toSet()
    }

    fun remove(host: String) {
        val normalized = TlsExceptionStore.normalize(host)
        if (normalized.isEmpty()) return
        prefs.edit().remove(normalized).apply()
        domains = prefs.all.keys.toSet()
    }

    fun clear() {
        prefs.edit().clear().apply()
        domains = emptySet()
    }

    /** dominio normalizado → epochMillis de la excepción. */
    fun list(): Map<String, Long> {
        return prefs.all.mapNotNull { (k, v) ->
            (v as? Long)?.let { k to it }
        }.toMap()
    }

    private companion object {
        const val PREFS_FILE = "adblock_whitelist"
    }
}
