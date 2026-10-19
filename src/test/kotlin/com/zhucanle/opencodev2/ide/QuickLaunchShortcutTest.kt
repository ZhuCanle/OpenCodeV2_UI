package com.zhucanle.opencodev2.ide

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.util.SystemInfo
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import javax.swing.KeyStroke

/**
 * Guards the quick launch key bindings.
 *
 * The original plugin bound `Cmd+Esc` on macOS, a keystroke the IDE's own
 * `Vcs.CombinedDiff.ToggleCollapseBlock` action already claims. When two actions claim one keystroke
 * only one of them can fire, so the shortcut looked dead. These tests fail if the action loses its
 * shortcut or starts colliding with another action again.
 */
class QuickLaunchShortcutTest : BasePlatformTestCase() {

    fun testQuickLaunchHasShortcut() {
        assertNotNull(ActionManager.getInstance().getAction("OpenCodeV2.QuickLaunch"))
        assertFalse(
            "OpenCodeV2.QuickLaunch has no keyboard shortcut",
            shortcutsOf("OpenCodeV2.QuickLaunch").isEmpty()
        )
    }

    fun testQuickLaunchShortcutIsNotClaimedByAnotherAction() {
        val ours = shortcutsOf("OpenCodeV2.QuickLaunch")
        val otherIds = ActionManager.getInstance().getActionIdList("")
            .filter { it != "OpenCodeV2.QuickLaunch" }

        for (shortcut in ours) {
            val clashes = otherIds.filter { id ->
                shortcutsOf(id).any {
                    it.firstKeyStroke == shortcut.firstKeyStroke && it.secondKeyStroke == shortcut.secondKeyStroke
                }
            }
            assertEmpty("${shortcut.firstKeyStroke} is also bound to $clashes", clashes)
        }
    }

    /**
     * The README, the plugin description and this test must agree on the macOS bindings:
     * `Cmd+\` is the documented primary shortcut and `Shift+Cmd+Esc` the documented alternative.
     */
    fun testMacOsLaunchShortcutsMatchTheDocumentedOnes() {
        if (!SystemInfo.isMac) return

        val strokes = shortcutsOf("OpenCodeV2.QuickLaunch").map { it.firstKeyStroke }

        assertContainsElements(
            strokes,
            KeyStroke.getKeyStroke("meta BACK_SLASH"),
            KeyStroke.getKeyStroke("shift meta ESCAPE")
        )
        assertFalse(
            "Cmd+Esc is owned by Vcs.CombinedDiff.ToggleCollapseBlock and must not be bound again",
            strokes.contains(KeyStroke.getKeyStroke("meta ESCAPE"))
        )
    }

    private fun shortcutsOf(actionId: String): List<KeyboardShortcut> =
        KeymapManager.getInstance().activeKeymap.getShortcuts(actionId).filterIsInstance<KeyboardShortcut>()
}
