package com.zhucanle.opencodev2.ide.ui

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Component
import java.awt.Container
import javax.swing.JComponent
import javax.swing.JRadioButton

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

    /** The mode lives in the application-level options, so a new dialog must reopen in it. */
    fun testSavedRemoteModeIsRestored() {
        withSavedMode("remote") {
            ApplicationManager.getApplication().invokeAndWait {
                val dialog = OpenCodeConnectDialog(project)
                try {
                    assertTrue(
                        "A saved remote choice must be restored",
                        radio(dialog, "Remote server").isSelected
                    )
                    assertFalse(
                        "The two modes are exclusive",
                        radio(dialog, "Local service").isSelected
                    )
                } finally {
                    Disposer.dispose(dialog.disposable)
                }
            }
        }
    }

    /** Pressing Connect persists the current choice, not the mode the dialog opened in. */
    fun testConnectSavesTheChosenMode() {
        withSavedMode("local") {
            ApplicationManager.getApplication().invokeAndWait {
                val dialog = OpenCodeConnectDialog(project)
                try {
                    radio(dialog, "Remote server").isSelected = true
                    pressConnect(dialog)
                } finally {
                    Disposer.dispose(dialog.disposable)
                }
            }
            assertEquals("remote", PropertiesComponent.getInstance().getValue(MODE_KEY))
        }
    }

    /** Runs [body] with the saved mode set to [mode], then restores the option. */
    private fun withSavedMode(mode: String, body: () -> Unit) {
        val props = PropertiesComponent.getInstance()
        val previous = props.getValue(MODE_KEY)
        props.setValue(MODE_KEY, mode)
        try {
            body()
        } finally {
            if (previous == null) props.unsetValue(MODE_KEY) else props.setValue(MODE_KEY, previous)
        }
    }

    private fun radio(dialog: OpenCodeConnectDialog, text: String): JRadioButton =
        (invoke(dialog, "createCenterPanel") as JComponent).descendants()
            .filterIsInstance<JRadioButton>()
            .first { it.text == text }

    /** Presses Connect. */
    private fun pressConnect(dialog: OpenCodeConnectDialog) {
        invoke(dialog, "doOKAction")
    }

    /**
     * Calls a protected [DialogWrapper] hook. The dialog is built lazily, so the tests have to ask
     * for the panel (and press Connect) the same way the framework does; reflecting the two hooks is
     * cheaper than a test-only seam in the production dialog.
     */
    private fun invoke(dialog: OpenCodeConnectDialog, name: String): Any? {
        val method = OpenCodeConnectDialog::class.java.getDeclaredMethod(name)
        method.isAccessible = true
        return method.invoke(dialog)
    }

    private fun Component.descendants(): List<Component> =
        listOf(this) + ((this as? Container)?.components?.flatMap { it.descendants() } ?: emptyList())

    private companion object {
        /** The same key as `OpenCodeConnectDialog.PROP_CONNECTION_MODE`, which is private there. */
        const val MODE_KEY = "opencode.connectionMode"
    }
}
