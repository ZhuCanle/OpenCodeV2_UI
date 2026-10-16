package com.zhucanle.opencodev2.ide.ui

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for the pure connection validator extracted from [OpenCodeConnectDialog.doValidate].
 *
 * opencode v2 serves the API behind HTTP Basic auth. Local hosts run the service-managed background
 * service whose password is auto-discovered from `service.json`, so a blank password must be
 * accepted for them; remote hosts authenticate with the password printed by `opencode serve` and
 * therefore require a non-blank value. The validator is deliberately free of Swing/EDT state so it
 * can be exercised in a plain unit test.
 */
class ConnectDialogValidationTest {

    @Test
    fun blankPasswordIsRejectedForRemoteHost() {
        val info = OpenCodeConnectDialog.validateConnection("10.0.0.5:49374", "")
        assertNotNull("A blank password must produce a validation error for a remote host", info)
    }

    @Test
    fun whitespaceOnlyPasswordIsRejectedForRemoteHost() {
        val info = OpenCodeConnectDialog.validateConnection("10.0.0.5:49374", "   ")
        assertNotNull("A whitespace-only password must produce a validation error for a remote host", info)
    }

    @Test
    fun blankPasswordIsAcceptedForLocalHosts() {
        assertNull(OpenCodeConnectDialog.validateConnection("127.0.0.1:49374", ""))
        assertNull(OpenCodeConnectDialog.validateConnection("localhost:49374", ""))
        assertNull(OpenCodeConnectDialog.validateConnection("0.0.0.0:49374", ""))
    }

    @Test
    fun blankPasswordIsAcceptedForLocalHostRegardlessOfCase() {
        assertNull(OpenCodeConnectDialog.validateConnection("LocalHost:49374", "   "))
    }

    @Test
    fun nonBlankPasswordWithValidAddressIsAccepted() {
        assertNull(OpenCodeConnectDialog.validateConnection("127.0.0.1:49374", "s3cret"))
        assertNull(OpenCodeConnectDialog.validateConnection("10.0.0.5:49374", "s3cret"))
    }

    @Test
    fun malformedAddressIsStillRejected() {
        assertNotNull(OpenCodeConnectDialog.validateConnection("", ""))
        assertNotNull(OpenCodeConnectDialog.validateConnection("no-port-here", ""))
        assertNotNull(OpenCodeConnectDialog.validateConnection(":49374", ""))
        assertNotNull(OpenCodeConnectDialog.validateConnection("127.0.0.1:0", ""))
        assertNotNull(OpenCodeConnectDialog.validateConnection("127.0.0.1:99999", ""))
    }
}
