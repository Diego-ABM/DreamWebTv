package com.example.dreamweb.adblock

/**
 * Motor de matching por dominio: dos HashSet<String> en lowercase
 * (`blocked` para reglas `||d^`, `exceptions` para `@@||d^`).
 * Kotlin puro, inmutable tras construcción — se publica vía @Volatile
 * y se consulta sin locks desde el hilo de red del WebView (research R1).
 *
 * Matching: suffix walk por etiquetas del host. Las excepciones se
 * consultan antes que los bloqueos en cada sufijo y una excepción en
 * cualquier nivel gana (semántica `@@` — contrato §3).
 */
class DomainMatcher(
    private val blocked: Set<String>,
    private val exceptions: Set<String>
) {
    val ruleCount: Int = blocked.size + exceptions.size

    /**
     * true si `host` (ya normalizado: lowercase, sin esquema/puerto/path)
     * debe bloquearse. Pasada única por sufijos: excepción en cualquier
     * nivel => false; bloqueo si algún sufijo está en `blocked`.
     */
    fun isBlocked(host: String): Boolean {
        var candidate = host
        var hit = false
        while (true) {
            if (candidate in exceptions) return false
            if (!hit && candidate in blocked) hit = true
            val dot = candidate.indexOf('.')
            if (dot < 0) return hit
            candidate = candidate.substring(dot + 1)
        }
    }

    companion object {
        /** Matcher vacío publicado mientras el motor carga (modo degradado). */
        val EMPTY = DomainMatcher(emptySet(), emptySet())
    }
}
