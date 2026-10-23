package com.zhucanle.opencodev2.ide.api

import com.zhucanle.opencodev2.ide.util.PathUtil
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The opencode `/api/event` stream is **not** filtered by the `directory` query parameter: every
 * subscriber receives the events of every directory the server knows about, each tagged with
 * `location.directory`. Without a client-side check, an IDE window reacts to another project's
 * session and opens its diff tab in the wrong window.
 *
 * These tests pin the frame-level filter that keeps only the frames of the subscribed directory.
 */
class SseEventScopeTest {

    private fun frame(directory: String) =
        """{"id":"evt_1","created":1,"type":"session.step.ended",""" +
            """"location":{"directory":"$directory"},"data":{"sessionID":"s1","files":["a.kt"]}}"""

    @Test
    fun keepsFramesOfTheSubscribedDirectory() {
        val payload = frame("/Users/me/project")
        assertTrue(SseEventListener.isScopedTo(payload, "/Users/me/project"))
    }

    @Test
    fun dropsFramesOfAnotherDirectory() {
        val payload = frame("/Users/me/other-project")
        assertFalse(SseEventListener.isScopedTo(payload, "/Users/me/project"))
    }

    @Test
    fun ignoresTrailingSeparators() {
        val payload = frame("/Users/me/project/")
        assertTrue(SseEventListener.isScopedTo(payload, "/Users/me/project"))
    }

    @Test
    fun keepsFramesWithoutALocation() {
        val payload = """{"type":"server.connected","data":{}}"""
        assertTrue(SseEventListener.isScopedTo(payload, "/Users/me/project"))
    }

    @Test
    fun keepsUnparseableFrames() {
        assertTrue(SseEventListener.isScopedTo("not json", "/Users/me/project"))
    }

    @Test
    fun matchesWindowsSeparatorStyles() {
        assertTrue(
            PathUtil.sameDirectory(
                """C:\Users\me\project""",
                "C:/Users/me/project",
                caseInsensitive = false
            )
        )
    }

    @Test
    fun matchesWindowsPathsCaseInsensitively() {
        assertTrue(PathUtil.sameDirectory("C:/Users/Me/Project", "c:/users/me/project", caseInsensitive = true))
        assertFalse(PathUtil.sameDirectory("C:/Users/Me/Project", "c:/users/me/project", caseInsensitive = false))
    }

    @Test
    fun doesNotTreatSiblingPathsAsEqual() {
        assertFalse(PathUtil.sameDirectory("/Users/me/project", "/Users/me/project2", caseInsensitive = false))
    }
}
