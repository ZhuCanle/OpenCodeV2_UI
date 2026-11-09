package com.zhucanle.opencodev2.ide.session

import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.zhucanle.opencodev2.ide.api.models.DiffEntry
import com.zhucanle.opencodev2.ide.api.models.FileDiff
import java.io.File
import java.nio.file.Files

class RejectDiffSafetyTest : BasePlatformTestCase() {
    private lateinit var manager: SessionManager
    private lateinit var directory: File

    override fun setUp() {
        super.setUp()
        manager = SessionManager(project)
        directory = Files.createTempDirectory("reject-safety").toFile().canonicalFile
        com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess.allowRootAccess(testRootDisposable, directory.path)
    }

    override fun tearDown() {
        try {
            Disposer.dispose(manager)
            directory.deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    private fun reject(entry: DiffEntry): Boolean {
        var result: Boolean? = null
        manager.rejectDiff(entry) { result = it }
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        return checkNotNull(result)
    }

    private fun createFile(name: String, content: String): VirtualFile {
        val file = File(directory, name).apply {
            parentFile.mkdirs()
            writeText(content)
        }
        return checkNotNull(LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file))
    }

    fun testRejectPreservesLaterSavedEdit() {
        val file = createFile("saved.txt", "later user edit")
        val entry = DiffEntry(file.path, FileDiff(file.path, "", 1, 1, status = "modified"),
            resolvedBefore = "original", resolvedAfter = "AI edit")
        assertFalse(reject(entry))
        assertEquals("later user edit", VfsUtil.loadText(file))
    }

    fun testRejectPreservesUnsavedEdit() {
        val file = createFile("unsaved.txt", "AI edit")
        val document = checkNotNull(FileDocumentManager.getInstance().getDocument(file))
        runWriteAction { document.setText("unsaved user edit") }
        val entry = DiffEntry(file.path, FileDiff(file.path, "", 1, 1, status = "modified"),
            resolvedBefore = "original", resolvedAfter = "AI edit")
        assertFalse(reject(entry))
        assertEquals("unsaved user edit", document.text)
        assertEquals("AI edit", VfsUtil.loadText(file))
    }

    fun testRejectDoesNotDeleteNewFileWithLaterEdits() {
        val file = createFile("new.txt", "AI edit plus user edit")
        val entry = DiffEntry(file.path, FileDiff(file.path, "", 1, 0, status = "added"),
            resolvedBefore = "", resolvedAfter = "AI edit", isCreatedExplicitly = true)
        assertFalse(reject(entry))
        assertTrue(file.isValid)
        assertEquals("AI edit plus user edit", VfsUtil.loadText(file))
    }

    fun testUnchangedFileCanStillBeRejected() {
        val file = createFile("unchanged.txt", "AI edit")
        val entry = DiffEntry(file.path, FileDiff(file.path, "", 1, 1, status = "modified"),
            resolvedBefore = "original", resolvedAfter = "AI edit")
        assertTrue(reject(entry))
        assertEquals("original", VfsUtil.loadText(file))
    }

    fun testOldDiffCannotDeleteSameContentInAnotherWorkingDirectory() {
        createFile("a/new.txt", "AI edit")
        val other = createFile("b/new.txt", "AI edit")
        manager.activeBasePath = File(directory, "a").path
        val snapshot = TurnSnapshot(1, setOf("new.txt"), setOf("new.txt"), setOf("new.txt"),
            emptySet(), null)
        val entry = manager.getProcessedDiffs(
            listOf(FileDiff("new.txt", "", 1, 0, "added")), snapshot
        ).single()
        manager.activeBasePath = File(directory, "b").path
        assertFalse(reject(entry))
        var accepted: Boolean? = null
        manager.acceptDiff(entry) { accepted = it }
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEquals(false, accepted)
        assertTrue(other.isValid)
        assertEquals("AI edit", VfsUtil.loadText(other))
    }

    fun testRejectRecreatesDeletedEmptyFile() {
        val path = File(directory, "empty.txt").path
        val entry = DiffEntry(path, FileDiff(path, "", 0, 0, "deleted"), resolvedBefore = "")
        assertTrue(reject(entry))
        assertTrue(File(path).isFile)
        assertEquals("", File(path).readText())
    }

    fun testOpenedCrLfFileCanBeRejectedWithoutEditingIt() {
        val file = createFile("windows.txt", "AI\r\nedit\r\n")
        val document = checkNotNull(FileDocumentManager.getInstance().getDocument(file))
        assertEquals("AI\nedit\n", document.text)
        val entry = DiffEntry(file.path, FileDiff(file.path, "", 1, 1, "modified"),
            resolvedBefore = "original\r\n", resolvedAfter = "AI\r\nedit\r\n")
        assertTrue(reject(entry))
        assertEquals("original\r\n", File(file.path).readText())
    }

    fun testRejectRefusesDeletedFileWithUnknownOriginalContent() {
        val path = File(directory, "unknown.txt").path
        val entry = DiffEntry(path, FileDiff(path, "", 0, 1, "deleted"))
        assertFalse(reject(entry))
        assertFalse(File(path).exists())
    }

    fun testAcceptRefusesAReplacedSnapshot() = checkAcceptRefusesLaterEdit(replaceSnapshot = true)

    fun testAcceptRefusesLaterDiskEditsEvenBeforeSnapshotReplacement() =
        checkAcceptRefusesLaterEdit(replaceSnapshot = false)

    private fun checkAcceptRefusesLaterEdit(replaceSnapshot: Boolean) {
        val init = ProcessBuilder("git", "init").directory(directory).redirectErrorStream(true).start()
        init.inputStream.readBytes()
        assertEquals(0, init.waitFor())
        manager.activeBasePath = directory.path
        val file = createFile("changed.txt", "first turn")
        val snapshot = TurnSnapshot(1, setOf("changed.txt"), setOf("changed.txt"), emptySet(),
            emptySet(), null, capturedBeforeContent = mapOf("changed.txt" to "original"))
        val diff = FileDiff("changed.txt", "", 1, 1, "modified")
        val old = manager.getProcessedDiffs(listOf(diff), snapshot).single()
        File(file.path).writeText("second turn")
        val latest = if (replaceSnapshot) manager.getProcessedDiffs(listOf(diff), snapshot).single() else old
        var accepted: Boolean? = null
        manager.acceptDiff(old) { accepted = it }
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
        while (accepted == null && System.nanoTime() < deadline) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(10)
        }
        assertEquals(false, accepted)
        assertSame(latest, manager.getDiffForFile("changed.txt"))
        val staged = ProcessBuilder("git", "diff", "--cached", "--name-only").directory(directory).start()
        assertEquals("", staged.inputStream.bufferedReader().readText())
        assertEquals(0, staged.waitFor())
    }
}
