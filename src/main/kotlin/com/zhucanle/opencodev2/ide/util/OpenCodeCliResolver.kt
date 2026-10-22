package com.zhucanle.opencodev2.ide.util

/**
 * Resolves the opencode CLI entry point that Java can actually start on Windows.
 *
 * `where opencode` lists every PATH match, including the extensionless POSIX launcher that npm and
 * Scoop install next to the Windows wrappers. Java starts a process through `CreateProcess`, which
 * only appends `.exe` to a bare name, so running `opencode` neither starts that shim nor finds
 * `opencode.cmd`; it fails with `CreateProcess error=2, The system cannot find the file specified`.
 * Only `.exe`, `.com`, `.cmd` and `.bat` files are runnable, so the resolver keeps the first of
 * those in `where` order (which follows PATH order) and drops everything else.
 */
object OpenCodeCliResolver {

    /** Extensions Windows can start directly; other PATHEXT entries are scripts Java cannot run. */
    private val RUNNABLE_EXTENSIONS = listOf(".exe", ".com", ".cmd", ".bat")

    /**
     * Returns the first Windows-runnable path in [whereOutput], or null when there is none.
     * Each line is one `where` match; blank lines and CRLF are tolerated.
     */
    fun selectWindowsExecutable(whereOutput: String): String? =
        whereOutput.lineSequence()
            .map { it.trim() }
            .firstOrNull { path -> path.isNotEmpty() && RUNNABLE_EXTENSIONS.any { path.endsWith(it, ignoreCase = true) } }
}
