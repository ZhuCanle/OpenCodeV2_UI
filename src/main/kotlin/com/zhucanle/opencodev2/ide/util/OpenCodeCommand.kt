package com.zhucanle.opencodev2.ide.util

/**
 * Builds opencode CLI (v2) command lines.
 *
 * In v2 the CLI has no top-level `--hostname`/`--port` flags: running the binary launches the
 * TUI, which attaches to the background service itself. The most recent session is resumed with
 * `--continue`.
 */
object OpenCodeCommand {

    /** The shell family a terminal runs; it decides how an executable path has to be written. */
    enum class Shell { CMD, POWERSHELL, POSIX, WSL, UNKNOWN }

    /** An absolute Windows path, e.g. `C:\Users\me\opencode.exe`. */
    private val WINDOWS_ABSOLUTE_PATH = Regex("^[A-Za-z]:\\\\")

    /**
     * Builds the command line that launches the opencode TUI.
     *
     * The command line is typed into the terminal shell the user configured, so the executable path
     * is written in a form that shell can run:
     * - Windows paths keep their drive letter but use forward slashes (`C:/Users/.../opencode.exe`).
     *   Backslashes are escape characters in POSIX shells such as Git Bash and Cygwin, which turned
     *   `C:\Users\...` into `C:Users...` and made the TUI fail to start; cmd.exe and PowerShell
     *   accept forward slashes just as well.
     * - WSL shells see the Windows drives under `/mnt/<letter>`, so the same path becomes
     *   `/mnt/c/Users/.../opencode.exe` (a native `.exe` still runs there through interop).
     * - PowerShell only runs a quoted path through the call operator, so a path with spaces gets the
     *   `& "..."` form instead of a bare `"..."`.
     *
     * @param binary absolute path or bare name of the opencode executable.
     * @param cont when true, resume the most recent session via `--continue`.
     * @param shell shell family of the terminal the command is typed into.
     */
    fun tui(binary: String, cont: Boolean, shell: Shell = Shell.UNKNOWN): String {
        val path = executablePath(binary, shell)
        val executable = when {
            !path.contains(' ') -> path
            shell == Shell.POWERSHELL -> "& \"$path\""
            else -> "\"$path\""
        }
        return if (cont) "$executable --continue" else executable
    }

    /**
     * Maps the terminal's configured shell path (the "Shell path" setting) to a [Shell] family.
     *
     * The path can be empty (the IDE picks the system default), a bare executable name
     * (`powershell.exe`) or an executable with arguments (`"C:\cygwin\bin\bash.exe" --login -i`).
     * An unrecognised shell stays [Shell.UNKNOWN], which uses the form every shell accepts.
     */
    fun detectShell(shellPath: String?): Shell {
        val path = shellPath?.trim()?.lowercase().orEmpty()
        return when {
            path.isEmpty() -> Shell.UNKNOWN
            path.contains("wsl") -> Shell.WSL
            path.contains("powershell") || path.contains("pwsh") -> Shell.POWERSHELL
            path.contains("cmd.exe") || path == "cmd" -> Shell.CMD
            // The bash.exe shipped in System32 is the WSL launcher, not a POSIX shell of its own.
            path.contains("system32") && path.contains("bash") -> Shell.WSL
            path.contains("bash") || path.contains("zsh") || path.contains("fish") ||
                path.contains("cygwin") || path.contains("sh.exe") || path.contains("git") -> Shell.POSIX
            else -> Shell.UNKNOWN
        }
    }

    /** Rewrites a Windows absolute path so [shell] can execute it; other values are returned as is. */
    private fun executablePath(binary: String, shell: Shell): String {
        if (!WINDOWS_ABSOLUTE_PATH.containsMatchIn(binary)) return binary
        val path = binary.replace('\\', '/')
        if (shell != Shell.WSL) return path
        val drive = path[0].lowercaseChar()
        return "/mnt/$drive${path.substring(2)}"
    }
}
