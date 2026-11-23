package com.example.dreamweb.adblock

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Helper puro de suffix walk compartido (`DomainSuffixes.kt`) aplicado al
 * set de dominios exceptuados — sin SharedPreferences ni
 * AdBlockWhitelistStore (contrato §4, US3).
 */
class AdBlockWhitelistMatcherTest {

    private val excepted: Set<String> = setOf("ejemplo.com", "otro-sitio.org")

    private fun isExcepted(pageHost: String) = suffixMatches(pageHost, excepted)

    @Test
    fun `dominio exceptuado cubre el propio host`() {
        assertTrue(isExcepted("ejemplo.com"))
    }

    @Test
    fun `dominio exceptuado cubre subdominios`() {
        assertTrue(isExcepted("www.ejemplo.com"))
        assertTrue(isExcepted("a.b.ejemplo.com"))
    }

    @Test
    fun `dominio parecido no queda cubierto`() {
        assertFalse(isExcepted("otroejemplo.com"))
        assertFalse(isExcepted("ejemplo.com.evil.net"))
    }

    @Test
    fun `dominio no exceptuado sigue filtrando`() {
        assertFalse(isExcepted("publicidad.com"))
    }

    @Test
    fun `set vacio nunca exceptua`() {
        assertFalse(suffixMatches("ejemplo.com", emptySet()))
        assertFalse(suffixMatches("", setOf("ejemplo.com")))
    }
}
