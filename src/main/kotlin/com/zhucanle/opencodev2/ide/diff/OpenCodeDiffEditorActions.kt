package com.zhucanle.opencodev2.ide.diff

import com.zhucanle.opencodev2.ide.api.models.DiffEntry
import com.zhucanle.opencodev2.ide.session.SessionManager
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ui.Messages

private fun findEntryIndex(entries: List<DiffEntry>, filePath: String): Int {
    val idx = entries.indexOfFirst { it.file == filePath }
    return if (idx >= 0) idx else 0
}

private fun closeCurrentDiffIfPossible(e: AnActionEvent) {
    val project = e.project ?: return

    // 尝试获取当前的 VirtualFile（Diff 视图通常是一个虚拟文件）
    val virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
    
    // 只要文件存在，就尝试关闭它。DiffVirtualFile 通常是临时的。
    // 如果是在普通编辑器里（不太可能，因为 Action 只在 Diff 上下文显示），关闭也是合理的。
    FileEditorManager.getInstance(project).closeFile(virtualFile)
}

private fun openNextDiff(
    project: com.intellij.openapi.project.Project,
    batch: DiffReviewBatch,
    previousIndex: Int,
    closeEvent: AnActionEvent?
) {
    if (closeEvent != null) {
        closeCurrentDiffIfPossible(closeEvent)
    }

    // Recheck after the close event: another turn may replace a snapshot before this runs.
    ApplicationManager.getApplication().invokeLater {
        if (project.isDisposed) return@invokeLater
        val remaining = batch.remaining(project.service<SessionManager>())
        if (remaining.isEmpty()) return@invokeLater
        val nextIndex = previousIndex.coerceIn(0, remaining.lastIndex)
        project.service<DiffViewerService>().showMultiFileDiff(remaining, nextIndex)
    }
}

internal class AcceptFromDiffEditorAction(
    private val displayedEntry: DiffEntry,
    private val batch: DiffReviewBatch
) : AnAction() {
    private val filePath = displayedEntry.file
    init {
        templatePresentation.text = "Accept"
        templatePresentation.description = "Accept this change (git add)"
        templatePresentation.icon = AllIcons.Actions.Checked
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val sessionManager = project.service<SessionManager>()

        val before = batch.remaining(sessionManager)
        val previousIndex = findEntryIndex(before, filePath)

        val entry = displayedEntry
        if (sessionManager.getDiffForFile(filePath) === entry && sessionManager.isCurrentScope(entry.scopeVersion)) {
            // Use the callback to navigate after accept completes
            sessionManager.acceptDiff(entry) { success ->
                if (success) {
                    openNextDiff(project, batch, previousIndex, e)
                } else {
                    Messages.showWarningDialog(project, "Failed to stage $filePath", "Accept Failed")
                }
            }
        } else {
            ApplicationManager.getApplication().invokeLater {
                Messages.showWarningDialog(project, "This diff is out of date. Review the latest diff before accepting.", "Accept Failed")
            }
        }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val enabled = project != null && project.service<SessionManager>().getDiffForFile(filePath) === displayedEntry
        e.presentation.isEnabled = enabled
    }
}

internal class RejectFromDiffEditorAction(
    private val displayedEntry: DiffEntry,
    private val batch: DiffReviewBatch
) : AnAction() {
    private val filePath = displayedEntry.file
    init {
        templatePresentation.text = "Reject"
        templatePresentation.description = "Reject this change (restore original)"
        templatePresentation.icon = AllIcons.Actions.Rollback
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val sessionManager = project.service<SessionManager>()
        val entry = displayedEntry
        if (sessionManager.getDiffForFile(filePath) !== entry) {
            Messages.showWarningDialog(project, "This diff is out of date. Review the latest diff before rejecting.", "Reject Failed")
            return
        }

        // Build confirmation message based on file state
        val isNewFile = entry.isNewFile
        val hasUserEdits = entry.hasUserEdits
        
        val message = buildString {
            if (isNewFile) {
                append("Delete new file '${entry.file}'?\n\n")
                append("This file was created by OpenCode and will be removed.")
            } else {
                append("Reject changes to '${entry.file}'?\n\n")
                append("The file will be restored to its state before OpenCode modified it.")
            }
            
            // Per design doc: warn if user has edits in this file
            if (hasUserEdits) {
                append("\n\n")
                append("WARNING: You have also edited this file. Your changes will be lost!")
            }
        }

        val confirm = Messages.showYesNoDialog(
            project,
            message,
            if (hasUserEdits) "Confirm Reject (Data Loss Warning)" else "Confirm Reject",
            Messages.getQuestionIcon()
        )

        if (confirm != Messages.YES) return

        val before = batch.remaining(sessionManager)
        val previousIndex = findEntryIndex(before, filePath)

        // Use the callback to navigate after reject completes (no more ProgressManager race)
        sessionManager.rejectDiff(entry) { success ->
            if (success) {
                openNextDiff(project, batch, previousIndex, e)
            } else {
                Messages.showWarningDialog(project,
                    "Could not restore $filePath. The file may have changed since this diff was captured. " +
                        "Review the current file before rejecting.", "Reject Failed")
            }
        }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val entry = if (project != null) project.service<SessionManager>().getDiffForFile(filePath) else null
        e.presentation.isEnabled = entry === displayedEntry
    }
}
