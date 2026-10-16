package com.zhucanle.opencodev2.ide.terminal

import com.intellij.openapi.diagnostic.Logger
import com.jediterm.terminal.TtyConnector

/**
 * Sink for text the plugin injects into a terminal as if the user had typed it.
 *
 * opencode v2 removed `POST /tui/append-prompt`; the TUI consumes bytes arriving on its TTY as
 * input, so `@path#Lx-y` references are injected by writing them to the terminal.
 */
fun interface TerminalTextWriter {
    /** @return true when [text] reached the terminal; false when no live terminal accepted it. */
    fun write(text: String): Boolean
}

/**
 * [TerminalTextWriter] backed by a [TtyConnector].
 *
 * Writes the exact UTF-8 bytes a keystroke would produce (no extra newline: the TUI decides what
 * to do with the reference). Never throws: a missing or broken connector reports `false` so the
 * caller can retry while the terminal is still starting up.
 */
class TtyTerminalTextWriter(private val connector: TtyConnector?) : TerminalTextWriter {

    private val logger = Logger.getInstance(TtyTerminalTextWriter::class.java)

    override fun write(text: String): Boolean {
        val target = connector ?: return false
        return try {
            target.write(text.toByteArray(Charsets.UTF_8))
            true
        } catch (e: Exception) {
            logger.warn("[Paste] Failed to write to the terminal TTY: ${e.message}")
            false
        }
    }
}
