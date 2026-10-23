package com.zhucanle.opencodev2.ide.terminal

import com.intellij.openapi.fileTypes.ex.FakeFileType
import com.zhucanle.opencodev2.ide.util.OpenCodeVirtualFile

import javax.swing.Icon

/**
 * Virtual file representing an OpenCode terminal tab in the editor area.
 */
class OpenCodeTerminalVirtualFile(
    terminalName: String = "OpenCode"
) : OpenCodeVirtualFile(terminalName, "opencode-terminal", OpenCodeTerminalFileType)

/** Fake file type that routes [OpenCodeTerminalVirtualFile] tabs to the terminal editor provider. */
object OpenCodeTerminalFileType : FakeFileType() {
    override fun getName(): String = "OpenCode Terminal"
    override fun getDescription(): String = "OpenCode Terminal"
    override fun isMyFileType(file: com.intellij.openapi.vfs.VirtualFile): Boolean =
        file is OpenCodeTerminalVirtualFile
    override fun getIcon(): Icon? = null
}
