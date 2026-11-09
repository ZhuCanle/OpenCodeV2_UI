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
}
