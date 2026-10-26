package com.zhucanle.opencodev2.ide.session

import com.intellij.openapi.diagnostic.Logger
import com.zhucanle.opencodev2.ide.util.PathUtil

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Decides which file changes a turn may claim.
 *
 * Two kinds of changes reach the plugin but are not agent edits:
 * 1. Changes outside the project (another IDE window, IDE caches, another checkout).
 * 2. Changes a git operation rewrote in the working tree (`git pull`, `git merge`, rebases, stash).
 *
 * The first kind is filtered by the project roots. The second kind cannot be told apart from an
 * agent edit by content, so git metadata writes are used as the signal: `FETCH_HEAD`, `ORIG_HEAD`,
 * merge/rebase markers open a short window, and every working-tree file that changes inside that
 * window is remembered as "git touched" and kept out of the diff pipeline.
 *
 * `.git/index` and `.git/logs/HEAD` are deliberately *not* markers: `git add` (our own Accept
 * action), `git status` and `git commit` write them without touching the working tree, and
 * treating them as operations would swallow the agent's next edits.
 */
internal class ChangeScope(
    private val roots: () -> List<String>,
    private val now: () -> Long = System::currentTimeMillis
) {

    private val logger = Logger.getInstance(ChangeScope::class.java)

    enum class Verdict {
        /** A real file edit inside the project. */
        TRACK,

        /** Outside every tracked root: another project's file or IDE-internal data. */
        IGNORE_OUTSIDE,

        /** Git metadata: never a diff, but it may open the git-operation window. */
        IGNORE_METADATA,

        /** A git operation rewrote this file; it is not an agent edit. */
        IGNORE_GIT_OPERATION
    }

    /** Working-tree files a git operation rewrote, with the moment they were seen. */
    private val gitTouchedAt = ConcurrentHashMap<String, Long>()

    /** While [now] is below this value, working-tree changes belong to a git operation. */
    @Volatile
    private var gitOperationUntil = 0L

    /**
     * Classifies one VFS change. Git metadata events are consumed here because they only carry the
     * operation signal, never content.
     */
    fun classify(absolutePath: String): Verdict {
        val path = PathUtil.toSystemIndependentPath(absolutePath)
        val gitRelative = gitMetadataRelativePath(path)
        if (gitRelative != null) {
            noteGitMetadata(gitRelative)
            return Verdict.IGNORE_METADATA
        }
        if (!isOwned(path)) return Verdict.IGNORE_OUTSIDE
        if (now() < gitOperationUntil) {
            gitTouchedAt[path] = now()
            return Verdict.IGNORE_GIT_OPERATION
        }
        // A later edit of the same file is a real agent edit again, so the git mark must not
        // outlive it.
        gitTouchedAt.remove(path)
        return Verdict.TRACK
    }

    /** True when [path] belongs to this project (inside a root and not git metadata). */
    fun isOwned(path: String): Boolean {
        val normalized = PathUtil.toSystemIndependentPath(path)
        if (gitMetadataRelativePath(normalized) != null) return false
        return roots().any { root -> !root.isBlank() && PathUtil.isUnder(root, normalized) }
    }

    /** Files rewritten by a git operation recently; they must never surface as diffs. */
    fun gitTouchedPaths(): Set<String> {
        val cutoff = now() - GIT_TOUCH_MEMORY_MS
        gitTouchedAt.entries.removeIf { it.value < cutoff }
        return gitTouchedAt.keys.toSet()
    }

    fun clear() {
        gitTouchedAt.clear()
        gitOperationUntil = 0L
    }

    /**
     * Path relative to the deepest `.git` segment, or null when [path] is not git metadata.
     * Matching the segment (and not just any substring) keeps `.gitignore` a normal project file.
     */
    private fun gitMetadataRelativePath(path: String): String? = when {
        path == ".git" || path.endsWith("/.git") -> ""
        else -> {
            val marker = "/.git/"
            val index = path.indexOf(marker)
            if (index >= 0) path.substring(index + marker.length) else null
        }
    }

    private fun noteGitMetadata(relativePath: String) {
        val marker = when {
            relativePath in GIT_OPERATION_MARKERS -> relativePath
            relativePath.startsWith("rebase-merge/") -> "rebase-merge"
            relativePath.startsWith("rebase-apply/") -> "rebase-apply"
            // The index is written throughout a long merge/checkout: extend an open window, but
            // never open one on its own (git add / git status write it too).
            relativePath == "index" && now() < gitOperationUntil -> "index"
            else -> return
        }
        gitOperationUntil = now() + GIT_OPERATION_WINDOW_MS
        logger.info("[Git] Operation detected ($marker); working-tree changes are ignored for ${GIT_OPERATION_WINDOW_MS}ms")
    }

    companion object {
        /** How long working-tree writes are attributed to the git operation that just started. */
        internal val GIT_OPERATION_WINDOW_MS = TimeUnit.SECONDS.toMillis(15)

        /** How long a git-touched file stays out of the diff pipeline. */
        internal val GIT_TOUCH_MEMORY_MS = TimeUnit.MINUTES.toMillis(10)

        /** Git metadata files only written by working-tree-changing operations. */
        internal val GIT_OPERATION_MARKERS = setOf(
            "FETCH_HEAD",
            "ORIG_HEAD",
            "MERGE_HEAD",
            "MERGE_MSG",
            "MERGE_RR",
            "AUTO_MERGE",
            "SQUASH_MSG",
            "CHERRY_PICK_HEAD",
            "REVERT_HEAD",
            "REBASE_HEAD",
            "refs/stash",
            "logs/refs/stash"
        )
    }
}
