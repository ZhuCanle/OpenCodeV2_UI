package com.zhucanle.opencodev2.ide.api

import com.zhucanle.opencodev2.ide.integration.FakeOpenCodeServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Wire-level tests for the opencode v2 `/api/` client surface.
 *
 * Every v2 route answers with a `{data: ...}` envelope behind HTTP Basic auth; these tests pin
 * the envelope unwrapping, the auth behaviour, and the query parameters the plugin relies on.
 */
class OpenCodeApiClientV2Test {

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
    fun getSessionsUnwrapsDataEnvelope() {
        fake.setSessions(
            """{"data":[{"id":"ses_1","projectID":"p","location":{"directory":"/x"},"title":"t","time":{"created":1.0,"updated":2.0}}]}"""
        )
        val client = OpenCodeApiClient("127.0.0.1", fake.activePort, "opencode", "pw")
        assertEquals(1, client.getSessions("/x").size)
    }

    @Test
    fun getSessionsMapsDirectoryFromLocation() {
        fake.setSessions(
            """{"data":[{"id":"ses_1","projectID":"p","location":{"directory":"/work/repo"},"title":"t"}]}"""
        )
        val session = client().getSessions("/work/repo").single()
        assertEquals("ses_1", session.id)
        assertEquals("/work/repo", session.directory)
    }

    @Test
    fun getSessionsReturnsEmptyListOnEmptyData() {
        fake.setSessions("""{"data":[]}""")
        assertTrue(client().getSessions("/x").isEmpty())
    }

    @Test
    fun getActiveSessionHandlesEmptyObject() {
        val client = OpenCodeApiClient("127.0.0.1", fake.activePort, "opencode", "pw")
        assertNull(client.getActiveSession("/x"))   // server returns {"data":{}}
    }

    @Test
    fun getActiveSessionUnwrapsDataObject() {
        fake.setActiveSession("""{"data":{"id":"ses_9","projectID":"p","location":{"directory":"/x"}}}""")
        val session = client().getActiveSession("/x")
        assertNotNull(session)
        assertEquals("ses_9", session?.id)
        assertEquals("/x", session?.directory)
    }

    @Test
    fun checkHealthHitsApiInfo() {
        assertTrue(client().checkHealth("/x"))
    }

    @Test
    fun checkHealthIsFalseWhenUnauthorized() {
        fake.requireAuth("opencode", "pw")
        val wrong = OpenCodeApiClient("127.0.0.1", fake.activePort, "opencode", "wrong")
        assertFalse(wrong.checkHealth("/x"))
    }

    @Test
    fun checkAuthIsTrueForValidCredentials() {
        fake.requireAuth("opencode", "pw")
        assertTrue(client("pw").checkAuth("/x"))
    }

    @Test
    fun checkAuthIsFalseForWrongPassword() {
        fake.requireAuth("opencode", "pw")
        val wrong = OpenCodeApiClient("127.0.0.1", fake.activePort, "opencode", "wrong")
        assertFalse("A 401 on an authenticated route must fail the probe", wrong.checkAuth("/x"))
    }

    @Test
    fun checkAuthIsFalseWhenServerEnforcesAuthAndClientHasNone() {
        fake.requireAuth("opencode", "pw")
        val anonymous = OpenCodeApiClient("127.0.0.1", fake.activePort)
        assertFalse(anonymous.checkAuth("/x"))
    }

    @Test
    fun getSessionDiffUnwrapsData() {
        fake.setDiffResponse("msg_1", """{"data":[{"file":"a.kt","patch":"@@","additions":1,"deletions":0,"status":"modified"}]}""")
        val client = OpenCodeApiClient("127.0.0.1", fake.activePort, "opencode", "pw")
        assertEquals("a.kt", client.getSessionDiff("ses_1", "/x", "msg_1").first().file)
    }

    @Test
    fun getSessionDiffMapsAllV2Fields() {
        fake.setDiffResponse(
            "msg_2",
            """{"data":[{"file":"notes.md","patch":"diff --git a/notes.md b/notes.md\n@@ -0,0 +1 @@\n+summary\n","additions":1,"deletions":0,"status":"added"}]}"""
        )
        val diff = client().getSessionDiff("ses_1", "/x", "msg_2").single()
        assertEquals("notes.md", diff.file)
        assertEquals(1, diff.additions)
        assertEquals(0, diff.deletions)
        assertEquals("added", diff.status)
        assertTrue(diff.patch.contains("@@ -0,0 +1 @@"))
    }

    @Test
    fun getSessionDiffReturnsEmptyListForEmptyEnvelope() {
        fake.setDiffResponse("msg_3", """{"data":[]}""")
        assertTrue(client().getSessionDiff("ses_1", "/x", "msg_3").isEmpty())
    }

    @Test
    fun getSessionDiffSendsFromParameter() {
        client().getSessionDiff("ses_1", "/x", "msg_1")
        assertTrue("from must be sent when provided", fake.diffQueries.last().contains("from=msg_1"))
    }

    @Test
    fun getSessionDiffOmitsFromParameterWhenNull() {
        client().getSessionDiff("ses_1", "/x")
        assertFalse("from must be omitted when null", fake.diffQueries.last().contains("from="))
    }

    @Test
    fun requestsCarryBasicAuth() {
        fake.requireAuth("opencode", "pw")
        val client = OpenCodeApiClient("127.0.0.1", fake.activePort, "opencode", "wrong")
        assertTrue(client.getSessions("/x").isEmpty())  // 401 -> empty
    }

    @Test
    fun requestsWithoutAuthAreUnauthorized() {
        // When the service enforces auth, a client with no password (no Authorization header) is
        // rejected with 401, which the client surfaces as an empty result.
        fake.requireAuth("opencode", "pw")
        val anonymous = OpenCodeApiClient("127.0.0.1", fake.activePort)
        assertTrue(anonymous.getSessions("/x").isEmpty())
        assertNull(anonymous.getActiveSession("/x"))
    }
}
