package com.zhucanle.opencodev2.ide

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins which UI a connection opens, the decision behind [OpenCodeService.startLocalSession].
 *
 * Regression: a web-mode connection used to create the terminal TUI before the web tab, so every
 * web session left a second, unused terminal tab behind (and the launch shortcut focused that
 * terminal instead of the web tab). The terminal is only a fallback for the case where the web tab
 * cannot be opened at all.
 */
class OpenCodeServiceConnectionUiTest {

    @Test
    fun webModeDoesNotOpenTheTerminal() {
        assertFalse(OpenCodeService.opensTerminalTui(web = true, webTabOpened = true))
    }

    @Test
    fun webModeFallsBackToTheTerminalWhenTheTabCannotOpen() {
        assertTrue(OpenCodeService.opensTerminalTui(web = true, webTabOpened = false))
    }

    @Test
    fun terminalModeOpensTheTerminal() {
        assertTrue(OpenCodeService.opensTerminalTui(web = false, webTabOpened = false))
    }
}
