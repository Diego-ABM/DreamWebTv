package com.example.dreamweb.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tabla completa de contracts/external-url-schemes.md (FR-004, FR-017). */
class ExternalUrlClassifierTest {

    private fun assertWebLoad(url: String) {
        assertEquals("esperaba WebLoad para '$url'", ExternalAction.WebLoad, ExternalUrlClassifier.classify(url))
    }

    private fun assertExternal(url: String, scheme: String) {
        val action = ExternalUrlClassifier.classify(url)
        assertTrue("esperaba External para '$url'", action is ExternalAction.External)
        assertEquals(scheme, (action as ExternalAction.External).scheme)
    }

    @Test
    fun `esquemas web clasifican como WebLoad`() {
        assertWebLoad("http://example.com")
        assertWebLoad("https://example.com/path?q=1")
        assertWebLoad("about:blank")
        assertWebLoad("about:config")
        assertWebLoad("data:text/html,<h1>hi</h1>")
        assertWebLoad("blob:https://example.com/uuid")
        assertWebLoad("javascript:void(0)")
    }

    @Test
    fun `urls sin esquema clasifican como WebLoad`() {
        assertWebLoad("www.foo.com")
        assertWebLoad("texto sin esquema")
        assertWebLoad("")
        assertWebLoad("   ")
    }

    @Test
    fun `intent clasifica como External`() {
        assertExternal("intent://scan/#Intent;scheme=zxing;end", "intent")
        assertExternal("intent:foo#Intent;end", "intent")
    }

    @Test
    fun `esquemas externos conocidos clasifican como External`() {
        assertExternal("mailto:a@b.com", "mailto")
        assertExternal("tel:+34123456", "tel")
        assertExternal("sms:+34123456", "sms")
        assertExternal("smsto:+34123456", "smsto")
        assertExternal("geo:40.4,-3.7", "geo")
        assertExternal("market://details?id=com.x", "market")
    }

    @Test
    fun `esquemas genericos validos clasifican como External`() {
        assertExternal("whatsapp://send?text=hi", "whatsapp")
        assertExternal("fb://page/1", "fb")
        assertExternal("vnd.youtube://abc", "vnd.youtube")
    }

    @Test
    fun `esquema en mayusculas se normaliza`() {
        assertWebLoad("HTTPS://example.com")
        assertExternal("MAILTO:a@b.com", "mailto")
    }
}
