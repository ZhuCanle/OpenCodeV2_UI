package com.zhucanle.opencodev2.ide.api

import com.zhucanle.opencodev2.ide.api.models.OpenCodeEvent
import com.zhucanle.opencodev2.ide.api.models.OpenCodeEventDeserializer
import com.zhucanle.opencodev2.ide.api.models.SessionCreatedEvent
import com.zhucanle.opencodev2.ide.api.models.SessionExecutionEndedEvent
import com.zhucanle.opencodev2.ide.api.models.SessionExecutionStartedEvent
import com.zhucanle.opencodev2.ide.api.models.SessionStepEndedEvent
import com.zhucanle.opencodev2.ide.api.models.UnknownEvent
import com.google.gson.GsonBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parsing tests for the opencode v2 `/api/event` SSE stream.
 *
 * The v2 wire format carries the event name in the JSON `type` field of a **raw JSON object**
 * (there is no `event:` line and `data` is not double-encoded). These tests pin the mapping from
 * the real v2 type names to the event model, the strict v2-only fallback to [UnknownEvent], and
 * the raw-frame decoder.
 */
class SseEventParsingTest {

    private val gson = GsonBuilder()
        .registerTypeAdapter(OpenCodeEvent::class.java, OpenCodeEventDeserializer())
        .create()

    private fun parse(json: String): OpenCodeEvent =
        gson.fromJson(json, OpenCodeEvent::class.java)

    @Test
    fun parsesExecutionStartedAsTurnStart() {
        val e = parse("""{"id":"evt_1","type":"session.execution.started","data":{"sessionID":"s1"}}""")
        assertTrue(e is SessionExecutionStartedEvent && e.sessionID == "s1")
    }

    @Test
    fun parsesSessionCreated() {
        // The captured v2 frame: location-scoped, so it also proves the session belongs to us.
        val e = parse(
            """{"id":"evt_2","created":1792052378577,"type":"session.created",""" +
                """"location":{"directory":"/work/repo"},"data":{"sessionID":"ses_1"}}"""
        )
        assertTrue(e is SessionCreatedEvent && e.sessionID == "ses_1")
    }

    @Test
    fun parsesExecutionSucceededAsTurnEnd() {
        val e = parse("""{"type":"session.execution.succeeded","data":{"sessionID":"s1"}}""")
        assertTrue(e is SessionExecutionEndedEvent && e.outcome == "succeeded")
    }

    @Test
    fun parsesExecutionFailedWithProviderErrorMessage() {
        val e = parse(
            """{"type":"session.execution.failed","data":{"sessionID":"s1",""" +
                """"error":{"type":"provider.quota","message":"Insufficient Balance"}}}"""
        ) as SessionExecutionEndedEvent
        assertEquals("failed", e.outcome)
        assertEquals("Insufficient Balance", e.errorMessage)
    }

    @Test
    fun parsesInterruptedOutcome() {
        val e = parse("""{"type":"session.execution.interrupted","data":{"sessionID":"s1"}}""")
        assertTrue(e is SessionExecutionEndedEvent && e.outcome == "interrupted")
    }

    @Test
    fun dropsV2ExecutionFrameWithoutSessionId() {
        // A frame with no session id must not create turn state keyed under "".
        assertTrue(parse("""{"type":"session.execution.started","data":{}}""") is UnknownEvent)
        assertTrue(parse("""{"type":"session.execution.succeeded","data":{}}""") is UnknownEvent)
        assertTrue(parse("""{"type":"session.execution.succeeded","data":{"sessionID":"  "}}""") is UnknownEvent)
    }

    @Test
    fun dropsStepEndedFrameWithoutSessionId() {
        assertTrue(parse("""{"type":"session.step.ended","data":{"files":["a.kt"]}}""") is UnknownEvent)
    }

    @Test
    fun parsesStepEndedChangedFiles() {
        val e = parse("""{"type":"session.step.ended","data":{"sessionID":"s1","assistantMessageID":"m1","finish":"stop","files":["a.kt","b.kt"]}}""")
        assertEquals(listOf("a.kt", "b.kt"), (e as SessionStepEndedEvent).files)
    }

    @Test
    fun v1OnlyEventNamesDecodeToUnknownEvent() {
        // Strict v2-only: the v1 names are not routed any more, so they fall through to UnknownEvent.
        val v1Names = listOf(
            "session.diff", "session.idle", "session.status", "session.updated",
            "file.edited", "message.updated", "message.part.updated",
            "message.part.removed", "command.executed"
        )
        for (name in v1Names) {
            val e = parse("""{"type":"$name","properties":{"sessionID":"s1"}}""")
            assertTrue("$name must be an UnknownEvent", e is UnknownEvent && e.type == name)
        }
    }

    @Test
    fun decodeFrameReadsRawJsonObject() {
        val ev = SseEventListener.decodeFrame("""{"type":"session.execution.started","data":{"sessionID":"s1"}}""")
        assertTrue(ev is SessionExecutionStartedEvent)
    }

    @Test
    fun unknownEventIsIgnoredNotThrown() {
        assertTrue(parse("""{"type":"mcp.tools.changed","data":{}}""") is UnknownEvent)
    }
}
