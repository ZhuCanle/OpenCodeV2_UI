package com.zhucanle.opencodev2.ide

import com.intellij.history.Label
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.zhucanle.opencodev2.ide.api.OpenCodeApiClient
import com.zhucanle.opencodev2.ide.api.models.FileDiff
import com.zhucanle.opencodev2.ide.diff.DiffViewerService
import com.zhucanle.opencodev2.ide.integration.FakeOpenCodeServer
import com.zhucanle.opencodev2.ide.session.SessionManager
import java.io.File
import java.nio.file.Files

class OpenCodeConnectionRecoveryTest : BasePlatformTestCase() {
    private lateinit var server: FakeOpenCodeServer
    private lateinit var manager: SessionManager
    private lateinit var service: OpenCodeService

    override fun setUp() {
        super.setUp()
        server = FakeOpenCodeServer(0).also { it.start() }
        manager = object : SessionManager(project) {
            override fun createSystemLabel(name: String): Label? = null
        }
        service = OpenCodeService(project)
        service.setTestDeps(manager, DiffViewerService(project), OpenCodeApiClient("127.0.0.1", server.activePort))
    }

    override fun tearDown() {
        try {
            Disposer.dispose(service)
            Disposer.dispose(manager)
            server.stop()
        } finally {
            super.tearDown()
        }
    }

    private fun invoke(name: String) {
        OpenCodeService::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(service)
    }

    fun testDisconnectAllowsSameSessionToStartAFreshTurn() {
        assertTrue(manager.onTurnStart("session"))
        invoke("disconnectAndReset")
        assertTrue("An abandoned turn must not block the next task", manager.onTurnStart("session"))
    }

    fun testMalformedOwnershipResponseDoesNotOpenEventStream() {
        server.setSessions("{\"data\":{}}")
        invoke("connectToSse")
        val field = OpenCodeService::class.java.getDeclaredField("sseListener").apply { isAccessible = true }
        assertNull("Retry ownership initialization before subscribing", field.get(service))
        server.setSessions("{\"data\":[]}")
        invoke("connectToSse")
        assertNotNull("A valid empty list still allows a connection", field.get(service))
    }

    fun testStreamLossAllowsSameSessionToStartAFreshTurn() {
        val update = OpenCodeService::class.java.getDeclaredMethod("updateConnectionState", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }
        update.invoke(service, true)
        assertTrue(manager.onTurnStart("session"))
        update.invoke(service, false)
        assertTrue(manager.onTurnStart("session"))
    }

    fun testStreamLossPreservesCompletedDiffButDiscardsInFlightResponse() {
        checkCompletedDiffSurvives {
            val update = OpenCodeService::class.java.getDeclaredMethod("updateConnectionState", Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }
            update.invoke(service, true)
            update.invoke(service, false)
        }
    }

    fun testManualDisconnectPreservesCompletedDiffInCustomDirectory() {
        checkCompletedDiffSurvives { invoke("disconnectAndReset") }
    }

    private fun checkCompletedDiffSurvives(disconnect: () -> Unit) {
        val directory = Files.createTempDirectory("connection-pending").toFile()
        try {
            manager.activeBasePath = directory.path
            File(directory, "a.kt").writeText("AI edit")
            manager.onTurnStart("completed")
            manager.onFileEdited("completed", "a.kt")
            val snapshot = checkNotNull(manager.onTurnEnd("completed"))
                .copy(capturedBeforeContent = mapOf("a.kt" to "original"))
            val diffs = listOf(FileDiff("a.kt", "", 1, 1, "modified"))
            val pending = manager.getProcessedDiffs(diffs, snapshot).single()
            disconnect()
            assertSame(pending, manager.getDiffForFile("a.kt"))
            assertTrue(manager.isCurrentScope(pending.scopeVersion))
            assertTrue("An old API response must not replace the preserved snapshot",
                manager.getProcessedDiffs(diffs, snapshot).isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }
}
