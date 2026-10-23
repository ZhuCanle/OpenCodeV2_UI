package com.zhucanle.opencodev2.ide.terminal

import com.zhucanle.opencodev2.ide.util.PathUtil

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.AppExecutorUtil

import com.jediterm.terminal.model.hyperlinks.HyperlinkFilter
import com.jediterm.terminal.model.hyperlinks.LinkInfo
import com.jediterm.terminal.model.hyperlinks.LinkResult
import com.jediterm.terminal.model.hyperlinks.LinkResultItem
import org.jetbrains.plugins.terminal.ShellTerminalWidget

import java.util.regex.Pattern

/**
 * Jediterm hyperlink filter that makes @path, @"path with spaces", and @path#Lx-y clickable in the terminal.
 * Clicking opens the file and highlights the specified line range.
 *
 * @param baseDir directory relative references are resolved against. It is the terminal's working
 *   directory (the connect dialog's custom base path when set), not always `project.basePath`.
 */
class OpenCodeTerminalLinkFilter private constructor(
    private val project: Project,
    private val baseDir: String?
) : HyperlinkFilter {

    override fun apply(line: String): LinkResult? {
        val matcher = LINK_PATTERN.matcher(line)
        val items = mutableListOf<LinkResultItem>()

        while (matcher.find()) {
            val rawFilePath = matcher.group(2) ?: matcher.group(3) ?: continue
            
            // Remove Jediterm wide character placeholders (U+E000)
            // Jediterm uses U+E000 as a placeholder for the second cell of wide characters (CJK)
            val filePath = rawFilePath.filter { it.code != 0xE000 }
            
            logger.debug("Cleaned filePath: '$filePath' (raw: '$rawFilePath')")
            
            val startLine = matcher.group(5)?.toIntOrNull()
            val endLine = matcher.group(7)?.toIntOrNull() ?: startLine

            val linkInfo = LinkInfo {
                navigateToFile(filePath, startLine, endLine)
            }

            items.add(LinkResultItem(matcher.start(), matcher.end(), linkInfo))
        }

        return if (items.isEmpty()) null else LinkResult(items)
    }

    private fun navigateToFile(filePath: String, startLine: Int?, endLine: Int?) {
        // Resolving touches the VFS (and the disk when the file is not cached), so it must not run
        // on the EDT; only the editor opening below does.
        AppExecutorUtil.getAppExecutorService().submit {
            val virtualFile = resolveFile(filePath)
            if (virtualFile == null) {
                logger.warn("File not found: $filePath (base dir: ${baseDir ?: project.basePath})")
                return@submit
            }
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                val descriptor = if (startLine != null) {
                    OpenFileDescriptor(project, virtualFile, startLine.coerceAtLeast(1) - 1, 0)
                } else {
                    OpenFileDescriptor(project, virtualFile)
                }
                val editor = FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
                if (editor != null && startLine != null) {
                    val document = editor.document
                    val startIndex = (startLine - 1).coerceIn(0, document.lineCount - 1)
                    val endIndex = (endLine ?: startLine).minus(1).coerceIn(startIndex, document.lineCount - 1)
                    val startOffset = document.getLineStartOffset(startIndex)
                    val endOffset = document.getLineEndOffset(endIndex)

                    editor.selectionModel.setSelection(startOffset, endOffset)
                    editor.caretModel.moveToOffset(startOffset)
                    editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
                }
            }
        }
    }

    private fun resolveFile(filePath: String): VirtualFile? {
        val resolvedPath = PathUtil.resolveToSystemIndependentPath(baseDir ?: project.basePath, filePath)
            ?: return null
        return LocalFileSystem.getInstance().findFileByPath(resolvedPath)
    }

    companion object {
        private val logger = Logger.getInstance(OpenCodeTerminalLinkFilter::class.java)
        private val LINK_PATTERN = Pattern.compile("@(\"([^\"]+)\"|([^\\s#]+))(#L(\\d+)(-(\\d+)))?")

        /**
         * Installs the filter on [widget].
         *
         * @param baseDir working directory of the terminal, used to resolve relative references
         */
        fun install(project: Project, widget: ShellTerminalWidget, baseDir: String? = null) {
            widget.addHyperlinkFilter(OpenCodeTerminalLinkFilter(project, baseDir))
            logger.info("OpenCode terminal link filter installed (baseDir=${baseDir ?: project.basePath})")
        }
    }
}
