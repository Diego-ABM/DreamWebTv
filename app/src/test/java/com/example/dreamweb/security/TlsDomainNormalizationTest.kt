package com.example.dreamweb.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TlsDomainNormalizationTest {

    @Test
    fun `lowercases host`() {
        assertEquals("example.com", TlsExceptionStore.normalize("EXAMPLE.COM"))
        assertEquals("example.com", TlsExceptionStore.normalize("Example.Com"))
    }

    @Test
    fun `strips scheme port and path`() {
        assertEquals("example.com", TlsExceptionStore.normalize("https://example.com"))
        assertEquals("example.com", TlsExceptionStore.normalize("https://example.com:8443"))
        assertEquals("example.com", TlsExceptionStore.normalize("http://example.com/live/stream.m3u8"))
        assertEquals("example.com", TlsExceptionStore.normalize("https://example.com:443/path?q=1#frag"))
    }

    @Test
    fun `bare host with port normalizes to host`() {
        assertEquals("example.com", TlsExceptionStore.normalize("example.com:443"))
        assertEquals("192.168.1.10", TlsExceptionStore.normalize("192.168.1.10:8080"))
    }

    @Test
    fun `exact match only - parent domain does not cover subdomain`() {
        assertEquals("sub.example.com", TlsExceptionStore.normalize("sub.example.com"))
        assertNotEquals(
            TlsExceptionStore.normalize("example.com"),
            TlsExceptionStore.normalize("sub.example.com")
        )
    }

    @Test
    fun `rejects empty and blank input`() {
        assertEquals("", TlsExceptionStore.normalize(""))
        assertEquals("", TlsExceptionStore.normalize("   "))
    }
}
