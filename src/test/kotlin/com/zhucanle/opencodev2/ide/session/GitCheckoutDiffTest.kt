package com.zhucanle.opencodev2.ide.session

import com.intellij.history.Label
import com.intellij.openapi.project.Project
import com.zhucanle.opencodev2.ide.api.models.FileDiff
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files

/** Real checkout/switch operations without metadata VFS callbacks: refresh ordering is not evidence. */
class GitCheckoutDiffTest {
    private lateinit var root: File
    private lateinit var manager: SessionManager

    @Before
    fun setUp() {
        root = Files.createTempDirectory("checkout-diff").toFile().canonicalFile
        git("init", "-b", "base")
        git("config", "user.name", "Test User")
        git("config", "user.email", "test@example.com")
        git("config", "core.autocrlf", "false")
        File(root, "changed.txt").writeText("base content\n")
        File(root, "base-only.txt").writeText("base file\n")
        File(root, "common.txt").writeText("shared content\n")
        git("add", ".")
        git("commit", "-m", "Base")
        git("switch", "-c", "target")
        File(root, "changed.txt").writeText("target content\n")
        File(root, "target-only.txt").writeText("target file\n")
        File(root, "base-only.txt").delete()
        git("add", ".")
        git("commit", "-m", "Target")
        git("switch", "base")
        manager = createManager(root)
    }

    @After
    fun tearDown() {
        manager.dispose()
        root.deleteRecursively()
    }

    private fun createManager(directory: File): SessionManager {
        val project = Proxy.newProxyInstance(Project::class.java.classLoader, arrayOf(Project::class.java)) { _, m, _ ->
            when (m.name) {
                "getBasePath" -> directory.path
                "isDisposed" -> false
                "hashCode" -> 1
                "equals" -> false
                "toString" -> "CheckoutProject"
                else -> null
            }
        } as Project
        return object : SessionManager(project) {
            override fun createSystemLabel(name: String): Label? = null
        }
    }

    private fun git(vararg args: String, directory: File = root): String {
        val process = ProcessBuilder(listOf("git") + args).directory(directory).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(output, 0, process.waitFor())
        return output.trim()
    }

    private fun finish(before: String = "base content\n"): TurnSnapshot {
        manager.onFileEdited("session", "changed.txt")
        return checkNotNull(manager.onTurnEnd("session")).copy(
            capturedBeforeContent = mapOf("changed.txt" to before)
        )
    }

    private val changed = FileDiff("changed.txt", "", 1, 1, "modified")

    @Test
    fun checkoutOnlyChangesAreNotAgentDiffsEvenWhenClaimedByServer() {
        manager.onTurnStart("session")
        git("switch", "target")
        assertTrue(manager.getProcessedDiffs(listOf(changed), finish()).isEmpty())
    }

    @Test
    fun immediateEditAfterSwitchUsesTargetBranchAsBefore() {
        manager.onTurnStart("session")
        git("switch", "target")
        File(root, "changed.txt").appendText("AI edit\n")
        val entry = manager.getProcessedDiffs(listOf(changed), finish()).single()
        assertEquals("target content\n", entry.resolvedBefore)
        assertEquals("target content\nAI edit\n", entry.resolvedAfter)
    }

    @Test
    fun checkoutThenCommitStillUsesCheckoutTargetNotTheNewCommit() {
        manager.onTurnStart("session")
        git("switch", "target")
        File(root, "changed.txt").appendText("AI edit\n")
        git("add", ".")
        git("commit", "-m", "AI edit")
        val entry = manager.getProcessedDiffs(listOf(changed), finish()).single()
        assertEquals("target content\n", entry.resolvedBefore)
        assertEquals("target content\nAI edit\n", entry.resolvedAfter)
    }

    @Test
    fun ordinaryCommitDoesNotSuppressTheTurnDiff() {
        manager.onTurnStart("session")
        File(root, "changed.txt").appendText("AI edit\n")
        git("add", ".")
        git("commit", "-m", "AI edit")
        val entry = manager.getProcessedDiffs(listOf(changed), finish()).single()
        assertEquals("base content\n", entry.resolvedBefore)
    }

    @Test
    fun fileAddedByCheckoutIsNotTreatedAsAnAgentCreatedFile() {
        manager.onTurnStart("session")
        git("switch", "target")
        manager.onFileEdited("session", "target-only.txt")
        val snapshot = checkNotNull(manager.onTurnEnd("session"))
            .copy(aiCreatedFiles = setOf("target-only.txt"))
        val diff = FileDiff("target-only.txt", "", 1, 0, "added")
        assertTrue(manager.getProcessedDiffs(listOf(diff), snapshot).isEmpty())
    }

    @Test
    fun checkoutBeforeTurnDoesNotHideRealEdits() {
        git("switch", "target")
        manager.onTurnStart("session")
        File(root, "changed.txt").appendText("AI edit\n")
        val entry = manager.getProcessedDiffs(listOf(changed), finish("target content\n")).single()
        assertEquals("target content\n", entry.resolvedBefore)
    }

    @Test
    fun checkoutDeletionIsNotAnAgentDeletion() {
        manager.onTurnStart("session")
        git("switch", "target")
        manager.onFileEdited("session", "base-only.txt")
        val snapshot = checkNotNull(manager.onTurnEnd("session"))
            .copy(capturedBeforeContent = mapOf("base-only.txt" to "base file\n"))
        assertTrue(manager.getProcessedDiffs(listOf(FileDiff("base-only.txt", "", 0, 1, "deleted")), snapshot).isEmpty())
    }

    @Test
    fun editsToFileIntroducedByCheckoutRestoreTargetContentInsteadOfDeletingFile() {
        manager.onTurnStart("session")
        git("switch", "target")
        File(root, "target-only.txt").appendText("AI edit\n")
        manager.onFileEdited("session", "target-only.txt")
        val snapshot = checkNotNull(manager.onTurnEnd("session")).copy(aiCreatedFiles = setOf("target-only.txt"))
        val entry = manager.getProcessedDiffs(listOf(FileDiff("target-only.txt", "", 2, 0, "added")), snapshot).single()
        assertEquals("target file\n", entry.resolvedBefore)
        assertEquals("modified", entry.diff.status)
        assertTrue(!entry.isNewFile)
    }

    @Test
    fun carriedLocalChangesOnUnchangedBranchFileRemainTheBeforeBaseline() {
        File(root, "common.txt").writeText("user content\n")
        manager.onTurnStart("session")
        git("switch", "target")
        File(root, "common.txt").appendText("AI edit\n")
        manager.onFileEdited("session", "common.txt")
        val snapshot = checkNotNull(manager.onTurnEnd("session"))
            .copy(capturedBeforeContent = mapOf("common.txt" to "user content\n"))
        val entry = manager.getProcessedDiffs(listOf(FileDiff("common.txt", "", 1, 0, "modified")), snapshot).single()
        assertEquals("user content\n", entry.resolvedBefore)
        assertEquals("user content\nAI edit\n", entry.resolvedAfter)
    }

    @Test
    fun linkedWorktreeCheckoutUsesItsOwnHeadReflog() {
        val worktree = File(root, "linked")
        git("worktree", "add", "--detach", worktree.path, "base")
        manager.dispose()
        manager = createManager(worktree)
        manager.onTurnStart("session")
        git("switch", "target", directory = worktree)
        File(worktree, "changed.txt").appendText("AI edit\n")
        val entry = manager.getProcessedDiffs(listOf(changed), finish()).single()
        assertEquals("target content\n", entry.resolvedBefore)
    }

    @Test
    fun checkoutWithCrLfConversionDoesNotBecomeAnEdit() {
        git("config", "core.autocrlf", "true")
        manager.onTurnStart("session")
        git("switch", "target")
        assertEquals("target content\r\n", File(root, "changed.txt").readText())
        val baseline = checkNotNull(GitCheckoutBaseline.capture(root.path))
        assertEquals("Git's filtered bytes must retain CRLF", "target content\r\n",
            baseline.readFile(baseline.revision, File(root, "changed.txt").path)?.content)
        assertTrue(manager.getProcessedDiffs(listOf(changed), finish()).isEmpty())
    }

    @Test
    fun repeatedCheckoutsUseTheLastTargetTree() {
        manager.onTurnStart("session")
        git("switch", "target")
        git("switch", "base")
        File(root, "changed.txt").appendText("AI edit\n")
        val entry = manager.getProcessedDiffs(listOf(changed), finish()).single()
        assertEquals("base content\n", entry.resolvedBefore)
        assertEquals("base content\nAI edit\n", entry.resolvedAfter)
    }

    @Test
    fun checkoutRoundTripDoesNotTurnRestoredTrackedFileIntoANewFile() {
        manager.onTurnStart("session")
        git("switch", "target")
        git("switch", "base")
        File(root, "base-only.txt").appendText("AI edit\n")
        manager.onFileEdited("session", "base-only.txt")
        val snapshot = checkNotNull(manager.onTurnEnd("session")).copy(
            aiCreatedFiles = setOf("base-only.txt"),
            capturedBeforeContent = mapOf("base-only.txt" to "base file\n")
        )
        val entry = manager.getProcessedDiffs(listOf(FileDiff("base-only.txt", "", 2, 0, "added")), snapshot).single()
        assertEquals("modified", entry.diff.status)
        assertTrue(!entry.isNewFile)
        assertEquals("base file\n", entry.resolvedBefore)
    }

    @Test
    fun branchSpecificAttributesDoNotMisclassifyTheOriginalCleanFile() {
        // Git executes the configured filter through its shell, also on Git for Windows.
        git("config", "filter.upper.smudge", "tr '[:lower:]' '[:upper:]'")
        git("config", "filter.upper.clean", "cat")
        git("switch", "target")
        File(root, ".gitattributes").writeText("changed.txt filter=upper\n")
        git("add", ".gitattributes")
        git("commit", "-m", "Target attributes")
        git("switch", "base")
        manager.onTurnStart("session")
        git("switch", "target")
        assertEquals("TARGET CONTENT\n", File(root, "changed.txt").readText())
        File(root, "changed.txt").appendText("AI edit\n")
        val entry = manager.getProcessedDiffs(listOf(changed), finish()).single()
        assertEquals("TARGET CONTENT\n", entry.resolvedBefore)
        assertEquals("TARGET CONTENT\nAI edit\n", entry.resolvedAfter)
    }
}
