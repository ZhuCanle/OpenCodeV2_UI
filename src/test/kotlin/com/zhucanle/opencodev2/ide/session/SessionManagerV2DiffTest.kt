package com.zhucanle.opencodev2.ide.session

import com.zhucanle.opencodev2.ide.api.models.FileDiff
import com.intellij.history.Label
import com.intellij.openapi.project.Project
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * v2 diff pipeline tests.
 *
 * The v2 server only ships a unified-diff `patch` plus a `status`; there is no `before`/`after`
 * content on the wire. The "before" side must therefore be reconstructed from local snapshots
 * (captured content / LocalHistory / known state) and the "after" side from the file on disk.
 *
 * Uses the same mock-Project + SessionManager-subclass technique as `OpenCodeLogicTest`.
 */
class SessionManagerV2DiffTest {

    private lateinit var projectDir: File
    private lateinit var sm: V2TestSessionManager

    @Before
    fun setUp() {
        projectDir = Files.createTempDirectory("session-manager-v2-diff").toFile()
        val mockProject = Proxy.newProxyInstance(
            Project::class.java.classLoader,
            arrayOf(Project::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getBasePath" -> projectDir.absolutePath
                "isDisposed" -> false
                "toString" -> "MockProject"
                "hashCode" -> 12345
                "equals" -> false
                else -> null
            }
        } as Project
        sm = V2TestSessionManager(mockProject)
    }

    @After
    fun tearDown() {
        projectDir.deleteRecursively()
    }

    private fun writeDisk(relativePath: String, content: String) {
        val file = File(projectDir, relativePath)
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    private fun snapshotWith(
        capturedBefore: Map<String, String> = emptyMap(),
        knownStates: Map<String, String> = emptyMap(),
        aiCreated: Set<String> = emptySet(),
        serverEdited: Set<String> = emptySet(),
        vfsChanged: Set<String> = emptySet()
    ): TurnSnapshot = TurnSnapshot(
        turnNumber = 1,
        vfsChangedFiles = vfsChanged + capturedBefore.keys + serverEdited + aiCreated,
        serverEditedFiles = serverEdited,
        aiCreatedFiles = aiCreated,
        userEditedFiles = emptySet(),
        baselineLabel = null,
        knownFileStates = knownStates,
        capturedBeforeContent = capturedBefore
    )

    /**
     * A `status = "added"` file must always report an empty "before", even when a snapshot happens
     * to hold content for that path. Reading the newly written on-disk content as "before" would
     * collapse the diff into a no-op.
     */
    @Test
    fun newFileStatusForcesEmptyBefore() {
        writeDisk("new.kt", "fun new() {}")
        val d = FileDiff("new.kt", "@@ -0,0 +1 @@", 1, 0, status = "added")

        val entry = sm.getProcessedDiffs(
            listOf(d),
            snapshotWith(capturedBefore = mapOf("new.kt" to "SHOULD_NOT_BE_USED"))
        ).first { it.file == "new.kt" }

        assertEquals("", entry.beforeContent)
    }

    /**
     * A modified file resolves "before" from the snapshot and "after" from the current disk content,
     * because the wire no longer carries either side.
     */
    @Test
    fun modifiedFileUsesCapturedBeforeAndDiskAfter() {
        writeDisk("a.kt", "new")
        val d = FileDiff("a.kt", "@@ -1 +1 @@", 1, 1, status = "modified")

        val entry = sm.getProcessedDiffs(
            listOf(d),
            snapshotWith(capturedBefore = mapOf("a.kt" to "old"))
        ).first { it.file == "a.kt" }

        assertEquals("old", entry.beforeContent)
        assertEquals("new", entry.resolvedAfter)
    }

    /**
     * A deleted file keeps its snapshot "before" and reports an empty "after" (the file is gone).
     */
    @Test
    fun deletedFileUsesCapturedBeforeAndEmptyAfter() {
        val d = FileDiff("gone.kt", "@@ -1,3 +0,0 @@", 0, 3, status = "deleted")

        val entry = sm.getProcessedDiffs(
            listOf(d),
            snapshotWith(capturedBefore = mapOf("gone.kt" to "old"))
        ).first { it.file == "gone.kt" }

        assertEquals("old", entry.beforeContent)
        assertEquals("", entry.resolvedAfter)
    }

    /**
     * A VFS event outside the project (another IDE window edited a file) must never reach the diff
     * pipeline: the window would otherwise show that other project's file as an agent diff.
     */
    @Test
    fun fileOutsideTheProjectIsNeverShown() {
        val otherProject = Files.createTempDirectory("other-project").toFile()
        try {
            val foreign = File(otherProject, "main.kt")
            foreign.writeText("foreign content")
            val d = FileDiff(foreign.absolutePath, "", 1, 1, status = "modified")

            val entries = sm.getProcessedDiffs(
                listOf(d),
                snapshotWith(vfsChanged = setOf(foreign.absolutePath))
            )

            assertTrue("foreign file leaked into the diff list: ${entries.map { it.file }}", entries.isEmpty())
        } finally {
            otherProject.deleteRecursively()
        }
    }

    /**
     * A `git pull` / `git merge` rewrites the working tree, but those files are not agent edits.
     * The server may still report them as session diffs, so the pipeline has to drop them.
     */
    @Test
    fun gitOperationFilesAreNotShown() {
        writeDisk("pulled.kt", "pulled content")
        sm.changeScope.classify(File(projectDir, ".git/FETCH_HEAD").path)
        assertEquals(
            ChangeScope.Verdict.IGNORE_GIT_OPERATION,
            sm.changeScope.classify(File(projectDir, "pulled.kt").path)
        )

        val d = FileDiff("pulled.kt", "", 2, 2, status = "modified")
        val entries = sm.getProcessedDiffs(
            listOf(d),
            snapshotWith(capturedBefore = mapOf("pulled.kt" to "old content"))
        )

        assertTrue("git operation diff leaked: ${entries.map { it.file }}", entries.isEmpty())
    }

    /** Binary files cannot be rendered as text diffs (a PNG reads back as garbage). */
    @Test
    fun binaryFilesAreNotShown() {
        writeDisk("logo.png", "\u0000\u0001binary")
        val d = FileDiff("logo.png", "", 1, 1, status = "modified")

        val entries = sm.getProcessedDiffs(
            listOf(d),
            snapshotWith(capturedBefore = mapOf("logo.png" to "\u0000old binary"))
        )

        assertTrue("binary diff leaked: ${entries.map { it.file }}", entries.isEmpty())
    }
}

/** Test double: keeps LocalHistory out of the picture (no IDE application in unit tests). */
private class V2TestSessionManager(project: Project) : SessionManager(project) {
    override fun createSystemLabel(name: String): Label? = null
}
