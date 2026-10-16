package com.zhucanle.opencodev2.ide.api

import com.zhucanle.opencodev2.ide.api.models.SessionExecutionEndedEvent
import com.zhucanle.opencodev2.ide.api.models.SessionExecutionStartedEvent
import com.zhucanle.opencodev2.ide.api.models.SessionStepEndedEvent
import com.zhucanle.opencodev2.ide.api.models.UnknownEvent
import com.zhucanle.opencodev2.ide.integration.FakeOpenCodeServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Fixture-driven tests for the committed v2 wire capture (`src/test/resources/v2/`).
 *
 * [SseFixtureTest] replays the captured `/api/event` frames through the pure
 * [SseEventListener.decodeFrame] and pins the v2 event counts; [V2JsonFixtureTest] feeds the
 * captured `{data: ...}` JSON bodies through a real [OpenCodeApiClient] backed by the fake server
 * and pins the model field mapping. These fixtures previously had no test referencing them.
 */
class SseFixtureTest {

    /** The real v2 type names the plugin maps to concrete events. */
    private val knownV2Types = setOf(
        "session.execution.started",
        "session.execution.succeeded",
        "session.execution.failed",
        "session.execution.interrupted",
        "session.step.ended"
    )

    private fun fixtureLines(): List<String> =
        readFixture("/v2/event-frame.txt").lines()

    private fun dataFrames(): List<String> = fixtureLines()
        .filter { it.startsWith("data:") }
        .map { it.substring("data:".length).trim() }
        .filter { it.isNotEmpty() }

    @Test
    fun everyDataFrameDecodesToAnEvent() {
        val frames = dataFrames()
        assertTrue("the capture must contain data frames", frames.isNotEmpty())
        for (frame in frames) {
            assertNotNull("frame must decode into an event: ${frame.take(100)}", SseEventListener.decodeFrame(frame))
        }
    }

    @Test
    fun knownV2TypesNeverDecodeToUnknownEvent() {
        val unknownTypes = dataFrames()
            .mapNotNull { SseEventListener.decodeFrame(it) }
            .filterIsInstance<UnknownEvent>()
            .map { it.type }
        val unexpected = unknownTypes.filter { it in knownV2Types }
        assertTrue("known v2 types must not fall through to UnknownEvent: $unexpected", unexpected.isEmpty())
    }

    @Test
    fun decodesTheCapturedV2EventCounts() {
        val events = dataFrames().mapNotNull { SseEventListener.decodeFrame(it) }

        assertEquals("session.execution.started frames", 4, events.count { it is SessionExecutionStartedEvent })

        val ended = events.filterIsInstance<SessionExecutionEndedEvent>()
        assertEquals("session.execution.succeeded|failed|interrupted frames", 4, ended.size)
        assertEquals("succeeded frames", 3, ended.count { it.outcome == "succeeded" })
        assertEquals("failed frames", 1, ended.count { it.outcome == "failed" })
        assertEquals("interrupted frames", 0, ended.count { it.outcome == "interrupted" })

        assertEquals("session.step.ended frames", 6, events.count { it is SessionStepEndedEvent })
    }

    @Test
    fun failedFrameCarriesTheProviderErrorMessage() {
        val failed = dataFrames()
            .mapNotNull { SseEventListener.decodeFrame(it) }
            .filterIsInstance<SessionExecutionEndedEvent>()
            .single { it.outcome == "failed" }
        assertTrue(
            "the failed frame must carry the provider error message",
            failed.errorMessage.orEmpty().isNotBlank()
        )
    }

    @Test
    fun heartbeatLinesAreIgnored() {
        val lines = fixtureLines()
        val heartbeats = lines.count { it.startsWith(":") }
        assertTrue("the capture must contain heartbeat lines", heartbeats > 0)
        // Heartbeats are SSE comments, never data frames: they carry no event at all.
        assertTrue(
            "no heartbeat line may be treated as a data frame",
            lines.filter { it.startsWith(":") }.none { it.startsWith("data:") }
        )
        assertEquals(
            "one event per data frame, heartbeats contribute none",
            dataFrames().size,
            dataFrames().count { SseEventListener.decodeFrame(it) != null }
        )
    }
}

/** Feeds the captured v2 JSON bodies through the real client to pin the model field mapping. */
class V2JsonFixtureTest {

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

    private fun client(): OpenCodeApiClient = OpenCodeApiClient("127.0.0.1", fake.activePort)

    @Test
    fun sessionListFixtureMapsIdsDirectoriesAndMissingFields() {
        fake.setSessions(readFixture("/v2/session-list.json"))

        val sessions = client().getSessions("/x")

        assertEquals(4, sessions.size)
        assertEquals("ses_ec15443abffeWLHK3SlLqhQDPf", sessions[0].id)
        assertEquals(
            "/private/var/folders/_h/mjq63c6s241b2tbrlr8f4d340000gn/T/claude-agent-tmp/opencode/v2spike",
            sessions[0].directory
        )
        // The 4th captured session is the failed turn: no model and no title on the wire.
        assertEquals("ses_ec15ac47dffey3rdTfZFb6rSX9", sessions[3].id)
        assertNull("the failed session has no title in the capture", sessions[3].title)
    }

    @Test
    fun sessionDiffFixtureMapsFileDiffFieldsAndDecodesEscapedPatches() {
        fake.setCurrentDiffResponse(readFixture("/v2/session-diff.json"))

        val diffs = client().getSessionDiff("ses_ec15a2197ffefz47oVYIfAxUTQ", "/x")

        assertEquals(2, diffs.size)

        val modified = diffs[0]
        assertEquals("hello.txt", modified.file)
        assertEquals(3, modified.additions)
        assertEquals(1, modified.deletions)
        assertEquals("modified", modified.status)
        // `\n` escapes in the JSON patch must decode to real newlines.
        assertTrue("patch must carry decoded newlines", modified.patch.contains("-hello\n+alpha\n+beta\n+gamma\n"))

        val added = diffs[1]
        assertEquals("notes.md", added.file)
        assertEquals(1, added.additions)
        assertEquals(0, added.deletions)
        assertEquals("added", added.status)
        assertTrue("added patch must include the new-file marker", added.patch.contains("new file mode"))
    }

    @Test
    fun emptyDiffEnvelopeMapsToAnEmptyList() {
        fake.setCurrentDiffResponse("""{"data":[]}""")
        assertTrue(client().getSessionDiff("ses_1", "/x").isEmpty())
    }

    @Test
    fun emptySessionListEnvelopeMapsToAnEmptyList() {
        fake.setSessions("""{"data":[]}""")
        assertTrue(client().getSessions("/x").isEmpty())
    }
}

/** Reads a test resource as UTF-8 text, failing loudly when the fixture is missing. */
internal fun readFixture(path: String): String =
    (object {}.javaClass.getResourceAsStream(path)?.bufferedReader()?.use { it.readText() })
        ?: error("Missing test fixture on the classpath: $path")
