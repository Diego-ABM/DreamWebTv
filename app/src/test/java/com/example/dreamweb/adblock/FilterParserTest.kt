package com.example.dreamweb.adblock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cobertura de la gramática `contracts/easylist-subset.md` §1-§2
 * contra cada fila de su tabla de aceptación §5.
 */
class FilterParserTest {

    // ---------- Reglas aceptadas ----------

    @Test
    fun `domain rule con terminador produce DomainBlock`() {
        assertEquals(
            FilterRule.DomainBlock("doubleclick.net"),
            FilterParser.parse("||doubleclick.net^")
        )
    }

    @Test
    fun `domain rule sin terminador EOL produce DomainBlock con misma semantica`() {
        assertEquals(
            FilterRule.DomainBlock("doubleclick.net"),
            FilterParser.parse("||doubleclick.net")
        )
    }

    @Test
    fun `exception rule produce Exception`() {
        assertEquals(
            FilterRule.Exception("ads.ejemplo.com"),
            FilterParser.parse("@@||ads.ejemplo.com^")
        )
    }

    @Test
    fun `exception rule sin terminador produce Exception`() {
        assertEquals(
            FilterRule.Exception("ads.ejemplo.com"),
            FilterParser.parse("@@||ads.ejemplo.com")
        )
    }

    @Test
    fun `dominio se normaliza a lowercase`() {
        assertEquals(
            FilterRule.DomainBlock("ads.ejemplo.com"),
            FilterParser.parse("||ADS.Ejemplo.COM^")
        )
    }

    // ---------- Ignoradas: comentarios, cabeceras, cosméticas ----------

    @Test
    fun `comentarios ignorados`() {
        assertNull(FilterParser.parse("! comentario"))
        assertNull(FilterParser.parse("!"))
        assertNull(FilterParser.parse("! Title: EasyList"))
    }

    @Test
    fun `cabeceras ignoradas`() {
        assertNull(FilterParser.parse("[Adblock Plus 3.0]"))
        assertNull(FilterParser.parse("[Adblock Plus 1.1]"))
    }

    @Test
    fun `reglas cosmeticas ignoradas`() {
        assertNull(FilterParser.parse("example.com##.ad-banner"))
        assertNull(FilterParser.parse("example.com#@#.ad"))
        assertNull(FilterParser.parse("example.com#?#div:-abp-has(> .ad)"))
        assertNull(FilterParser.parse("example.com#\$#hide-if-contains 'x'"))
    }

    // ---------- Ignoradas: opciones, wildcards, URL patterns ----------

    @Test
    fun `reglas con opciones dollar ignoradas enteras`() {
        assertNull(FilterParser.parse("||d.com^\$third-party"))
        assertNull(FilterParser.parse("||d.com^\$domain=example.com"))
        assertNull(FilterParser.parse("@@||d.com^\$document"))
    }

    @Test
    fun `wildcards ignorados`() {
        assertNull(FilterParser.parse("||ads.*^"))
        assertNull(FilterParser.parse("*/banner/*"))
    }

    @Test
    fun `anclas de URL y regex ignoradas`() {
        assertNull(FilterParser.parse("|http://x/"))
        assertNull(FilterParser.parse("|https://ads.example.com/banner"))
        assertNull(FilterParser.parse("/ads\\d+/"))
    }

    @Test
    fun `regla con path tras terminador ignorada`() {
        assertNull(FilterParser.parse("||d.com^/path"))
        assertNull(FilterParser.parse("||ads.com/banner.js"))
    }

    @Test
    fun `substring y hosts-style ignoradas`() {
        assertNull(FilterParser.parse("ads.example"))
        assertNull(FilterParser.parse("0.0.0.0 d.com"))
        assertNull(FilterParser.parse("example.com"))
    }

    // ---------- Ignoradas: dominios inválidos ----------

    @Test
    fun `dominios invalidos ignorados`() {
        assertNull(FilterParser.parse("||sub"))       // sin punto
        assertNull(FilterParser.parse("||"))           // vacío
        assertNull(FilterParser.parse("||-bad-.com^")) // guiones en los bordes
        assertNull(FilterParser.parse("||a.b^"))       // longitud < 4
        assertNull(FilterParser.parse("||do_min.com^"))// caracter no valido
        assertNull(FilterParser.parse(""))
        assertNull(FilterParser.parse("   "))
    }

    // ---------- FilterStats ----------

    @Test
    fun `FilterStats coherente aceptadas mas ignoradas igual a leidas`() {
        val blocked = mutableSetOf<String>()
        val exceptions = mutableSetOf<String>()
        val lines = sequenceOf(
            "[Adblock Plus 3.0]",          // ignored
            "! comment",                    // ignored
            "||doubleclick.net^",           // blocked
            "||doubleclick.net",            // blocked (EOL)
            "@@||ads.ejemplo.com^",         // exception
            "example.com##.ad",             // ignored
            "||d.com^\$third-party",        // ignored
            "||bad_domain",                 // ignored
            ""                              // ignored
        )
        val stats = FilterParser.parseLines("easylist.txt", lines, blocked, exceptions)

        assertEquals(9, stats.linesRead)
        assertEquals(2, stats.acceptedBlocked)
        assertEquals(1, stats.acceptedExceptions)
        assertEquals(6, stats.ignored)
        assertEquals(
            stats.linesRead,
            stats.acceptedBlocked + stats.acceptedExceptions + stats.ignored
        )
        assertTrue("doubleclick.net" in blocked)
        assertTrue("ads.ejemplo.com" in exceptions)
    }
}
