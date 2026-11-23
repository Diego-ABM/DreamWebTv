package com.example.dreamweb.adblock

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Semántica de matching del contrato §3: suffix walk por etiquetas,
 * precedencia de excepciones `@@`, modo degradado EMPTY.
 */
class DomainMatcherTest {

    private val matcher = DomainMatcher(
        blocked = setOf("ads.ejemplo.com", "tracker.net", "doubleclick.net"),
        exceptions = setOf("allowed.ads.ejemplo.com")
    )

    @Test
    fun `host exacto bloqueado`() {
        assertTrue(matcher.isBlocked("ads.ejemplo.com"))
        assertTrue(matcher.isBlocked("doubleclick.net"))
    }

    @Test
    fun `subdominio bloqueado por sufijo`() {
        assertTrue(matcher.isBlocked("x.ads.ejemplo.com"))
        assertTrue(matcher.isBlocked("a.b.c.tracker.net"))
    }

    @Test
    fun `dominio no listado permitido`() {
        assertFalse(matcher.isBlocked("www.ejemplo.com"))
        assertFalse(matcher.isBlocked("google.com"))
    }

    @Test
    fun `dominio parecido pero no subdominio no bloqueado`() {
        // "badtracker.net" no es subdominio de "tracker.net"
        assertFalse(matcher.isBlocked("badtracker.net"))
        assertFalse(matcher.isBlocked("tracker.net.evil.org"))
    }

    @Test
    fun `excepcion gana sobre bloqueo`() {
        // host cubierto por @@ (subdominio de regla bloqueada) -> ALLOW
        assertFalse(matcher.isBlocked("allowed.ads.ejemplo.com"))
        assertFalse(matcher.isBlocked("deep.allowed.ads.ejemplo.com"))
        // el padre bloqueado sigue bloqueando sus otros subdominios
        assertTrue(matcher.isBlocked("other.ads.ejemplo.com"))
    }

    @Test
    fun `EMPTY nunca bloquea - modo degradado`() {
        assertFalse(DomainMatcher.EMPTY.isBlocked("doubleclick.net"))
        assertFalse(DomainMatcher.EMPTY.isBlocked("anything.example.com"))
        assertFalse(DomainMatcher.EMPTY.isBlocked(""))
    }

    @Test
    fun `TLDs sueltos en las reglas son sondas normales`() {
        val withTld = DomainMatcher(setOf("com"), emptySet())
        assertTrue(withTld.isBlocked("anything.com"))
        // pero un TLD no listado no explota el matching
        assertFalse(matcher.isBlocked("co.uk"))
        assertFalse(matcher.isBlocked("net"))
    }

    @Test
    fun `ruleCount suma bloqueos y excepciones`() {
        assert(matcher.ruleCount == 4)
        assert(DomainMatcher.EMPTY.ruleCount == 0)
    }
}
