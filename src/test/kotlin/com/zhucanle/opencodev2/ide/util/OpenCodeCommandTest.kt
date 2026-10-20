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
}
