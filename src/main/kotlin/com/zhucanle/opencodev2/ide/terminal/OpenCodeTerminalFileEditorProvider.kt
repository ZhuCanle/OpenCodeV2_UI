package com.zhucanle.opencodev2.ide.terminal

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.components.service
import com.zhucanle.opencodev2.ide.OpenCodeService
import com.zhucanle.opencodev2.ide.util.ProjectScopedMap
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.plugins.terminal.ShellTerminalWidget
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Provider for OpenCode terminal file editors.
 * 
 * Manages the lifecycle of terminal widgets.
 * Implements "Delayed Disposal" to distinguish between closing a tab (kill) 
 * and moving a tab (keep alive).
 */
class OpenCodeTerminalFileEditorProvider : FileEditorProvider, DumbAware {

    companion object {
        private val logger = Logger.getInstance(OpenCodeTerminalFileEditorProvider::class.java)

        /**
         * Delay before a closed tab's widget is disposed. It absorbs the "tab move" case (close +
         * reopen in another window/pane), which must not kill the running terminal.
         */
        private const val DISPOSAL_DELAY_MS = 5_000L

        // Widgets are owned by the project that created them: several projects share this process,
        // and closing one window must never dispose another window's terminal.
        private val terminalWidgets = ProjectScopedMap<Project, OpenCodeTerminalVirtualFile, ShellTerminalWidget>()
        
        // Track pending disposal tasks
        private val disposalTasks = ConcurrentHashMap<OpenCodeTerminalVirtualFile, ScheduledFuture<*>>()
        
        fun registerWidget(file: OpenCodeTerminalVirtualFile, widget: ShellTerminalWidget, project: Project) {
            logger.debug("Registering terminal widget for file: ${file.name}")
            cancelDisposal(file) // Cancel any pending disposal if re-registering
            terminalWidgets.put(project, file, widget)
        }

        fun hasWidget(project: Project, file: OpenCodeTerminalVirtualFile): Boolean {
            return terminalWidgets.containsKey(project, file)
        }

        /**
         * Schedule widget for disposal after [DISPOSAL_DELAY_MS].
         * This handles the "Tab Move" scenario where a tab is closed and immediately re-opened.
         */
        fun scheduleDisposal(file: OpenCodeTerminalVirtualFile, project: Project) {
            // Cancel any existing task first
            cancelDisposal(file)
            
            // Only schedule if we actually have a widget to dispose
            if (!terminalWidgets.containsKey(project, file)) return

            logger.debug("Scheduling disposal check for ${file.name} in ${DISPOSAL_DELAY_MS}ms")
            
            val task = AppExecutorUtil.getAppScheduledExecutorService().schedule({
                // Must run on EDT to check FileEditorManager status safely
                ApplicationManager.getApplication().invokeLater {
                    // Drop the completed task: the map must not retain a done future (and the
                    // project/file it captured) until the next disposal.
                    disposalTasks.remove(file)
                    if (project.isDisposed) return@invokeLater
                    
                    // CRITICAL CHECK: Is the file currently open?
                    // This handles cases where:
                    // 1. The file was moved to a new window (so it's open there)
                    // 2. The file is split (so it's open in another pane)
                    // 3. The file was re-opened very quickly
                    val isStillOpen = FileEditorManager.getInstance(project).isFileOpen(file)
                    
                    if (isStillOpen) {
                        logger.debug("Aborting disposal for ${file.name}: File is still open (move/split detected)")
                    } else {
                        logger.info("Disposing ${file.name}: File is truly closed")
                        disposeWidget(file, project)
                    }
                }
            }, DISPOSAL_DELAY_MS, TimeUnit.MILLISECONDS)
            
            disposalTasks[file] = task
        }

        /**
         * Cancel pending disposal.
         * Called when the file is re-opened (e.g. during tab move).
         */
        fun cancelDisposal(file: OpenCodeTerminalVirtualFile) {
            val task = disposalTasks.remove(file)
            if (task != null) {
                if (!task.isDone) {
                    task.cancel(false)
                    logger.debug("Cancelled disposal task for ${file.name}")
                }
            }
        }

        fun disposeWidget(file: OpenCodeTerminalVirtualFile, project: Project?, notifyService: Boolean = true) {
            // Remove from the registry first: the service notification below re-enters this method
            // (disconnectAndReset), and the second call must find nothing to dispose.
            disposalTasks.remove(file)
            
            val widget = if (project != null) terminalWidgets.remove(project, file) else terminalWidgets.removeAny(file)
            if (widget == null) return

            // Notify the service BEFORE disposing the widget: it resolves the TUI process through
            // the editor's widget, which dispose() clears, and a missing process means the opencode
            // child process is left running.
            if (notifyService && project != null && !project.isDisposed) {
                runCatching { project.service<OpenCodeService>().onTerminalDisposed() }
                    .onFailure { logger.warn("Could not reset the OpenCode connection after terminal disposal", it) }
            }

            logger.debug("Disposing terminal widget for file: ${file.name}")
            try {
                widget.dispose()
            } catch (e: Exception) {
                logger.warn("Error disposing terminal widget", e)
            }
        }
        
        /**
         * Clears registered widgets.
         *
         * @param project when non-null, only that project's widgets are disposed; pass null to
         *   clear every project (plugin unload).
         */
        fun clearAll(project: Project? = null) {
            logger.debug("Clearing terminal widgets for ${project?.name ?: "all projects"}")
            val doomed = project?.let { terminalWidgets.keysOf(it) } ?: terminalWidgets.keys()

            // Cancel pending tasks first so a scheduled disposal cannot race the teardown.
            doomed.forEach { disposalTasks.remove(it)?.cancel(false) }

            doomed.forEach { disposeWidget(it, project, notifyService = false) }
        }
    }

    override fun accept(project: Project, file: VirtualFile): Boolean {
        return file is OpenCodeTerminalVirtualFile
    }

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        require(file is OpenCodeTerminalVirtualFile) { "File must be OpenCodeTerminalVirtualFile" }
        
        // Critical: Cancel any pending disposal since we are creating an editor for it!
        // This handles the "Fast Move" scenario where createEditor is called before the task runs.
        cancelDisposal(file)
        
        val widget = terminalWidgets.get(project, file)
        
        if (widget == null) {
            // This happens when IDE restores a session but our plugin hasn't re-initialized the terminal yet.
            // Or if the user closed the tab, waited longer than the disposal delay, and then re-opened it.
            logger.warn("Terminal widget not found for file: ${file.name} (creating placeholder or waiting for service)")
        } else {
            logger.debug("Creating terminal editor for existing widget: ${file.name}")
        }
        
        return OpenCodeTerminalFileEditor(project, file, widget)
    }

    override fun getEditorTypeId(): String = "opencode-terminal-editor"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}
