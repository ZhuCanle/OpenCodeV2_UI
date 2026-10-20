package com.zhucanle.opencodev2.ide.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure unit tests for [OpenCodeCommand].
 * The opencode CLI (v2) launches the TUI and takes no top-level `--hostname`/`--port` flags.
 */
class OpenCodeCommandTest {

    @Test
    fun plainTuiCommandHasNoHostOrPortFlags() {
        assertEquals("opencode", OpenCodeCommand.tui("opencode", cont = false))
    }

    @Test
    fun continueFlagAddsContinue() {
        assertEquals("opencode --continue", OpenCodeCommand.tui("opencode", cont = true))
    }

    @Test
    fun pathWithSpacesIsQuoted() {
        assertEquals(
            "\"/opt/my tools/opencode\" --continue",
            OpenCodeCommand.tui("/opt/my tools/opencode", cont = true)
        )
    }

    @Test
    fun windowsPathUsesForwardSlashes() {
        assertEquals(
            "C:/Users/me/.opencode/bin/opencode.exe --continue",
            OpenCodeCommand.tui("C:\\Users\\me\\.opencode\\bin\\opencode.exe", cont = true)
        )
    }

    @Test
    fun windowsPathWithSpacesUsesForwardSlashesAndStaysQuoted() {
        assertEquals(
            "\"C:/Program Files/opencode/opencode.exe\" --continue",
            OpenCodeCommand.tui("C:\\Program Files\\opencode\\opencode.exe", cont = true)
        )
    }

    @Test
    fun windowsPathWithSpacesNeedsTheCallOperatorOnPowerShell() {
        assertEquals(
            "& \"C:/Program Files/opencode/opencode.exe\" --continue",
            OpenCodeCommand.tui(
                "C:\\Program Files\\opencode\\opencode.exe",
                cont = true,
                shell = OpenCodeCommand.Shell.POWERSHELL
            )
        )
    }

    @Test
    fun windowsPathWithoutSpacesIsNotPrefixedOnPowerShell() {
        assertEquals(
            "C:/Users/me/.opencode/bin/opencode.exe --continue",
            OpenCodeCommand.tui(
                "C:\\Users\\me\\.opencode\\bin\\opencode.exe",
                cont = true,
                shell = OpenCodeCommand.Shell.POWERSHELL
            )
        )
    }

    @Test
    fun windowsPathIsMountedUnderMntForWsl() {
        assertEquals(
            "/mnt/c/Users/me/.opencode/bin/opencode.exe --continue",
            OpenCodeCommand.tui(
                "C:\\Users\\me\\.opencode\\bin\\opencode.exe",
                cont = true,
                shell = OpenCodeCommand.Shell.WSL
            )
        )
        assertEquals(
            "\"/mnt/c/Program Files/opencode/opencode.exe\" --continue",
            OpenCodeCommand.tui(
                "C:\\Program Files\\opencode\\opencode.exe",
                cont = true,
                shell = OpenCodeCommand.Shell.WSL
            )
        )
    }

    @Test
    fun shellPathIsDetected() {
        assertEquals(OpenCodeCommand.Shell.UNKNOWN, OpenCodeCommand.detectShell(null))
        assertEquals(OpenCodeCommand.Shell.UNKNOWN, OpenCodeCommand.detectShell(""))
        assertEquals(OpenCodeCommand.Shell.CMD, OpenCodeCommand.detectShell("C:\\Windows\\System32\\cmd.exe"))
        assertEquals(OpenCodeCommand.Shell.POWERSHELL, OpenCodeCommand.detectShell("powershell.exe"))
        assertEquals(
            OpenCodeCommand.Shell.POWERSHELL,
            OpenCodeCommand.detectShell("C:\\Program Files\\PowerShell\\7\\pwsh.exe")
        )
        assertEquals(
            OpenCodeCommand.Shell.POSIX,
            OpenCodeCommand.detectShell("C:\\Program Files\\Git\\bin\\bash.exe")
        )
        assertEquals(
            OpenCodeCommand.Shell.POSIX,
            OpenCodeCommand.detectShell("\"C:\\cygwin\\bin\\bash.exe\" --login -i")
        )
        assertEquals(OpenCodeCommand.Shell.WSL, OpenCodeCommand.detectShell("wsl.exe"))
        assertEquals(
            OpenCodeCommand.Shell.WSL,
            OpenCodeCommand.detectShell("C:\\Windows\\System32\\bash.exe")
        )
    }
}
