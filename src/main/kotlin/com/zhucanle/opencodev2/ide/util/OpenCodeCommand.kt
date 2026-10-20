package com.zhucanle.opencodev2.ide.util

/**
 * Builds opencode CLI (v2) command lines.
 *
 * In v2 the CLI has no top-level `--hostname`/`--port` flags: running the binary launches the
 * TUI, which attaches to the background service itself. The most recent session is resumed with
 * `--continue`.
 */
object OpenCodeCommand {

    /** An absolute Windows path, e.g. `C:\Users\me\opencode.exe`. */
    private val WINDOWS_ABSOLUTE_PATH = Regex("^[A-Za-z]:\\\\")

    /**
     * Builds the command line that launches the opencode TUI.
     *
     * The command line is typed into the terminal shell the user configured, so a Windows
     * executable path is written with forward slashes (`C:/Users/.../opencode.exe`). Backslashes
     * are escape characters in POSIX shells such as Git Bash and Cygwin, which turned
     * `C:\Users\...` into `C:Users...` and made the TUI fail to start; cmd.exe and PowerShell
     * accept forward slashes just as well.
     *
     * @param binary absolute path or bare name of the opencode executable.
     * @param cont when true, resume the most recent session via `--continue`.
     */
    fun tui(binary: String, cont: Boolean): String {
        val path = toShellPath(binary)
        val executable = if (path.contains(' ')) "\"$path\"" else path
        return if (cont) "$executable --continue" else executable
    }

    /** Windows paths keep their drive letter but use forward slashes; anything else is unchanged. */
    private fun toShellPath(binary: String): String {
        return if (WINDOWS_ABSOLUTE_PATH.containsMatchIn(binary)) binary.replace('\\', '/') else binary
    }
}
