package com.zhucanle.opencodev2.ide.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scope rules for VFS changes.
 *
 * The plugin receives every VFS event of the whole IDE, and a `git pull`/`git merge` rewrites the
 * working tree exactly like an agent edit does. Both must stay out of the diff pipeline, which is
 * what [ChangeScope] decides.
 */
class ChangeScopeTest {

    private val root = "/repo/project"
    private val other = "/repo/other"
    private var clock = 1_000_000L
    private val scope = ChangeScope(roots = { listOf(root) }, now = { clock })

    @Test
    fun fileInsideTheProjectIsTracked() {
        assertEquals(ChangeScope.Verdict.TRACK, scope.classify("$root/src/Main.kt"))
    }

    @Test
    fun fileInAnotherProjectIsIgnored() {
        assertEquals(ChangeScope.Verdict.IGNORE_OUTSIDE, scope.classify("$other/src/Main.kt"))
    }

    @Test
    fun aFileNamedGitignoreIsNotMetadata() {
        assertEquals(ChangeScope.Verdict.TRACK, scope.classify("$root/.gitignore"))
    }

    /**
     * `git add` (our own Accept action) and `git status` write `.git/index`. Treating that as a git
     * operation would swallow the next agent edits, so it must not open the suppression window.
     */
    @Test
    fun indexWriteAloneDoesNotSuppressLaterEdits() {
        assertEquals(ChangeScope.Verdict.IGNORE_METADATA, scope.classify("$root/.git/index"))
        assertEquals(ChangeScope.Verdict.TRACK, scope.classify("$root/src/Main.kt"))
    }

    /** A commit writes the reflog; the working tree is untouched, so edits must stay tracked. */
    @Test
    fun commitReflogDoesNotSuppressLaterEdits() {
        assertEquals(ChangeScope.Verdict.IGNORE_METADATA, scope.classify("$root/.git/logs/HEAD"))
        assertEquals(ChangeScope.Verdict.TRACK, scope.classify("$root/src/Main.kt"))
    }

    @Test
    fun fetchHeadOpensTheGitOperationWindow() {
        assertEquals(ChangeScope.Verdict.IGNORE_METADATA, scope.classify("$root/.git/FETCH_HEAD"))

        assertEquals(ChangeScope.Verdict.IGNORE_GIT_OPERATION, scope.classify("$root/src/Pulled.kt"))
        assertTrue(scope.gitTouchedPaths().contains("$root/src/Pulled.kt"))
    }

    @Test
    fun rebaseMetadataOpensTheGitOperationWindow() {
        scope.classify("$root/.git/rebase-merge/git-rebase-todo")
        assertEquals(ChangeScope.Verdict.IGNORE_GIT_OPERATION, scope.classify("$root/src/Rebased.kt"))
    }

    @Test
    fun theWindowExpires() {
        scope.classify("$root/.git/ORIG_HEAD")
        clock += ChangeScope.GIT_OPERATION_WINDOW_MS + 1
        assertEquals(ChangeScope.Verdict.TRACK, scope.classify("$root/src/Main.kt"))
    }

    /**
     * After a pull the agent may edit the pulled file again. That later edit is a real agent edit,
     * so the file must leave the git-touched set and be eligible for a diff.
     */
    @Test
    fun aLaterTrackedEditClearsTheGitMark() {
        scope.classify("$root/.git/ORIG_HEAD")
        scope.classify("$root/src/Pulled.kt")
        clock += ChangeScope.GIT_OPERATION_WINDOW_MS + 1

        assertEquals(ChangeScope.Verdict.TRACK, scope.classify("$root/src/Pulled.kt"))
        assertFalse(scope.gitTouchedPaths().contains("$root/src/Pulled.kt"))
    }

    /** Custom base path sessions work outside `project.basePath`, so every root counts. */
    @Test
    fun everyConfiguredRootIsAccepted() {
        val multi = ChangeScope(roots = { listOf(root, "/repo/custom") }, now = { clock })

        assertEquals(ChangeScope.Verdict.TRACK, multi.classify("$root/src/Main.kt"))
        assertEquals(ChangeScope.Verdict.TRACK, multi.classify("/repo/custom/src/Main.kt"))
        assertEquals(ChangeScope.Verdict.IGNORE_OUTSIDE, multi.classify("$other/src/Main.kt"))
    }

    @Test
    fun ownedRejectsGitMetadataAndForeignFiles() {
        assertTrue(scope.isOwned("$root/src/Main.kt"))
        assertFalse(scope.isOwned("$root/.git/index"))
        assertFalse(scope.isOwned("$other/src/Main.kt"))
    }
}
