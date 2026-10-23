package com.zhucanle.opencodev2.ide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `TerminalProjectOptionsProvider.getShellPath()` blocks (`runBlockingMaybeCancellable`) since
 * IntelliJ 2026.2 and throws "This method is forbidden on EDT" when called there. The plugin used
 * to read it inside the `invokeLater` that opens the terminal tab, which logged that exception on
 * the first launch of every IDE session (issue #3).
 *
 * These tests pin the guard that keeps the read off the EDT.
 */
class OpenCodeServiceShellPathTest {

    @Test
    fun doesNotReadTheShellPathOnEdt() {
        var read = false

        val result = OpenCodeService.readShellPathSafely(onEdt = true) {
            read = true
            "C:\\Program Files\\Git\\bin\\bash.exe"
        }

        assertNull(result)
        assertTrue("the provider must not be touched on the EDT", !read)
    }

    @Test
    fun readsTheShellPathOffEdt() {
        val result = OpenCodeService.readShellPathSafely(onEdt = false) { "/bin/zsh" }

        assertEquals("/bin/zsh", result)
    }

    @Test
    fun returnsNullWhenTheProviderFails() {
        val result = OpenCodeService.readShellPathSafely(onEdt = false) { error("no terminal plugin") }

        assertNull(result)
    }
}
