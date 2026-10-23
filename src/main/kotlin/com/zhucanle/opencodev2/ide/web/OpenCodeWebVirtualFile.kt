package com.zhucanle.opencodev2.ide.web

import com.intellij.openapi.fileTypes.ex.FakeFileType
import com.intellij.openapi.util.Key
import com.zhucanle.opencodev2.ide.util.OpenCodeVirtualFile

import javax.swing.Icon

/**
 * Virtual file backing the embedded OpenCode web UI tab.
 *
 * @param tabName tab title, e.g. `OpenCode Web(4096)`
 * @param targetUrl URL the embedded browser loads (`http://host:port/doc`)
 */
class OpenCodeWebVirtualFile(
    tabName: String,
    val targetUrl: String
) : OpenCodeVirtualFile(tabName, "opencode-web", OpenCodeWebFileType) {

    companion object {
        val PASSWORD_KEY: Key<String> = Key.create("OPENCODE_PASSWORD")
    }
}

/** Fake file type that routes [OpenCodeWebVirtualFile] tabs to the JCEF editor provider. */
object OpenCodeWebFileType : FakeFileType() {
    override fun getName(): String = "OpenCode Web"
    override fun getDescription(): String = "OpenCode Web Interface"
    override fun isMyFileType(file: com.intellij.openapi.vfs.VirtualFile): Boolean =
        file is OpenCodeWebVirtualFile
    override fun getIcon(): Icon? = null
}
