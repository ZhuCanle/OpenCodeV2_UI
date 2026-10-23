package com.zhucanle.opencodev2.ide.integration

import com.zhucanle.opencodev2.ide.OpenCodeService
import com.zhucanle.opencodev2.ide.api.OpenCodeApiClient
import com.zhucanle.opencodev2.ide.diff.DiffViewerService
import com.zhucanle.opencodev2.ide.session.SessionManager
import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.util.concurrent.CountDownLatch

/**
 * Local diff-pipeline integration tests.
 *
 * These exercise the accept/reject flow against a temporary Git repository and need neither the
 * opencode CLI nor a live server, so they always run as part of the default `test` task. The
 * CLI-dependent real-server test lives in [RealServerIntegrationTest].
 */
class RealProcessIntegrationTest : BasePlatformTestCase() {

    private companion object {
        /** Session id used for the turn bookkeeping; the pipeline is session-scoped. */
        const val TEST_SESSION = "ses_test"
    }

    // ==================== Tests ====================

    fun testAcceptAndReject() {
        val service = project.service<OpenCodeService>()
        val sm = project.service<SessionManager>()
        // The diff pipeline is exercised directly, so no live server is required here.
        val client = OpenCodeApiClient("127.0.0.1", 0)

        // Mock DiffViewerService to capture entries without trying to open real UI windows
        val mockDiffViewer = object : DiffViewerService(project) {
            override fun showMultiFileDiff(entries: List<com.zhucanle.opencodev2.ide.api.models.DiffEntry>, initialIndex: Int?) {
                println("MockViewer showing ${entries.size} diffs")
            }
        }

        service.setTestDeps(sm, mockDiffViewer, client)

        // Setup Git Repo in temp project
        val projectDir = File(checkNotNull(project.basePath) { "project basePath is null" })
        if (!projectDir.exists()) projectDir.mkdirs()

        runCommand(projectDir, "git", "init")
        // Config git user to allow committing
        runCommand(projectDir, "git", "config", "user.name", "TestUser")
        runCommand(projectDir, "git", "config", "user.email", "test@example.com")

        // 1. Create a file and commit it (Baseline state)
        // Use Real File System because SessionManager uses LocalFileSystem
        val relPath = "test.txt"
        val ioFile = File(projectDir, relPath)
        ioFile.writeText("Initial Content")

        // Refresh VFS to pick it up
        val vf = checkNotNull(
            com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ioFile)
        ) { "could not find $relPath in the VFS" }

        runCommand(projectDir, "git", "add", ".")
        runCommand(projectDir, "git", "commit", "-m", "Initial commit")

        // 2. Start Turn (Captures LocalHistory label)
        sm.onTurnStart(TEST_SESSION)
        sm.onFileEdited(TEST_SESSION, relPath)

        // Simulate AI modifying the file content (On Disk + Refresh)
        com.intellij.openapi.application.runWriteAction {
            com.intellij.openapi.vfs.VfsUtil.saveText(vf, "AI Modified Content")
        }

        // 3. Create Snapshot and Process Diffs
        val snapshot = sm.onTurnEnd(TEST_SESSION) ?: throw AssertionError("onTurnEnd returned null")
        val diffs = listOf(
            com.zhucanle.opencodev2.ide.api.models.FileDiff(
                file = relPath,
                patch = "",
                additions = 1,
                deletions = 1,
                status = "modified"
            )
        )
        val entries = sm.processDiffs(diffs, snapshot)
        assertTrue(entries.isNotEmpty())

        // In test environment, provide fallback explicitly
        val resolvedBefore = sm.resolveBeforeContent(relPath, diffs.first(), snapshot)
        val entry = entries.first().copy(resolvedBefore = resolvedBefore)

        // 4. Test REJECT
        // Expectation: File reverts to "Initial Content"
        val rejectLatch = CountDownLatch(1)
        sm.rejectDiff(entry) { success ->
            assertTrue("Reject should succeed", success)
            rejectLatch.countDown()
        }
        waitForLatch(rejectLatch)

        vf.refresh(false, false)
        val vfsContent = String(vf.contentsToByteArray())
        println("VFS Content after reject: $vfsContent")

        assertEquals("Initial Content", vfsContent)
        println("Reject verified: File reverted successfully.")

        // 5. Test ACCEPT
        // Reset to modified state for Accept test
        com.intellij.openapi.application.runWriteAction {
            com.intellij.openapi.vfs.VfsUtil.saveText(vf, "Final Accepted Content")
        }

        val acceptLatch = CountDownLatch(1)
        sm.acceptDiff(entry) { success ->
            assertTrue("Accept should succeed", success)
            acceptLatch.countDown()
        }
        waitForLatch(acceptLatch)

        // Verify git status -> Should be staged "M  test.txt" (M space)
        val status = runCommand(projectDir, "git", "status", "--porcelain")
        println("Git Status after accept: [$status]")
        assertTrue("File should be staged", status.trim().startsWith("M") || status.trim().startsWith("A"))
        println("Accept verified: File staged in git.")
    }

    fun testEmptyFileSafety() {
        val service = project.service<OpenCodeService>()
        val sm = project.service<SessionManager>()
        val client = OpenCodeApiClient("127.0.0.1", 0)

        val mockDiffViewer = object : DiffViewerService(project) {
            override fun showMultiFileDiff(entries: List<com.zhucanle.opencodev2.ide.api.models.DiffEntry>, initialIndex: Int?) {}
        }

        service.setTestDeps(sm, mockDiffViewer, client)

        val projectDir = File(checkNotNull(project.basePath) { "project basePath is null" })
        if (!projectDir.exists()) projectDir.mkdirs()

        runCommand(projectDir, "git", "init")
        runCommand(projectDir, "git", "config", "user.name", "TestUser")
        runCommand(projectDir, "git", "config", "user.email", "test@example.com")

        // 1. Create EMPTY file and commit
        val relPath = "empty.txt"
        val ioFile = File(projectDir, relPath)
        ioFile.writeText("")

        val vf = checkNotNull(
            com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ioFile)
        ) { "could not find $relPath in the VFS" }
        runCommand(projectDir, "git", "add", ".")
        runCommand(projectDir, "git", "commit", "-m", "Add empty file")

        // 2. AI Modifies it
        sm.onTurnStart(TEST_SESSION)

        com.intellij.openapi.application.runWriteAction {
            com.intellij.openapi.vfs.VfsUtil.saveText(vf, "AI Content")
        }

        // 3. Reject
        val snapshot = checkNotNull(sm.onTurnEnd(TEST_SESSION)) { "onTurnEnd returned null" }

        // Verify that 'isCreatedExplicitly' is FALSE: `saveText` is a modification, not a creation.
        assertFalse("Should not be marked as created", snapshot.aiCreatedFiles.contains(relPath))

        val diffs = listOf(
            com.zhucanle.opencodev2.ide.api.models.FileDiff(
                file = relPath,
                patch = "",
                additions = 1,
                deletions = 0,
                status = "modified"
            )
        )
        val entries = sm.processDiffs(diffs, snapshot)
        assertNotEmpty(entries)
        val entry = entries.first()

        // Verify isNewFile logic (Strict check)
        assertFalse("Should NOT be considered new file", entry.isNewFile)

        // Provide before content (empty) explicitly
        val resolvedEntry = entry.copy(resolvedBefore = "")

        val latch = CountDownLatch(1)
        sm.rejectDiff(resolvedEntry) {
            latch.countDown()
        }
        waitForLatch(latch)

        vf.refresh(false, false)
        assertTrue("File should still exist", vf.exists())
        assertEquals("File should be empty", "", String(vf.contentsToByteArray()))
        println("Empty File Safety Verified: File restored to empty, not deleted.")
    }

    private fun waitForLatch(latch: CountDownLatch) {
        val start = System.currentTimeMillis()
        while (latch.count > 0) {
            if (System.currentTimeMillis() - start > 10000) {
                throw AssertionError("Timeout waiting for latch")
            }
            com.intellij.testFramework.PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(10)
        }
    }

    private fun runCommand(dir: File, vararg command: String): String {
        val cmd = com.intellij.execution.configurations.GeneralCommandLine(*command)
            .withWorkDirectory(dir)
        val handler = com.intellij.execution.process.CapturingProcessHandler(cmd)
        val output = handler.runProcess(10000)
        if (output.exitCode != 0) {
             throw RuntimeException("Command failed: ${command.joinToString(" ")}\nStderr: ${output.stderr}\nStdout: ${output.stdout}")
        }
        return output.stdout
    }
}
