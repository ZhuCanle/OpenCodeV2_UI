package com.zhucanle.opencodev2.ide.api

import com.zhucanle.opencodev2.ide.integration.FakeOpenCodeServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for the connect-time version gate: `/api/info` version extraction plus the pure
 * major-version parser the plugin uses to decide whether to warn about an unsupported server.
 */
class OpenCodeVersionGateTest {

    private lateinit var fake: FakeOpenCodeServer

    @Before
    fun setUp() {
        fake = FakeOpenCodeServer(0)
        fake.start()
    }

    @After
    fun tearDown() {
        fake.stop()
    }

    private fun client(password: String = FakeOpenCodeServer.DEFAULT_PASSWORD): OpenCodeApiClient =
        OpenCodeApiClient("127.0.0.1", fake.activePort, FakeOpenCodeServer.DEFAULT_USER, password)

    @Test
    fun parseMajorVersionReadsLeadingComponent() {
        assertEquals(2, OpenCodeApiClient.parseMajorVersion("2.0.29"))
        assertEquals(1, OpenCodeApiClient.parseMajorVersion("1.2.3"))
        assertEquals(2, OpenCodeApiClient.parseMajorVersion("2"))
        assertEquals(2, OpenCodeApiClient.parseMajorVersion("2.1.0-beta.1"))
    }

    @Test
    fun parseMajorVersionReturnsNullWhenUnparseable() {
        assertNull(OpenCodeApiClient.parseMajorVersion(null))
        assertNull(OpenCodeApiClient.parseMajorVersion(""))
        assertNull(OpenCodeApiClient.parseMajorVersion("  "))
        assertNull(OpenCodeApiClient.parseMajorVersion("v2.0.29"))
    }

    @Test
    fun getServerVersionReadsInfoRoute() {
        // The fake serves the captured v2 `/api/info` body with version "2.0.29".
        assertEquals("2.0.29", client().getServerVersion())
    }

    @Test
    fun getServerVersionReturnsNullWhenFieldMissing() {
        fake.setInfoResponse("""{"pid":1}""")
        assertNull(client().getServerVersion())
    }

    @Test
    fun getServerVersionReturnsNullOnUnauthorized() {
        fake.requireAuth("opencode", "pw")
        assertNull(client("wrong").getServerVersion())
    }
}
