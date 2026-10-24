package com.zhucanle.opencodev2.ide

import com.intellij.history.Label
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.zhucanle.opencodev2.ide.api.OpenCodeApiClient
import com.zhucanle.opencodev2.ide.api.models.SessionCreatedEvent
import com.zhucanle.opencodev2.ide.api.models.SessionExecutionEndedEvent
import com.zhucanle.opencodev2.ide.api.models.SessionExecutionStartedEvent
import com.zhucanle.opencodev2.ide.api.models.SessionStepEndedEvent
import com.zhucanle.opencodev2.ide.diff.DiffViewerService
import com.zhucanle.opencodev2.ide.session.SessionManager
import com.zhucanle.opencodev2.ide.session.TurnSnapshot
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Tests the session attribution of the turn events.
 *
 * `session.execution.started|succeeded|failed` carry no `location.directory` on the v2 wire, so the
 * SSE scope filter keeps them (`SseEventListener.isScopedTo`) and **every** open window receives
 * them. Without attribution a window started a turn for another project's session and then showed
 * that session's diff in its own frame — the "diff appears in the wrong project" bug.
 *
 * Ownership is seeded from the server's directory-scoped session list on connect
 * ([OpenCodeService.setTestDeps]'s `ownedSessionIds` stands in for that list here) and extended by
 * the location-scoped `session.created` / `session.step.ended` frames.
 */
class OpenCodeServiceSessionScopeTest : BasePlatformTestCase() {

    /** Records the sessions a turn was started for; nothing else is needed for this test. */
    private class RecordingSessionManager(project: Project) : SessionManager(project) {
        val started = CopyOnWriteArrayList<String>()

        override fun onTurnStart(sessionId: String): Boolean {
            started.add(sessionId)
            return true
        }

        override fun onTurnEnd(sessionId: String): TurnSnapshot? = null

        override fun createSystemLabel(name: String): Label? = null
    }

    private lateinit var service: OpenCodeService
    private lateinit var sessionManager: RecordingSessionManager
    private lateinit var diffViewer: DiffViewerService

    override fun setUp() {
        super.setUp()
        sessionManager = RecordingSessionManager(project)
        diffViewer = DiffViewerService(project)
        service = OpenCodeService(project)
        service.setTestDeps(
            sessionManager,
            diffViewer,
            OpenCodeApiClient("127.0.0.1", 1),
            ownedSessionIds = listOf("ses_own")
        )
    }

    override fun tearDown() {
        try {
            Disposer.dispose(service)
        } finally {
            super.tearDown()
        }
    }

    fun testTurnStartOfAnotherProjectIsIgnored() {
        service.handleEvent(SessionExecutionStartedEvent("ses_foreign"))

        assertEmpty("A window must not start a turn for another project's session", sessionManager.started)
    }

    fun testTurnEndOfAnotherProjectIsIgnored() {
        service.handleEvent(SessionExecutionEndedEvent("ses_foreign", "succeeded"))

        assertEmpty("A window must not end (and diff) another project's session", sessionManager.started)
    }

    fun testTurnStartOfAnOwnedSessionIsHandled() {
        service.handleEvent(SessionExecutionStartedEvent("ses_own"))

        assertEquals(listOf("ses_own"), sessionManager.started.toList())
    }

    fun testCreatedSessionIsAdopted() {
        // `session.created` is location-scoped, so receiving it proves the session is ours even
        // when it was created after the connection seeded the ownership list.
        service.handleEvent(SessionCreatedEvent("ses_new"))
        service.handleEvent(SessionExecutionStartedEvent("ses_new"))

        assertEquals(listOf("ses_new"), sessionManager.started.toList())
    }

    fun testStepEndedAdoptsTheSession() {
        // `session.step.ended` is location-scoped as well: it re-adopts a session after a reconnect.
        service.handleEvent(SessionStepEndedEvent("ses_resumed", files = emptyList()))
        service.handleEvent(SessionExecutionStartedEvent("ses_resumed"))

        assertEquals(listOf("ses_resumed"), sessionManager.started.toList())
    }
}
