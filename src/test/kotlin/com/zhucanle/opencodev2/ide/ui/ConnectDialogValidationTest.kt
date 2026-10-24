package com.zhucanle.opencodev2.ide.ui

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for the pure connection validator extracted from [OpenCodeConnectDialog.doValidate].
 *
 * The dialog is local-first: a local session talks to the service-managed background daemon, whose
 * endpoint and password come from `service.json` via discovery, so nothing typed in the dialog is
 * authoritative and no field is required. A remote session authenticates with the password printed
 * by `opencode serve` and therefore needs a valid `host:port` and a non-blank password. The
 * validator is deliberately free of Swing/EDT state so it can be exercised in a plain unit test.
 */
class ConnectDialogValidationTest {

    @Test
    fun localSessionNeedsNoInput() {
        assertNull(OpenCodeConnectDialog.validateConnection(local = true, host = "", password = ""))
        assertNull(
            OpenCodeConnectDialog.validateConnection(local = true, host = "not-an-address", password = "  ")
        )
        assertNull(
            OpenCodeConnectDialog.validateConnection(local = true, host = "10.0.0.5:49374", password = "")
        )
    }

    @Test
    fun blankPasswordIsRejectedForRemoteSession() {
        val info = OpenCodeConnectDialog.validateConnection(local = false, host = "10.0.0.5:49374", password = "")
        assertNotNull("A blank password must produce a validation error for a remote session", info)
    }

    @Test
    fun whitespaceOnlyPasswordIsRejectedForRemoteSession() {
        val info = OpenCodeConnectDialog.validateConnection(local = false, host = "10.0.0.5:49374", password = "   ")
        assertNotNull("A whitespace-only password must produce a validation error for a remote session", info)
    }

    @Test
    fun validRemoteInputIsAccepted() {
        assertNull(
            OpenCodeConnectDialog.validateConnection(local = false, host = "127.0.0.1:49374", password = "s3cret")
        )
        assertNull(
            OpenCodeConnectDialog.validateConnection(local = false, host = "10.0.0.5:49374", password = "s3cret")
        )
    }

    @Test
    fun malformedAddressIsRejectedForRemoteSession() {
        for (host in listOf("", "no-port-here", ":49374", "127.0.0.1:0", "127.0.0.1:99999")) {
            val info = OpenCodeConnectDialog.validateConnection(local = false, host = host, password = "s3cret")
            assertNotNull("Address '$host' must be rejected for a remote session", info)
        }
    }
}
