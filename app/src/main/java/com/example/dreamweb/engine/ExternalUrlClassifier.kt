package com.example.dreamweb.engine

/**
 * Resultado de clasificar una URL de navegación (Kotlin puro — FR-004, R2).
 * Ver contracts/external-url-schemes.md para la tabla completa.
 */
sealed interface ExternalAction {
    /** http(s), about, data, blob, javascript o sin esquema → el WebView decide. */
    data object WebLoad : ExternalAction

    /** Cualquier otro esquema válido → resolución con apps externas. */
    data class External(val scheme: String) : ExternalAction
}

object ExternalUrlClassifier {

    /** Esquema URI válido: ALPHA *( ALPHA / DIGIT / "+" / "-" / "." ) seguido de ':'. */
    private val SCHEME_REGEX = Regex("^[a-z][a-z0-9+.-]*:")

    private val WEB_SCHEMES = setOf("http", "https", "about", "data", "blob", "javascript")

    fun classify(url: String): ExternalAction {
        val match = SCHEME_REGEX.find(url.trim().lowercase())
            ?: return ExternalAction.WebLoad
        val scheme = match.value.dropLast(1)
        return if (scheme in WEB_SCHEMES) {
            ExternalAction.WebLoad
        } else {
            ExternalAction.External(scheme)
        }
    }
}
