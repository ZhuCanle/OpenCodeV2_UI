package com.zhucanle.opencodev2.ide.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2 web-mode URL tests for [WebModeSupport.buildUrl].
 *
 * The v2 web app is a single-page application served from the server root: it returns the same
 * shell for every path and routes on the client. Its router only knows `/`, `/settings`,
 * `/connect`, `/new-session` and `/server/<id>/session/<id>`; anything else throws
 * "Unrecognised route!" while booting, so the embedded browser must target the root rather than
 * the old `/doc` path. The bind-all host `0.0.0.0` is not navigable from a browser and is
 * normalized to `127.0.0.1`.
 */
class WebModeUrlTest {

    @Test
    fun targetsSpaRootForBindAllHost() {
        assertEquals("http://127.0.0.1:49374", WebModeSupport.buildUrl("0.0.0.0", 49374))
    }

    @Test
    fun targetsSpaRootForPlainHost() {
        assertEquals("http://192.168.1.10:4096", WebModeSupport.buildUrl("192.168.1.10", 4096))
    }

    @Test
    fun keepsLoopbackHostAsIs() {
        assertEquals("http://127.0.0.1:8080", WebModeSupport.buildUrl("127.0.0.1", 8080))
    }

    @Test
    fun originOfReturnsTheSchemeHostAndPort() {
        assertEquals("http://127.0.0.1:49374", WebModeSupport.originOf("http://127.0.0.1:49374"))
    }

    @Test
    fun isSameOriginMatchesApiXhrsUnderTheSameOrigin() {
        val origin = checkNotNull(WebModeSupport.originOf("http://127.0.0.1:49374"))
        assertTrue(WebModeSupport.isSameOrigin("http://127.0.0.1:49374/api/session?directory=/x", origin))
        assertTrue(WebModeSupport.isSameOrigin("http://127.0.0.1:49374/", origin))
    }

    @Test
    fun isSameOriginRejectsOtherOriginsAndPrefixLookalikes() {
        val origin = checkNotNull(WebModeSupport.originOf("http://127.0.0.1:4096"))
        assertFalse(WebModeSupport.isSameOrigin("http://127.0.0.1:40960/api/session", origin))
        assertFalse(WebModeSupport.isSameOrigin("http://evil.example.com/", origin))
    }
}
