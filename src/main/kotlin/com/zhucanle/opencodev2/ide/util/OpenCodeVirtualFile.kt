package com.zhucanle.opencodev2.ide.util

import com.intellij.openapi.fileTypes.FileType
import com.intellij.testFramework.LightVirtualFile

import java.util.UUID

/**
 * Shared implementation of the plugin's synthetic editor tabs (terminal and web).
 *
 * Every instance gets a unique path: `FileEditorManager` caches editors by virtual file, and a path
 * reused across sessions made it confuse a stale instance with the live one (deadlocks and
 * "No file exists" errors).
 *
 * @param scheme URL-like scheme of the synthetic path, e.g. `opencode-terminal`
 * @param fileType the fake file type that routes the tab to the plugin's editor provider
 */
abstract class OpenCodeVirtualFile(
    name: String,
    private val scheme: String,
    private val fileType: FileType
) : LightVirtualFile(name) {

    private val uniqueId = UUID.randomUUID().toString()

    override fun getFileType(): FileType = fileType

    override fun isWritable(): Boolean = false

    override fun getPath(): String = "$scheme://$name/$uniqueId"

    override fun toString(): String = "${javaClass.simpleName}($name-$uniqueId)"
}
