package com.example.dreamweb.security

import com.example.dreamweb.managers.VideoLaunchPayload
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Merge de headers del canal del player por capas (R8, contrato §3).
 * Robolectric: la derivación de Origin usa android.net.Uri.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayerHttpClientFactoryTest {

    private fun factory(): PlayerHttpClientFactory {
        return PlayerHttpClientFactory(TlsExceptionStore(RuntimeEnvironment.getApplication()))
    }

    private fun payload(
        headers: Map<String, String> = emptyMap(),
        referer: String = "",
        cookies: String = "",
        userAgent: String = "Agent/1.0"
    ) = VideoLaunchPayload(
        url = "https://cdn.example.com/v/master.m3u8",
        referer = referer, userAgent = userAgent, cookies = cookies,
        headers = headers
    )

    @Test
    fun `captured headers are applied`() {
        val merged = factory().mergedRequestHeaders(
            payload(headers = mapOf("X-Requested-With" to "XMLHttpRequest", "Authorization" to "Bearer t"))
        )
        assertEquals("XMLHttpRequest", merged["X-Requested-With"])
        assertEquals("Bearer t", merged["Authorization"])
    }

    @Test
    fun `captured headers can never override managed names`() {
        val merged = factory().mergedRequestHeaders(
            payload(
                referer = "https://site.example.com/watch",
                cookies = "session=real",
                headers = mapOf(
                    "User-Agent" to "evil",
                    "Referer" to "https://evil.example",
                    "Origin" to "https://evil.example",
                    "Cookie" to "session=evil",
                    "Sec-Fetch-Mode" to "navigate",
                    "Sec-Fetch-Site" to "none",
                    "Host" to "evil.example",
                    "Content-Length" to "5",
                    "Range" to "bytes=0-1",
                    "X-Good" to "ok"
                )
            )
        )
        assertEquals("https://site.example.com/watch", merged["Referer"])
        assertEquals("https://site.example.com", merged["Origin"])
        assertEquals("session=real", merged["Cookie"])
        assertEquals("cors", merged["Sec-Fetch-Mode"])
        assertEquals("cross-site", merged["Sec-Fetch-Site"])
        assertFalse(merged.containsKey("Host"))
        assertFalse(merged.containsKey("Content-Length"))
        assertFalse(merged.containsKey("Range"))
        assertFalse(merged.containsKey("User-Agent")) // el UA viaja vía setUserAgent
        assertEquals("ok", merged["X-Good"])
    }

    @Test
    fun `referer produces Referer header plus derived Origin`() {
        val merged = factory().mergedRequestHeaders(
            payload(referer = "https://site.example.com:8443/path/watch")
        )
        assertEquals("https://site.example.com:8443/path/watch", merged["Referer"])
        assertTrue(merged["Origin"]!!.startsWith("https://site.example.com"))
    }

    @Test
    fun `fixed fetch headers are always present`() {
        val merged = factory().mergedRequestHeaders(payload())
        assertEquals("cors", merged["Sec-Fetch-Mode"])
        assertEquals("cross-site", merged["Sec-Fetch-Site"])
        assertEquals("*/*", merged["Accept"])
    }

    @Test
    fun `cookies produce the Cookie header and feed the CookieJar`() {
        val f = factory()
        val p = payload(cookies = "session=abc; pref=tv")
        val merged = f.mergedRequestHeaders(p)
        assertEquals("session=abc; pref=tv", merged["Cookie"])

        val client = f.createClient(p)
        val jar = client.cookieJar
        val sent = jar.loadForRequest("https://cdn.example.com/v/master.m3u8".toHttpUrl())
        assertTrue(sent.any { it.name == "session" && it.value == "abc" })
        assertTrue(sent.any { it.name == "pref" && it.value == "tv" })
    }

    @Test
    fun `createClient is reusable for the prober pipeline`() {
        val f = factory()
        val client = f.createClient(payload())
        // pipeline TLS por dominio intacto (nunca trust-all)
        assertTrue(client.sslSocketFactory != null)
        assertTrue(client.hostnameVerifier != null)
    }
}
