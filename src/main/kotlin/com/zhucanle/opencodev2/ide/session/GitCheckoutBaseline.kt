package com.zhucanle.opencodev2.ide.session

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.text.StringUtil
import com.intellij.util.concurrency.AppExecutorUtil
import com.zhucanle.opencodev2.ide.util.PathUtil

import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/** Git identity at turn start. Worktrees have their own git directory and HEAD reflog. */
data class GitCheckoutBaseline(
    val root: String,
    val gitDirectory: String,
    val revision: String,
    val reflogOffset: Long
) {
    internal data class Checkout(val revision: String, val offset: Long)
    internal data class TreeFile(val exists: Boolean, val content: String)

    /** Reads only records appended during this turn, distinguishing checkout/switch from commit. */
    internal fun latestCheckout(): Checkout? = try {
        val log = File(gitDirectory, "logs/HEAD")
        if (!log.isFile || log.length() <= reflogOffset) {
            null
        } else {
            RandomAccessFile(log, "r").use { input ->
                input.seek(reflogOffset)
                var checkout: Checkout? = null
                while (true) {
                    val line = input.readLine() ?: break
                    if (!line.substringAfter('\t', "").startsWith("checkout: moving from ")) continue
                    val target = line.substringBefore('\t').split(' ').getOrNull(1) ?: continue
                    if (target.matches(Regex("[0-9a-f]{40,64}"))) {
                        checkout = Checkout(target, input.filePointer)
                    }
                }
                checkout
            }
        }
    } catch (e: Exception) {
        logger.warn("[Git] Could not read checkout history for $root", e)
        null
    }

    /** Null means unreadable or non-text; a missing tree entry is a known absent file. */
    internal fun readFile(commit: String, absolutePath: String, filtered: Boolean = true): TreeFile? {
        if (!PathUtil.isUnder(root, absolutePath)) return null
        val path = PathUtil.relativizeToProject(root, absolutePath)
        val listing = git(root, "ls-tree", "-z", commit, "--", path) ?: return null
        if (listing.isEmpty()) return TreeFile(false, "")
        val header = listing.substringBefore('\t').split(' ')
        if (header.size != 3 || header[0] !in setOf("100644", "100755") || header[1] != "blob") return null
        val content = if (filtered) {
            // The old tree must use its own attributes, not the newly checked-out branch's rules.
            git(root, "--attr-source=$commit", "cat-file", "--filters", "--path=$path", header[2])
                ?: if (attributesMatchWorkingTree(commit, absolutePath)) {
                    // Older Git versions lack --attr-source. Current attributes are safe only
                    // when every applicable repository attributes file matches this tree.
                    git(root, "cat-file", "--filters", "--path=$path", header[2])
                } else null
        } else {
            git(root, "cat-file", "blob", header[2])
        } ?: return null
        if ('\u0000' in content) return null
        return TreeFile(true, content)
    }

    private fun attributesMatchWorkingTree(commit: String, absolutePath: String): Boolean {
        var directory: File? = File(absolutePath).parentFile
        while (directory != null && PathUtil.isUnder(root, directory.path)) {
            val attributes = File(directory, ".gitattributes")
            val original = readFile(commit, attributes.path, filtered = false) ?: return false
            if (original.exists != attributes.isFile) return false
            if (attributes.isFile && StringUtil.convertLineSeparators(attributes.readText()) !=
                StringUtil.convertLineSeparators(original.content)) return false
            directory = directory.parentFile
        }
        return true
    }

    companion object {
        private val logger = Logger.getInstance(GitCheckoutBaseline::class.java)

        /** Called on the SSE/connection worker before the turn starts capturing file changes. */
        fun capture(directory: String?): GitCheckoutBaseline? {
            if (directory == null) return null
            var candidate: File? = File(directory).absoluteFile
            while (candidate != null && !File(candidate, ".git").exists()) candidate = candidate.parentFile
            if (candidate == null) return null
            val result = git(directory, "rev-parse", "--show-toplevel", "--absolute-git-dir", "HEAD")
                ?.trimEnd()?.lines() ?: return null
            if (result.size != 3 || !result[2].matches(Regex("[0-9a-f]{40,64}"))) return null
            return GitCheckoutBaseline(
                PathUtil.toSystemIndependentPath(result[0]),
                PathUtil.toSystemIndependentPath(result[1]),
                result[2],
                File(result[1], "logs/HEAD").length()
            )
        }

        private fun git(directory: String, vararg arguments: String): String? = try {
            // Text process handlers normalize CRLF; tree content must retain exact checkout bytes.
            val builder = ProcessBuilder(listOf("git", "--literal-pathspecs") + arguments)
                .directory(File(directory)).redirectError(ProcessBuilder.Redirect.DISCARD)
            builder.environment()["GIT_OPTIONAL_LOCKS"] = "0"
            val process = builder.start()
            process.outputStream.close()
            val output = AppExecutorUtil.getAppExecutorService().submit<ByteArray> {
                process.inputStream.use { it.readBytes() }
            }
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0) null
                else output.get(1, TimeUnit.SECONDS).toString(Charsets.UTF_8)
            } finally {
                if (process.isAlive) process.destroyForcibly()
                output.cancel(true)
            }
        } catch (e: Exception) {
            if (e is InterruptedException) Thread.currentThread().interrupt()
            logger.debug("[Git] Could not inspect repository $directory", e)
            null
        }
    }
}
