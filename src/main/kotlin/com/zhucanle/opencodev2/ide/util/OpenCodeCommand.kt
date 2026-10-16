package com.zhucanle.opencodev2.ide.util

/**
 * Builds opencode CLI (v2) command lines.
 *
 * In v2 the CLI has no top-level `--hostname`/`--port` flags: running the binary launches the
 * TUI, which attaches to the background service itself. The most recent session is resumed with
 * `--continue`.
 */
object OpenCodeCommand {

    /**
     * Builds the command line that launches the opencode TUI.
     *
     * @param binary absolute path or bare name of the opencode executable.
     * @param cont when true, resume the most recent session via `--continue`.
     */
    fun tui(binary: String, cont: Boolean): String {
        val executable = if (binary.contains(' ')) "\"$binary\"" else binary
        return if (cont) "$executable --continue" else executable
    }
}
