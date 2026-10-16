package com.zhucanle.opencodev2.ide.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2 web-mode URL tests for [WebModeSupport.buildUrl].
 *
 * v2 serves its web UI from `/doc`, so the embedded browser must target that path instead of the
 * v1 root route. The bind-all host `0.0.0.0` is not navigable from a browser and is normalized to
 * `127.0.0.1`.
 */
class WebModeUrlTest {

    @Test
    fun normalizesBindAllHostToLoopbackForDocRoute() {
        assertEquals("http://127.0.0.1:49374/doc", WebModeSupport.buildUrl("0.0.0.0", 49374))
    }

    @Test
    fun keepsPlainHostAndTargetsDocRoute() {
        assertEquals("http://192.168.1.10:4096/doc", WebModeSupport.buildUrl("192.168.1.10", 4096))
    }

    @Test
    fun keepsLoopbackHostAsIs() {
        assertEquals("http://127.0.0.1:8080/doc", WebModeSupport.buildUrl("127.0.0.1", 8080))
    }

    @Test
    fun originOfStripsTheDocPath() {
        assertEquals("http://127.0.0.1:49374", WebModeSupport.originOf("http://127.0.0.1:49374/doc"))
    }

    @Test
    fun isSameOriginMatchesApiXhrsUnderTheSameOrigin() {
        val origin = checkNotNull(WebModeSupport.originOf("http://127.0.0.1:49374/doc"))
        assertTrue(WebModeSupport.isSameOrigin("http://127.0.0.1:49374/api/session?directory=/x", origin))
        assertTrue(WebModeSupport.isSameOrigin("http://127.0.0.1:49374/doc", origin))
    }

    @Test
    fun isSameOriginRejectsOtherOriginsAndPrefixLookalikes() {
        val origin = checkNotNull(WebModeSupport.originOf("http://127.0.0.1:4096/doc"))
        assertFalse(WebModeSupport.isSameOrigin("http://127.0.0.1:40960/api/session", origin))
        assertFalse(WebModeSupport.isSameOrigin("http://evil.example.com/", origin))
    }
}
