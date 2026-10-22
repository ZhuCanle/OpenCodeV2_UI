package com.zhucanle.opencodev2.ide.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenCodeCliResolverTest {

    @Test
    fun skipsTheExtensionlessPosixShimAndTakesTheCmdWrapper() {
        val output = """
            C:\Users\me\scoop\apps\nodejs-lts\current\bin\opencode
            C:\Users\me\scoop\apps\nodejs-lts\current\bin\opencode.cmd
        """.trimIndent()

        assertEquals(
            "C:\\Users\\me\\scoop\\apps\\nodejs-lts\\current\\bin\\opencode.cmd",
            OpenCodeCliResolver.selectWindowsExecutable(output)
        )
    }

    @Test
    fun keepsThePathOrderOfWhere() {
        val output = """
            C:\Users\me\AppData\Roaming\npm\opencode.exe
            C:\Users\me\scoop\apps\nodejs-lts\current\bin\opencode.cmd
        """.trimIndent()

        assertEquals(
            "C:\\Users\\me\\AppData\\Roaming\\npm\\opencode.exe",
            OpenCodeCliResolver.selectWindowsExecutable(output)
        )
    }

    @Test
    fun acceptsPathsWithSpacesAndCrlfLineEndings() {
        val output = "C:\\Program Files\\opencode\\opencode.exe\r\n\r\nC:\\tools\\opencode.cmd\r\n"

        assertEquals(
            "C:\\Program Files\\opencode\\opencode.exe",
            OpenCodeCliResolver.selectWindowsExecutable(output)
        )
    }

    @Test
    fun acceptsTheExtensionCaseInsensitively() {
        val output = "C:\\tools\\opencode.CMD"

        assertEquals("C:\\tools\\opencode.CMD", OpenCodeCliResolver.selectWindowsExecutable(output))
    }

    @Test
    fun rejectsEntriesJavaCannotExecute() {
        val output = """
            C:\Users\me\scoop\apps\nodejs-lts\current\bin\opencode
            C:\Users\me\scoop\apps\nodejs-lts\current\bin\opencode.ps1
            C:\tools.cmd\opencode
        """.trimIndent()

        assertNull(OpenCodeCliResolver.selectWindowsExecutable(output))
    }

    @Test
    fun returnsNullForEmptyOutput() {
        assertNull(OpenCodeCliResolver.selectWindowsExecutable(""))
    }
}
