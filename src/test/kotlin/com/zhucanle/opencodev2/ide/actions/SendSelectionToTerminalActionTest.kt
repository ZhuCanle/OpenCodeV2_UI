package com.zhucanle.opencodev2.ide.actions

import com.zhucanle.opencodev2.ide.OpenCodeService
import com.zhucanle.opencodev2.ide.SendSelectionToTerminalAction
import com.zhucanle.opencodev2.ide.api.OpenCodeApiClient
import com.zhucanle.opencodev2.ide.terminal.TerminalTextWriter
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.testFramework.MapDataContext
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Verifies the action builds the `@`-reference payload and hands it to the service's paste path.
 *
 * opencode v2 injects references through the terminal TTY (not `POST /tui/append-prompt`), so the
 * service is given a capturing [TerminalTextWriter] and the produced payload is asserted directly.
 */
class SendSelectionToTerminalActionTest : BasePlatformTestCase() {

    private lateinit var service: OpenCodeService
    private lateinit var pasted: CopyOnWriteArrayList<String>

    override fun setUp() {
        super.setUp()
        service = project.service()
        // The API client is only needed so the service considers itself connected; the paste itself
        // must not touch the API any more.
        service.setTestDeps(project.service(), project.service(), OpenCodeApiClient("127.0.0.1", 1))
        pasted = CopyOnWriteArrayList()
        service.terminalTextWriterProvider = { TerminalTextWriter { text -> pasted.add(text); true } }
    }

    fun testDirectorySelectionSendsDirectoryPath() {
        val dir = myFixture.tempDirFixture.findOrCreateDir("root/mydir")
        myFixture.tempDirFixture.createFile("root/mydir/file1.txt")
        myFixture.tempDirFixture.createFile("root/mydir/file2.txt")

        val action = SendSelectionToTerminalAction()
        val dataContext = MapDataContext()
        dataContext.put(CommonDataKeys.PROJECT, project)
        dataContext.put(CommonDataKeys.VIRTUAL_FILE_ARRAY, arrayOf(dir))
        dataContext.put(CommonDataKeys.EDITOR, null)

        action.actionPerformed(AnActionEvent.createFromDataContext("place", null, dataContext))

        val payload = awaitPastedPayload()

        // Expected reference: @<relative dir path> — directories only, never their children.
        assertTrue("Payload should be an @-reference, was: $payload", payload.startsWith("@"))
        assertTrue("Payload should end with a space, was: $payload", payload.endsWith(" "))
        assertTrue("Should contain directory path, was: $payload", payload.contains("mydir"))
        assertFalse("Should NOT contain file1, was: $payload", payload.contains("file1.txt"))
        assertFalse("Should NOT contain file2, was: $payload", payload.contains("file2.txt"))
    }

    fun testEditorSelectionSendsLineRangeReference() {
        val file = myFixture.configureByText("A.kt", "l1\nl2\nl3\n")
        myFixture.editor.selectionModel.setSelection(0, 4)

        val action = SendSelectionToTerminalAction()
        val dataContext = MapDataContext()
        dataContext.put(CommonDataKeys.PROJECT, project)
        dataContext.put(CommonDataKeys.EDITOR, myFixture.editor)
        dataContext.put(CommonDataKeys.VIRTUAL_FILE, file.virtualFile)

        action.actionPerformed(AnActionEvent.createFromDataContext("place", null, dataContext))

        val payload = awaitPastedPayload()

        assertTrue("Selection payload should be an @-reference, was: $payload", payload.startsWith("@"))
        assertTrue("Selection payload should carry the file plus line range, was: $payload", payload.contains("A.kt#L1-2"))
        assertTrue("Payload should end with a space, was: $payload", payload.endsWith(" "))
    }

    /** The action schedules the paste via a short delayed retry loop, so pump events until it lands. */
    private fun awaitPastedPayload(): String {
        val deadline = System.currentTimeMillis() + 5000
        while (pasted.isEmpty() && System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(20)
        }
        assertFalse("Paste never reached the terminal", pasted.isEmpty())
        return pasted.last()
    }
}
