package com.zhucanle.opencodev2.ide

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the launch decision table used by [OpenCodeService.focusOrCreateTerminal].
 *
 * The decision is a pure function on purpose: the live server probe behind it is a blocking HTTP
 * request, so the UI code must not evaluate the table before that probe has run on a worker
 * thread. Before the table was extracted, the probe ran on the EDT and the "endpoint known but
 * dead, not interactive" case silently did nothing (it called the disconnected dialog with
 * `interactive = false`, which returns immediately).
 */
class OpenCodeServiceLaunchActionTest {

    private fun decide(
        interactive: Boolean = true,
        connected: Boolean = false,
        uiOpen: Boolean = false,
        hasEndpoint: Boolean = true,
        hasMode: Boolean = true,
        running: Boolean = true
    ) = OpenCodeService.launchAction(interactive, connected, uiOpen, hasEndpoint, hasMode, running)

    @Test
    fun connectedWithOpenUiIsFocusedWhenInteractive() {
        assertEquals(
            OpenCodeService.LaunchAction.FOCUS_UI,
            decide(interactive = true, connected = true, uiOpen = true)
        )
    }

    @Test
    fun connectedWithOpenUiIsLeftAloneWhenNotInteractive() {
        assertEquals(
            OpenCodeService.LaunchAction.NO_OP,
            decide(interactive = false, connected = true, uiOpen = true)
        )
    }

    @Test
    fun noEndpointAsksForAConnection() {
        assertEquals(
            OpenCodeService.LaunchAction.SHOW_CONNECT_DIALOG,
            decide(hasEndpoint = false, running = true, interactive = true)
        )
        assertEquals(
            OpenCodeService.LaunchAction.SHOW_CONNECT_DIALOG,
            decide(hasEndpoint = false, running = false, interactive = false)
        )
    }

    @Test
    fun endpointWithoutModeAsksForAConnection() {
        assertEquals(
            OpenCodeService.LaunchAction.SHOW_CONNECT_DIALOG,
            decide(hasMode = false, running = true, interactive = true)
        )
    }

    @Test
    fun aliveEndpointAsksToRestoreWhenInteractive() {
        assertEquals(
            OpenCodeService.LaunchAction.ASK_RESTORE,
            decide(interactive = true, running = true)
        )
    }

    @Test
    fun aliveEndpointRestoresTheUiWhenNotInteractive() {
        assertEquals(
            OpenCodeService.LaunchAction.RESTORE_UI,
            decide(interactive = false, running = true)
        )
    }

    @Test
    fun deadEndpointAsksToRestartWhenInteractive() {
        assertEquals(
            OpenCodeService.LaunchAction.ASK_RESTART,
            decide(interactive = true, running = false)
        )
    }

    /**
     * Regression: this branch used to call the disconnected dialog with `interactive = false`,
     * which returns immediately, so pressing the shortcut did nothing at all.
     */
    @Test
    fun deadEndpointStillOpensTheConnectDialogWhenNotInteractive() {
        assertEquals(
            OpenCodeService.LaunchAction.SHOW_CONNECT_DIALOG,
            decide(interactive = false, running = false)
        )
    }
}
