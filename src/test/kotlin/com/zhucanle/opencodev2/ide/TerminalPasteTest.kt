package com.zhucanle.opencodev2.ide

import com.zhucanle.opencodev2.ide.terminal.TtyTerminalTextWriter
import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jediterm.terminal.TtyConnector
import java.io.ByteArrayOutputStream

/**
 * Verifies the v2 `@`-reference injection path.
 *
 * opencode v2 removed `POST /tui/append-prompt`, so the plugin must inject `@path#Lx-y` references
 * by writing them to the terminal TTY and report whether the write landed.
 */
class TerminalPasteTest : BasePlatformTestCase() {

    /** Fake TTY that records every byte the plugin writes to the terminal. */
    private class FakeTtyConnector : TtyConnector {
        private val writtenBytes = ByteArrayOutputStream()

        val writtenText: String
            get() = writtenBytes.toString(Charsets.UTF_8.name())

        override fun read(buf: CharArray, offset: Int, length: Int): Int = -1

        override fun write(bytes: ByteArray) {
            writtenBytes.write(bytes)
        }

        override fun write(str: String) {
            writtenBytes.write(str.toByteArray(Charsets.UTF_8))
        }

        override fun isConnected(): Boolean = true

        override fun waitFor(): Int = 0

        override fun ready(): Boolean = true

        override fun getName(): String = "fake-tty"

        override fun close() {}
    }

    /** TTY that rejects writes, standing in for a terminal that died mid-write. */
    private class FailingTtyConnector : TtyConnector {
        override fun read(buf: CharArray, offset: Int, length: Int): Int = -1

        override fun write(bytes: ByteArray) {
            throw java.io.IOException("tty closed")
        }

        override fun write(str: String) {
            throw java.io.IOException("tty closed")
        }

        override fun isConnected(): Boolean = false

        override fun waitFor(): Int = 0

        override fun ready(): Boolean = false

        override fun getName(): String = "failing-tty"

        override fun close() {}
    }

    private lateinit var service: OpenCodeService

    override fun setUp() {
        super.setUp()
        service = project.service()
    }

    fun testPasteWritesReferenceVerbatimToTerminalTty() {
        val tty = FakeTtyConnector()
        service.terminalTextWriterProvider = { TtyTerminalTextWriter(tty) }

        assertTrue("paste must report success once the TTY accepted the text", service.pasteToTerminal("@a.kt "))

        assertEquals("@a.kt ", tty.writtenText)
    }

    fun testPastePreservesEditorSelectionReference() {
        val tty = FakeTtyConnector()
        service.terminalTextWriterProvider = { TtyTerminalTextWriter(tty) }

        assertTrue(service.pasteToTerminal("@src/A.kt#L3-9 "))

        assertEquals("@src/A.kt#L3-9 ", tty.writtenText)
    }

    fun testPasteReturnsFalseWithoutTerminal() {
        service.terminalTextWriterProvider = { null }

        assertFalse("paste without a live terminal must fail so the caller can retry", service.pasteToTerminal("@a.kt "))
    }

    fun testPasteReturnsFalseForBlankText() {
        val tty = FakeTtyConnector()
        service.terminalTextWriterProvider = { TtyTerminalTextWriter(tty) }

        assertFalse(service.pasteToTerminal("   "))
        assertEquals("", tty.writtenText)
    }

    fun testWriterReturnsFalseWhenConnectorIsMissing() {
        assertFalse(TtyTerminalTextWriter(null).write("@a.kt "))
    }

    fun testWriterSwallowsTtyFailures() {
        assertFalse(TtyTerminalTextWriter(FailingTtyConnector()).write("@a.kt "))
    }
}
