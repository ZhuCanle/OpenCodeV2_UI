package com.zhucanle.opencodev2.ide.integration

import com.zhucanle.opencodev2.ide.OpenCodeService
import com.zhucanle.opencodev2.ide.api.OpenCodeApiClient
import com.zhucanle.opencodev2.ide.api.models.DiffEntry
import com.zhucanle.opencodev2.ide.diff.DiffViewerService
import com.zhucanle.opencodev2.ide.session.SessionManager
import com.intellij.history.Label
import com.intellij.openapi.project.Project
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * End-to-end diff-pipeline tests driven by the v2 fake server.
 *
 * The plugin's v2 turn is: `session.execution.started` (turn start) -> `session.step.ended`
 * (changed files + assistant message id) -> `session.execution.succeeded` (turn end), followed by a
 * `GET /api/session/{id}/diff` call **without** `from`. The wire carries no `before`/`after`
 * content: the "before" side comes from local snapshots (`simulateCapture`) and the "after" side
 * from the file on disk, so every scenario writes its expected content under the project root.
 */
class OpenCodeLogicTest {

    private lateinit var projectDir: File
    private lateinit var server: FakeOpenCodeServer
    private lateinit var runner: TestRunner
    private var turnCount = 0
    /** Global debounce value captured in [setUp] and restored in [tearDown]. */
    private var originalDebounceMs = 0L

    @Before
    fun setUp() {
        originalDebounceMs = OpenCodeService.DEBOUNCE_MS
        OpenCodeService.DEBOUNCE_MS = 0L
        projectDir = Files.createTempDirectory("opencode-logic").toFile()
        server = FakeOpenCodeServer(0)
        server.start()
        runner = TestRunner(projectDir, server.activePort)
        runner.awaitSseClient(server)
    }

    @After
    fun tearDown() {
        OpenCodeService.DEBOUNCE_MS = originalDebounceMs
        server.stop()
        projectDir.deleteRecursively()
    }

    // ==================== Turn drivers (v2 wire) ====================

    /** Emits `session.execution.started` and blocks until the plugin has begun the turn. */
    private fun startTurn(sessionId: String = SESSION_ID) {
        turnCount++
        server.broadcast("""{"type":"session.execution.started","data":{"sessionID":"$sessionId"}}""")
        runner.sessionManager.awaitTurnStart(turnCount)
    }

    /** Emits `session.step.ended`, the v2 carrier of the changed-file set and the message id. */
    private fun stepEnded(messageId: String, files: List<String>, sessionId: String = SESSION_ID) {
        val encoded = files.joinToString(",") { "\"$it\"" }
        server.broadcast(
            """{"type":"session.step.ended","data":{"sessionID":"$sessionId",""" +
                """"assistantMessageID":"$messageId","finish":"stop","snapshot":"snap-1","files":[$encoded]}}"""
        )
    }

    /** Emits `session.execution.succeeded`, the v2 turn-end signal. */
    private fun endTurn(sessionId: String = SESSION_ID) {
        server.broadcast("""{"type":"session.execution.succeeded","data":{"sessionID":"$sessionId"}}""")
    }

    private fun writeFile(relativePath: String, content: String) {
        val file = File(projectDir, relativePath)
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    // ==================== Scenarios ====================

    /** Normal modification: server reports `modified`; before comes from the local capture. */
    @Test
    fun normalModificationResolvesBeforeFromCaptureAndAfterFromDisk() {
        writeFile("a.kt", "new")
        startTurn()
        runner.sessionManager.simulateCapture("a.kt", "old")

        stepEnded("msg-1", listOf("a.kt"))
        server.setCurrentDiffResponse(diffJson("a.kt", "modified"))
        endTurn()

        runner.awaitDiffs(1)
        val entry = runner.lastShown().single { it.file == "a.kt" }
        assertEquals("old", entry.beforeContent)
        assertEquals("new", entry.resolvedAfter)
    }

    /**
     * A server `status = "added"` must force an empty before, even if a stale capture exists for the
     * path: reading the freshly written on-disk content as "before" would collapse the diff.
     */
    @Test
    fun addedStatusResolvesEmptyBeforeEvenWithStaleCapture() {
        writeFile("new.kt", "fun new() {}")
        startTurn()
        runner.sessionManager.simulateFileCreation("new.kt")
        runner.sessionManager.simulateCapture("new.kt", "SHOULD_NOT_BE_USED")

        stepEnded("msg-2", listOf("new.kt"))
        server.setCurrentDiffResponse(diffJson("new.kt", "added"))
        endTurn()

        runner.awaitDiffs(1)
        val entry = runner.lastShown().single { it.file == "new.kt" }
        assertEquals("", entry.beforeContent)
        assertEquals("fun new() {}", entry.resolvedAfter)
        assertTrue("VFS creation + server 'added' must classify as a new file", entry.isNewFile)
    }

    /** Turn isolation: a later chat-only turn must not re-surface the previous turn's diff. */
    @Test
    fun turnIsolationDoesNotLeakAcrossTurns() {
        // Turn 1: modifies a.kt and is surfaced.
        writeFile("a.kt", "new")
        startTurn()
        runner.sessionManager.simulateCapture("a.kt", "old")
        stepEnded("msg-3", listOf("a.kt"))
        server.setCurrentDiffResponse(diffJson("a.kt", "modified"))
        endTurn()
        runner.awaitDiffs(1)
        assertEquals(listOf("a.kt"), runner.lastShown().map { it.file })
        runner.clearShown()

        // Turn 2: pure chat, server reports nothing.
        startTurn()
        stepEnded("msg-4", emptyList())
        server.setCurrentDiffResponse("""{"data":[]}""")
        endTurn()

        runner.awaitNoDiffs()
    }

    /** A physically deleted file is rescued from the local capture even when the server is silent. */
    @Test
    fun deletionIsRescuedFromCapturedContent() {
        writeFile("to_delete.kt", "delete me")
        startTurn()
        runner.sessionManager.simulateCapture("to_delete.kt", "delete me")

        assertTrue("precondition: file must exist on disk", File(projectDir, "to_delete.kt").delete())
        runner.sessionManager.simulateVfsChange("to_delete.kt")

        stepEnded("msg-5", listOf("to_delete.kt"))
        server.setCurrentDiffResponse("""{"data":[]}""")
        endTurn()

        runner.awaitDiffs(1)
        val entry = runner.lastShown().single { it.file == "to_delete.kt" }
        assertEquals("delete me", entry.beforeContent)
        assertEquals("", entry.resolvedAfter)
    }

    /** Server-authoritative path: the turn diff alone surfaces a file, with no VFS/local capture. */
    @Test
    fun serverAuthoritativeDiffIsShownWithoutLocalSignals() {
        writeFile("server_only.kt", "server content")
        startTurn()

        stepEnded("msg-6", listOf("server_only.kt"))
        server.setCurrentDiffResponse(diffJson("server_only.kt", "modified"))
        endTurn()

        runner.awaitDiffs(1)
        val entry = runner.lastShown().single { it.file == "server_only.kt" }
        assertEquals("server content", entry.resolvedAfter)
    }

    /** A user edit during the turn must never be rescued as an AI ghost diff. */
    @Test
    fun userEditedFileIsNotRescuedAsGhostDiff() {
        writeFile("ai_edited.kt", "ai content")
        writeFile("user_edited.kt", "user content")
        startTurn()
        runner.sessionManager.simulateUserEdit("user_edited.kt")

        stepEnded("msg-7", listOf("ai_edited.kt"))
        server.setCurrentDiffResponse(diffJson("ai_edited.kt", "modified"))
        endTurn()

        runner.awaitDiffs(1)
        val files = runner.lastShown().map { it.file }
        assertTrue("AI-edited file must be shown, got $files", files.contains("ai_edited.kt"))
        assertFalse("user-edited file must not be rescued, got $files", files.contains("user_edited.kt"))
    }

    private fun diffJson(file: String, status: String): String =
        """{"data":[{"file":"$file","patch":"@@ -1 +1 @@","additions":1,"deletions":1,"status":"$status"}]}"""

    companion object {
        private const val SESSION_ID = "s1"
    }
}

/**
 * Wires a real [OpenCodeApiClient] against the fake server together with the mock IDE services the
 * diff pipeline needs, and exposes await-style assertions so tests never sleep blindly.
 */
class TestRunner(basePath: File, serverPort: Int) {
    val mockProject: Project
    val mockDiffViewer: MockDiffViewerService
    val sessionManager: TestSessionManager
    val openCodeService: OpenCodeService

    init {
        val basePathString = basePath.absolutePath
        mockProject = Proxy.newProxyInstance(
            Project::class.java.classLoader,
            arrayOf(Project::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getBasePath" -> basePathString
                "isDisposed" -> false
                "toString" -> "MockProject"
                "hashCode" -> 12345
                "equals" -> false
                else -> null
            }
        } as Project

        mockDiffViewer = MockDiffViewerService(mockProject)
        sessionManager = TestSessionManager(mockProject)

        // Real ApiClient pointed at the fake server; auth is not enforced unless the test opts in.
        val apiClient = OpenCodeApiClient("127.0.0.1", serverPort)

        openCodeService = OpenCodeService(mockProject)
        openCodeService.setTestDeps(sessionManager, mockDiffViewer, apiClient)

        // Run UI callbacks inline. UI services (notifications, diff windows) are not initialized in
        // a plain unit test, so swallow their failures the same way the production EDT would.
        openCodeService.invokeLater = { runnable ->
            try {
                runnable.run()
            } catch (e: Throwable) {
                println("[TestRunner] UI callback skipped: ${e.message}")
            }
        }

        val connectMethod = OpenCodeService::class.java.getDeclaredMethod("connectToSse")
        connectMethod.isAccessible = true
        connectMethod.invoke(openCodeService)
    }

    /** Blocks until the fake server sees the plugin's SSE connection. */
    fun awaitSseClient(server: FakeOpenCodeServer, timeoutMs: Long = 5000) {
        val start = System.currentTimeMillis()
        while (!server.hasSseClient()) {
            if (System.currentTimeMillis() - start > timeoutMs) {
                throw AssertionError("Timeout waiting for SSE client to connect")
            }
            Thread.sleep(20)
        }
    }

    fun lastShown(): List<DiffEntry> =
        mockDiffViewer.shownDiffs.lastOrNull() ?: throw AssertionError("No diffs shown")

    /** Drops all batches recorded so far, so the next await only observes new activity. */
    fun clearShown() {
        mockDiffViewer.shownDiffs.clear()
    }

    fun awaitDiffs(count: Int, timeoutMs: Long = 3000) {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (mockDiffViewer.shownDiffs.size >= count) return
            Thread.sleep(20)
        }
        throw AssertionError("Timeout waiting for $count diff batch(es), got ${mockDiffViewer.shownDiffs.size}")
    }

    fun awaitNoDiffs(windowMs: Long = 1000) {
        Thread.sleep(windowMs)
        if (mockDiffViewer.shownDiffs.isNotEmpty()) {
            throw AssertionError("Expected no diffs, got ${mockDiffViewer.shownDiffs.map { batch -> batch.map { it.file } }}")
        }
    }
}

class MockDiffViewerService(project: Project) : DiffViewerService(project) {
    val shownDiffs = CopyOnWriteArrayList<List<DiffEntry>>()

    override fun showMultiFileDiff(entries: List<DiffEntry>, initialIndex: Int?) {
        println("  [MockViewer] Showing diffs: ${entries.map { it.file }}")
        shownDiffs.add(entries)
    }
}

/**
 * [SessionManager] double for the fake-server tests: keeps LocalHistory out of the picture and lets
 * the test inject the VFS / capture signals that would otherwise arrive from the IDE.
 */
class TestSessionManager(project: Project) : SessionManager(project) {
    private val simulatedCreatedFiles = mutableSetOf<String>()
    private val simulatedVfsChangedFiles = mutableSetOf<String>()
    private val simulatedServerEditedFiles = mutableSetOf<String>()
    private val simulatedUserEditedFiles = mutableSetOf<String>()
    private val simulatedCapturedContent = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val turnStartCount = AtomicInteger(0)

    override fun createSystemLabel(name: String): Label? = null

    override fun onTurnStart(sessionId: String): Boolean {
        simulatedCreatedFiles.clear()
        simulatedVfsChangedFiles.clear()
        simulatedServerEditedFiles.clear()
        simulatedUserEditedFiles.clear()
        simulatedCapturedContent.clear()
        val started = super.onTurnStart(sessionId)
        if (started) turnStartCount.incrementAndGet()
        return started
    }

    /** Blocks until [expected] turns have started (the SSE events are dispatched off-thread). */
    fun awaitTurnStart(expected: Int, timeoutMs: Long = 3000) {
        val start = System.currentTimeMillis()
        while (turnStartCount.get() < expected) {
            if (System.currentTimeMillis() - start > timeoutMs) {
                throw AssertionError("Timeout waiting for turn start #$expected (got ${turnStartCount.get()})")
            }
            Thread.sleep(10)
        }
    }

    override fun onTurnEnd(sessionId: String): com.zhucanle.opencodev2.ide.session.TurnSnapshot? {
        val realSnapshot = super.onTurnEnd(sessionId) ?: return null

        // Merge simulated data into the snapshot so the pipeline sees a realistic turn.
        return realSnapshot.copy(
            aiCreatedFiles = realSnapshot.aiCreatedFiles + simulatedCreatedFiles,
            vfsChangedFiles = realSnapshot.vfsChangedFiles + simulatedVfsChangedFiles,
            serverEditedFiles = realSnapshot.serverEditedFiles + simulatedServerEditedFiles,
            userEditedFiles = realSnapshot.userEditedFiles + simulatedUserEditedFiles,
            capturedBeforeContent = realSnapshot.capturedBeforeContent + simulatedCapturedContent
        )
    }

    fun simulateUserEdit(file: String) {
        simulatedUserEditedFiles.add(file)
        simulatedVfsChangedFiles.add(file)
    }

    fun simulateCapture(path: String, content: String) {
        simulatedCapturedContent[path] = content
    }

    fun simulateFileCreation(file: String) {
        simulatedCreatedFiles.add(file)
        simulatedVfsChangedFiles.add(file)
    }

    fun simulateServerEdited(file: String) {
        simulatedServerEditedFiles.add(file)
    }

    fun simulateVfsChange(file: String) {
        simulatedVfsChangedFiles.add(file)
    }
}
