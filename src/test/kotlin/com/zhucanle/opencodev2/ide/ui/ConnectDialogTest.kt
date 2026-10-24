package com.zhucanle.opencodev2.ide.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Smoke test for [OpenCodeConnectDialog].
 *
 * A fresh dialog must open in "Local service" mode, and constructing it must build the whole center
 * panel without exceptions. This also guards the local-first entry point: the dialog used to scan
 * for a free port before it opened, which is exactly the kind of blocking work that breaks here.
 */
class ConnectDialogTest : BasePlatformTestCase() {

    fun testDialogOpensInLocalMode() {
        ApplicationManager.getApplication().invokeAndWait {
            val dialog = OpenCodeConnectDialog(project)
            try {
                assertTrue("A fresh dialog must default to the local service", dialog.local)
            } finally {
                Disposer.dispose(dialog.disposable)
            }
        }
    }
}
