package com.example.dreamweb.adblock

/**
 * Parser del subconjunto EasyList v1 (contracts/easylist-subset.md).
 * Kotlin puro, sin dependencias android.* — testeable en JVM (Principio IV).
 *
 * Acepta:
 *  - `||dominio^` y `||dominio` (fin de línea) -> FilterRule.DomainBlock
 *    (misma semántica de domain-suffix match en ambos casos — divergencia
 *    documentada respecto a EasyList estricto, contrato §1)
 *  - `@@||dominio^` / `@@||dominio`      -> FilterRule.Exception
 *
 * Ignora (devuelve null): comentarios `!`, cabeceras `[...]`, cosméticas
 * `##`/`#@#`/`#?#`/`#$#`, cualquier regla con `$`, `*`, paths, regex, anclas
 * `|http`, hosts-style y dominios que no validen.
 */
sealed interface FilterRule {
    val domain: String

    data class DomainBlock(override val domain: String) : FilterRule
    data class Exception(override val domain: String) : FilterRule
}

/** Metadatos de una lista parseada. aceptadas + ignoradas = líneas leídas. */
data class FilterStats(
    val source: String,
    val linesRead: Int,
    val acceptedBlocked: Int,
    val acceptedExceptions: Int,
    val ignored: Int
)

object FilterParser {

    private val DOMAIN_REGEX = Regex("^[a-z0-9]([a-z0-9.-]*[a-z0-9])?$")

    private val COSMETIC_MARKERS = arrayOf("##", "#@#", "#?#", "#$#")

    /**
     * Parsea una línea. Devuelve la regla o null si debe ignorarse.
     * Nunca lanza excepciones (parseo tolerante).
     */
    fun parse(line: String): FilterRule? {
        var s = line.trim()
        if (s.isEmpty()) return null
        if (s[0] == '!' || s[0] == '[') return null
        for (marker in COSMETIC_MARKERS) {
            if (s.contains(marker)) return null
        }

        val isException = s.startsWith("@@")
        if (isException) s = s.substring(2)

        if (!s.startsWith("||")) return null

        var rest = s.substring(2)
        if (rest.endsWith("^")) {
            rest = rest.substring(0, rest.length - 1)
        }

        val domain = rest.lowercase()
        if (!isValidDomain(domain)) return null

        return if (isException) {
            FilterRule.Exception(domain)
        } else {
            FilterRule.DomainBlock(domain)
        }
    }

    /**
     * Parsea una secuencia de líneas volcando los dominios aceptados en
     * [blocked] y [exceptions]. Devuelve las estadísticas de la fuente.
     * Líneas corruptas cuentan como ignoradas, nunca abortan el parseo.
     */
    fun parseLines(
        source: String,
        lines: Sequence<String>,
        blocked: MutableCollection<String>,
        exceptions: MutableCollection<String>
    ): FilterStats {
        var read = 0
        var acceptedBlocked = 0
        var acceptedExceptions = 0
        var ignored = 0
        for (line in lines) {
            read++
            when (val rule = parse(line)) {
                is FilterRule.DomainBlock -> {
                    blocked.add(rule.domain)
                    acceptedBlocked++
                }
                is FilterRule.Exception -> {
                    exceptions.add(rule.domain)
                    acceptedExceptions++
                }
                null -> ignored++
            }
        }
        return FilterStats(
            source = source,
            linesRead = read,
            acceptedBlocked = acceptedBlocked,
            acceptedExceptions = acceptedExceptions,
            ignored = ignored
        )
    }

    /**
     * `^[a-z0-9]([a-z0-9.-]*[a-z0-9])?$` con al menos un `.` y longitud ≥ 4.
     * Punycode (`xn--`) entra por el charset.
     */
    fun isValidDomain(domain: String): Boolean {
        return domain.length >= 4 &&
            domain.indexOf('.') > 0 &&
            DOMAIN_REGEX.matches(domain)
    }
}
