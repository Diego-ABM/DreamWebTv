package com.example.dreamweb.adblock

/**
 * Helper puro de suffix walk por etiquetas de dominio, compartido por
 * `DomainMatcher.isBlocked` y `AdBlockWhitelistStore.isExcepted`
 * (contracts/easylist-subset.md §3-§4). Sin dependencias android.*
 *
 * `suffixMatches("a.b.ads.com", set)` sondea: `a.b.ads.com`, `b.ads.com`,
 * `ads.com`, `com` — true al primer sufijo presente en el set.
 */
internal fun suffixMatches(host: String, domains: Set<String>): Boolean {
    var candidate = host
    while (true) {
        if (candidate in domains) return true
        val dot = candidate.indexOf('.')
        if (dot < 0) return false
        candidate = candidate.substring(dot + 1)
    }
}
