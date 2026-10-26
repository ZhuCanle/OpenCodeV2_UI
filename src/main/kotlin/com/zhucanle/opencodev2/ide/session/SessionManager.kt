package com.zhucanle.opencodev2.ide.session

import com.zhucanle.opencodev2.ide.api.models.DiffEntry
import com.zhucanle.opencodev2.ide.api.models.FileDiff
import com.zhucanle.opencodev2.ide.util.PathUtil
import java.io.File

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.history.Label
import com.intellij.history.LocalHistory
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileEvent
import com.intellij.openapi.vfs.VirtualFileListener
import com.intellij.openapi.vfs.VirtualFileMoveEvent
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.util.concurrency.AppExecutorUtil
import org.jetbrains.annotations.VisibleForTesting
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manages the diff state for a single AI "turn" (busy -> idle cycle).
 *
 * Design principles:
 * 1. Each turn is isolated by turnNumber
 * 2. State is snapshotted at turn end to avoid race conditions
 * 3. LocalHistory for reliable before-state restoration
 */
open class SessionManager(private val project: Project) : Disposable {

    private val logger = Logger.getInstance(SessionManager::class.java)

    // ==================== Turn State ====================

    /**
     * One active AI turn.
     *
     * A project can run several opencode sessions at the same time (the terminal TUI and the web UI,
     * for instance), so every turn owns its own change sets. Before, the state was global: the
     * second `session.execution.started` was ignored and its diffs were folded into the first turn.
     */
    private class TurnState(val number: Int, val label: Label?) {
        val vfsChangedFiles = ConcurrentHashMap.newKeySet<String>()
        val serverEditedFiles = ConcurrentHashMap.newKeySet<String>()
        val aiCreatedFiles = ConcurrentHashMap.newKeySet<String>()
        val userEditedFiles = ConcurrentHashMap.newKeySet<String>()
        val capturedBeforeContent = ConcurrentHashMap<String, String>()

        /** Known file contents at the start of this turn (the 'Before' baseline). */
        @Volatile
        var knownAtStart: Map<String, String> = emptyMap()
    }

    /** Monotonic counter shared by all sessions, so log lines stay unique. */
    private val turnCounter = AtomicInteger(0)

    /** Active turns, keyed by session id. */
    private val turns = ConcurrentHashMap<String, TurnState>()

    /**
     * Files changed while no turn was active (the gap between two turns). They are synced into
     * [lastKnownFileStates] at the next turn start, so the new turn's baseline includes them.
     */
    private val gapChangedFiles = ConcurrentHashMap.newKeySet<String>()

    /**
     * VFS signals observed after a turn ended (typically the refresh the diff pipeline performs).
     * They only serve as affinity hints for late diffs and are cleared at the next turn start.
     */
    private val lateVfsChangedFiles = ConcurrentHashMap.newKeySet<String>()
    private val lateAiCreatedFiles = ConcurrentHashMap.newKeySet<String>()

    /** 
     * Cache of last known file content after a Reject operation.
     * Used as a reliable fallback for 'Before' content if LocalHistory fails/is empty 
     * and Server state is stale (e.g. Server thinks file is deleted).
     */
    private val lastKnownFileStates = ConcurrentHashMap<String, String>()

    // ==================== Pending Diffs ====================
    
    /** Pending diffs awaiting user action, keyed by relative file path */
    private val pendingDiffs = ConcurrentHashMap<String, DiffEntry>()

    // ==================== Change Scope ====================

    /**
     * Working directory of the active session. It differs from [Project.getBasePath] when a custom
     * base path is configured, and file changes under it still belong to this window.
     */
    @Volatile
    var activeBasePath: String? = null

    /** Which VFS changes this window may claim; see [ChangeScope]. */
    @VisibleForTesting
    internal val changeScope = ChangeScope(roots = { workspaceRoots() })

    /**
     * Directories whose file changes belong to this window: the project base path, the session's
     * custom working directory, and every attached content root (a multi-module project keeps its
     * modules outside `basePath`).
     */
    private fun workspaceRoots(): List<String> {
        val roots = mutableListOf<String>()
        project.basePath?.let { roots.add(it) }
        activeBasePath?.let { roots.add(it) }
        if (ApplicationManager.getApplication() != null) {
            runCatching { ProjectRootManager.getInstance(project).contentRoots }
                .getOrNull()
                ?.forEach { roots.add(it.path) }
        }
        return roots
    }

    private fun absolutePathOf(filePath: String): String? {
        val normalized = PathUtil.toSystemIndependentPath(filePath)
        if (File(normalized).isAbsolute) return normalized
        return PathUtil.resolveProjectPath(project, normalized)
    }

    /** True when [filePath] (relative or absolute) belongs to this window's project. */
    private fun isOwnedChange(filePath: String): Boolean =
        absolutePathOf(filePath)?.let { changeScope.isOwned(it) } ?: false

    /**
     * Gate for every VFS callback: drops events from other projects, git metadata and working-tree
     * files rewritten by a git operation (merge, pull, rebase, stash).
     */
    private fun acceptsChange(file: VirtualFile?): Boolean {
        if (file == null || !file.isValid) return false
        return when (val verdict = changeScope.classify(file.path)) {
            ChangeScope.Verdict.TRACK -> true
            ChangeScope.Verdict.IGNORE_GIT_OPERATION -> {
                val relativePath = PathUtil.relativizeToProject(project, file.path)
                // A pending diff captured before the operation no longer matches the file on disk.
                pendingDiffs.remove(relativePath)
                logger.info("[Git] Ignoring change caused by a git operation: $relativePath")
                false
            }
            ChangeScope.Verdict.IGNORE_OUTSIDE -> {
                logger.debug("[Scope] Ignoring change outside the project: ${file.path}")
                false
            }
            ChangeScope.Verdict.IGNORE_METADATA -> false
        }
    }

    /**
     * Binary files cannot be rendered as text diffs: reading a PNG as text yields garbage, which is
     * how an image once ended up in the diff viewer. A NUL byte in the head of the file is the
     * usual binary marker (`git` uses the same rule).
     */
    private fun isBinaryFile(absolutePath: String): Boolean {
        val file = File(absolutePath)
        if (!file.isFile) return false
        return try {
            file.inputStream().use { input ->
                val buffer = ByteArray(1024)
                val read = input.read(buffer)
                read > 0 && buffer.copyOf(read).any { it == 0.toByte() }
            }
        } catch (e: Exception) {
            logger.debug("[Scope] Binary check failed for $absolutePath: ${e.message}")
            false
        }
    }

    // ==================== Document Listener ====================
    
    private val documentListener = object : DocumentListener {
        override fun documentChanged(event: DocumentEvent) {
            if (turns.isEmpty()) return
            val cmd = CommandProcessor.getInstance().currentCommandName
            if (!cmd.isNullOrEmpty()) {
                FileDocumentManager.getInstance().getFile(event.document)?.let { file ->
                    if (!acceptsChange(file)) return@let
                    val relativePath = PathUtil.relativizeToProject(project, file.path)
                    // A document change cannot be attributed to one session, so every active turn
                    // records it; each turn then filters its own diffs against that signal.
                    turns.values.forEach { it.userEditedFiles.add(relativePath) }
                    logger.info("[Turn ${turnNumbers()}] User edit detected: $relativePath")
                }
            }
        }
    }

    private val vfsListener = object : VirtualFileListener {
        override fun fileCreated(event: VirtualFileEvent) {
            if (!acceptsChange(event.file)) return
            onVfsChange(event.file)
            // Record creation event - will be cross-checked with serverEditedFiles later
            // to determine if it's truly AI-created or user-created
            val relPath = PathUtil.relativizeToProject(project, event.file.path)
            if (turns.isEmpty()) {
                lateAiCreatedFiles.add(relPath)
            } else {
                turns.values.forEach { it.aiCreatedFiles.add(relPath) }
            }
            logger.info("[Turn ${turnNumbers()}] VFS fileCreated: $relPath")
        }
        
        override fun beforeFileDeletion(event: VirtualFileEvent) {
            captureContentBeforeChange(event.file)
        }
        
        override fun beforeContentsChange(event: VirtualFileEvent) {
            captureContentBeforeChange(event.file)
        }
        
        override fun fileDeleted(event: VirtualFileEvent) {
            if (acceptsChange(event.file)) onVfsChange(event.file)
        }

        override fun contentsChanged(event: VirtualFileEvent) {
            if (acceptsChange(event.file)) onVfsChange(event.file)
        }

        override fun fileMoved(event: VirtualFileMoveEvent) {
            if (acceptsChange(event.file)) onVfsChange(event.file)
        }
        // fileCopied is rare for AI and caused compilation issues
    }

    /** Log-friendly numbers of the active turns (`-` when idle). */
    private fun turnNumbers(): String =
        turns.values.map { it.number }.sorted().joinToString(",").ifEmpty { "-" }

    internal fun captureContentBeforeChange(file: VirtualFile?) {
        if (file == null || !file.isValid || file.isDirectory) return
        if (!acceptsChange(file)) return
        if (file.fileType.isBinary) {
            logger.info("[Turn ${turnNumbers()}] Skipping binary file: ${file.path}")
            return
        }

        val relativePath = PathUtil.relativizeToProject(project, file.path)

        // Capture only for the turns that still need this file: we want the ORIGINAL state before
        // the first modification in the turn. Captures outside a turn are discarded, exactly like
        // the old "clear at turn start" behaviour.
        val pendingTurns = turns.values.filter { !it.capturedBeforeContent.containsKey(relativePath) }
        if (pendingTurns.isEmpty()) return

        fun record(content: String) {
            pendingTurns.forEach { it.capturedBeforeContent.putIfAbsent(relativePath, content) }
        }

        try {
            // Try VFS first
            val content = VfsUtil.loadText(file)
            record(content)
            logger.info("[Turn ${turnNumbers()}] Captured before content for: $relativePath (${content.length} chars)")
        } catch (e: Exception) {
            logger.warn("[Turn ${turnNumbers()}] VfsUtil load failed for $relativePath: ${e.message}. Trying java.io.File...")
            // Fallback to java.io.File
            try {
                val ioFile = File(file.path)
                if (ioFile.exists()) {
                    record(ioFile.readText())
                    logger.info("[Turn ${turnNumbers()}] Captured via java.io.File for: $relativePath")
                } else {
                    logger.warn("[Turn ${turnNumbers()}] File not found on disk: $relativePath. Trying LocalHistory...")
                    // Fallback to LocalHistory (Last known state provided by IDE)
                    // This handles external deletions where VFS event comes after physical deletion.
                    val bytes = LocalHistory.getInstance().getByteContent(file) { true }
                    if (bytes != null && bytes.isNotEmpty()) {
                        record(String(bytes, Charsets.UTF_8))
                        logger.info("[Turn ${turnNumbers()}] Captured via LocalHistory (Latest) for: $relativePath")
                    } else {
                        logger.warn("[Turn ${turnNumbers()}] LocalHistory lookup returned null or empty for $relativePath")
                    }
                }
            } catch (ioe: Exception) {
                logger.error("[Turn ${turnNumbers()}] Failed to capture content for $relativePath", ioe)
            }
        }
    }

    private fun onVfsChange(file: VirtualFile?) {
        if (file == null) return
        val relativePath = PathUtil.relativizeToProject(project, file.path)
        if (turns.isEmpty()) {
            // Gap: remember it so the next turn's baseline includes the change.
            if (gapChangedFiles.add(relativePath)) {
                lateVfsChangedFiles.add(relativePath)
                logger.info("[Turn -] Detected VFS change outside a turn: $relativePath")
            }
            return
        }
        turns.values.forEach { turn ->
            if (turn.vfsChangedFiles.add(relativePath)) {
                logger.info("[Turn #${turn.number}] Detected VFS change: $relativePath")
            }
        }
    }

    // ==================== Turn Lifecycle ====================

    /**
     * Called when a session becomes busy. Starts a turn for [sessionId].
     *
     * @return true when a new turn was started; false when that session already has one running
     */
    open fun onTurnStart(sessionId: String): Boolean {
        if (turns.containsKey(sessionId)) {
            logger.info("[Session $sessionId] onTurnStart: already busy, ignored")
            return false
        }

        // Gap state synchronization: files changed while no turn was active become the baseline of
        // the new turn (startOfTurnKnownState then includes user edits made during the gap).
        if (gapChangedFiles.isNotEmpty()) {
            val gap = gapChangedFiles.toList()
            logger.info("Syncing ${gap.size} gap-changed files to known state")
            updateKnownState(gap)
            gapChangedFiles.removeAll(gap.toSet())
        }
        lateVfsChangedFiles.clear()
        lateAiCreatedFiles.clear()

        val number = turnCounter.incrementAndGet()
        val state = TurnState(number, createSystemLabel("OpenCode Turn #$number Start"))
        state.knownAtStart = HashMap(lastKnownFileStates)
        turns[sessionId] = state

        logger.info("[Turn #$number] STARTED (session $sessionId)")
        return true
    }

    /**
     * Called when a session finishes working. Returns the snapshot of that session's turn.
     *
     * @return the state needed for diff processing, or null when [sessionId] had no active turn
     */
    open fun onTurnEnd(sessionId: String): TurnSnapshot? {
        val state = turns.remove(sessionId)
        if (state == null) {
            logger.warn("[Session $sessionId] onTurnEnd: no active turn, ignored")
            return null
        }

        // Update lastKnownFileStates with the current content of the files this turn touched, so
        // the NEXT turn can use it as its 'Before' state (especially after a deletion, where
        // LocalHistory may have nothing).
        (state.vfsChangedFiles + state.serverEditedFiles + state.aiCreatedFiles).forEach { relativePath ->
            updateKnownState(listOf(relativePath))
        }

        val snapshot = TurnSnapshot(
            turnNumber = state.number,
            vfsChangedFiles = state.vfsChangedFiles.toSet(),
            serverEditedFiles = state.serverEditedFiles.toSet(),
            aiCreatedFiles = state.aiCreatedFiles.toSet(),
            userEditedFiles = state.userEditedFiles.toSet(),
            baselineLabel = state.label,
            knownFileStates = state.knownAtStart,
            capturedBeforeContent = HashMap(state.capturedBeforeContent)
        )

        logger.info(
            "[Turn #${state.number}] ENDED (session $sessionId): " +
                "vfs=${snapshot.vfsChangedFiles.size}, server=${snapshot.serverEditedFiles.size}, " +
                "captured=${snapshot.capturedBeforeContent.size}"
        )
        return snapshot
    }

    /**
     * Called when OpenCode edits a file (from the `session.step.ended` SSE event's `files[]`).
     */
    fun onFileEdited(sessionId: String, filePath: String) {
        if (!isOwnedChange(filePath)) {
            logger.info("[Scope] Ignoring server-reported edit outside the project: $filePath")
            return
        }
        val relativePath = PathUtil.relativizeToProject(project, filePath)
        val state = turns[sessionId]
        if (state == null) {
            logger.debug("[Session $sessionId] Server reported an edit outside a turn: $relativePath")
            return
        }
        state.serverEditedFiles.add(relativePath)
        logger.info("[Turn #${state.number}] Server reported edit: $relativePath")
    }

    init {
        // Skip listener registration in test mode if Application is not loaded
        if (ApplicationManager.getApplication() != null) {
            EditorFactory.getInstance().eventMulticaster.addDocumentListener(documentListener, this)
            VirtualFileManager.getInstance().addVirtualFileListener(vfsListener, this)
        }
    }

    // ==================== Test Hooks ====================
    
    protected open fun createSystemLabel(name: String): Label? {
        return try {
            LocalHistory.getInstance().putSystemLabel(project, name)
        } catch (e: Exception) {
            // Fallback for tests or when LocalHistory is unavailable
            null
        }
    }



    // ==================== Diff Processing ====================

    /**
     * Main entry point for processing diffs.
     * Handles Rescue, Resolution, and Filtering in one place (Single Responsibility).
     * @param extraVfsEvents Additional VFS events (e.g. from post-turn refresh) to consider for affinity.
     * @param extraAiCreatedFiles Additional AI created files (from post-turn refresh).
     */
    fun getProcessedDiffs(
        serverDiffs: List<FileDiff>, 
        snapshot: TurnSnapshot, 
        extraVfsEvents: Set<String> = emptySet(),
        extraAiCreatedFiles: Set<String> = emptySet()
    ): List<DiffEntry> {
        logger.info("[Turn #${snapshot.turnNumber}] Processing diffs (Server: ${serverDiffs.size})")
        logger.info("[Turn #${snapshot.turnNumber}] Snapshot state:")
        logger.info("[Turn #${snapshot.turnNumber}]   vfsChangedFiles: ${snapshot.vfsChangedFiles}")
        logger.info("[Turn #${snapshot.turnNumber}]   serverEditedFiles: ${snapshot.serverEditedFiles}")
        logger.info("[Turn #${snapshot.turnNumber}]   aiCreatedFiles: ${snapshot.aiCreatedFiles}")
        logger.info("[Turn #${snapshot.turnNumber}]   userEditedFiles: ${snapshot.userEditedFiles}")
        logger.info("[Turn #${snapshot.turnNumber}]   capturedBeforeContent keys: ${snapshot.capturedBeforeContent.keys}")
        logger.info("[Turn #${snapshot.turnNumber}]   extraVfsEvents: $extraVfsEvents")
        logger.info("[Turn #${snapshot.turnNumber}]   extraAiCreatedFiles: $extraAiCreatedFiles")

        // The v2 wire carries no "after" content, so the working tree is the source of truth.
        // Read each file at most once per pipeline run.
        val afterByFile = HashMap<String, String>()
        fun resolveAfter(file: String): String = afterByFile.getOrPut(file) { deriveAfter(file) }

        // Files a git operation rewrote are not agent edits. The server may still report them (its
        // diff is a plain working-tree comparison), so they are dropped before anything else.
        val gitTouchedKeys = changeScope.gitTouchedPaths()
            .map { PathUtil.relativizeToProject(project, it) }
            .toSet()

        val scopedServerDiffs = serverDiffs.filter { diff ->
            when {
                !isOwnedChange(diff.file) -> {
                    logger.info("[Scope] ${diff.file} -> SKIP: outside this project")
                    false
                }
                diff.file in gitTouchedKeys -> {
                    logger.info("[Git] ${diff.file} -> SKIP: rewritten by a git operation")
                    false
                }
                else -> true
            }
        }

        // 0. Pre-Filter: Detect and discard "Stale No-Op" Server Diffs
        // If Server returns a diff that results in NO CHANGE (Before == After),
        // but VFS detected a physical change, it means the Server Diff is stale/wrong.
        // We discard it so that the Rescue logic (Step 1) can generate a correct Synthetic Diff from disk.
        val validServerDiffs = scopedServerDiffs.filter { diff ->
            val file = diff.file
            val isVfsTouched = file in snapshot.vfsChangedFiles || 
                              file in snapshot.aiCreatedFiles || 
                              file in snapshot.capturedBeforeContent.keys ||
                              file in extraVfsEvents ||
                              file in extraAiCreatedFiles

            if (!isVfsTouched) return@filter true // Keep purely server-side diffs (no VFS conflict)

            // Resolve before content using the diff as hint
            val before = resolveBeforeContent(file, diff, snapshot)
            val after = resolveAfter(file)

            if (before != null && before == after) {
                logger.warn("[PreFilter] $file -> DISCARD: Server diff implies no change (len=${before.length}), but VFS changed. Triggering Rescue.")
                return@filter false
            }
            true
        }

        // 1. VFS Rescue: Find files changed/deleted locally but missed by Server
        // Only rescue files that have STRONG AI affinity (serverEditedFiles) or physical deletion
        val rescueCandidates = snapshot.vfsChangedFiles + snapshot.capturedBeforeContent.keys + extraVfsEvents
        val serverFileNames = validServerDiffs.map { it.file }.toSet()

        val vfsMissing = rescueCandidates.filter { vfsFile ->
            // Skip if Server already returned this file
            if (vfsFile in serverFileNames) {
                logger.info("[Rescue] $vfsFile -> SKIP: Already in server diffs")
                return@filter false
            }

            // Files outside the project and files a git operation rewrote are never agent edits.
            if (!isOwnedChange(vfsFile)) {
                logger.info("[Rescue] $vfsFile -> SKIP: Outside this project")
                return@filter false
            }
            if (vfsFile in gitTouchedKeys) {
                logger.info("[Rescue] $vfsFile -> SKIP: Rewritten by a git operation")
                return@filter false
            }

            // Skip IDE internal files
            if (vfsFile.startsWith(".idea/") || vfsFile.contains("idea-sandbox")) {
                logger.info("[Rescue] $vfsFile -> SKIP: IDE internal file")
                return@filter false
            }

            // CRITICAL: Skip if user edited this file - user operations should NOT be rescued as AI diffs
            if (vfsFile in snapshot.userEditedFiles) {
                logger.info("[Rescue] $vfsFile -> SKIP: User edited this file")
                return@filter false
            }

            val absPath = PathUtil.resolveProjectPath(project, vfsFile) ?: return@filter false
            val exists = File(absPath).exists()

            // Determine if this is truly an AI operation by checking various signals
            val isServerClaimed = vfsFile in snapshot.serverEditedFiles
            val isVfsCreated = vfsFile in snapshot.aiCreatedFiles || vfsFile in extraAiCreatedFiles
            // Check if we can resolve the before content (from Capture, LocalHistory, or KnownState)
            val hasCapturedBefore = getSnapshotBeforeContent(vfsFile, snapshot) != null

            // Rescue conditions (conservative, requires evidence of AI involvement):
            //
            // For DELETION (!exists):
            //   - If we have capturedBeforeContent, we can safely show the diff
            //     (we know what the original content was, so Reject can restore it)
            //   - OR if Server claimed it via the session.step.ended SSE event
            //
            // For CREATION (exists && isVfsCreated):
            //   - MUST have Server claim (session.step.ended files[]) to distinguish from user-created files
            //   - VFS alone cannot tell us if AI or User created the file
            //
            val shouldRescue = when {
                // Case 1: Deletion with captured content - safe to rescue
                !exists && hasCapturedBefore -> {
                    logger.info("[Rescue] $vfsFile -> RESCUE: Deleted + Has captured content (${snapshot.capturedBeforeContent[vfsFile]?.length} chars)")
                    true
                }
                // Case 2: Deletion with Server claim - safe to rescue
                !exists && isServerClaimed -> {
                    logger.info("[Rescue] $vfsFile -> RESCUE: Deleted + Server claimed")
                    true
                }
                // Case 3: Creation with BOTH VFS and Server claim - safe to rescue
                exists && isVfsCreated && isServerClaimed -> {
                    logger.info("[Rescue] $vfsFile -> RESCUE: Created (VFS) + Server claimed")
                    true
                }
                // Case 4: Modification (Exists + Server Claimed + NOT Created)
                // This covers the case where Server edited the file, we saw the VFS change, 
                // but Server API failed to return a diff.
                exists && !isVfsCreated && isServerClaimed -> {
                     logger.info("[Rescue] $vfsFile -> RESCUE: Modified + Server claimed")
                     true
                }
                // Default: Not enough evidence, skip
                else -> {
                    logger.info("[Rescue] $vfsFile -> SKIP: Insufficient AI affinity (exists=$exists, serverClaimed=$isServerClaimed, vfsCreated=$isVfsCreated, hasCaptured=$hasCapturedBefore)")
                    false
                }
            }
            shouldRescue
        }.toSet()

        logger.info("[Turn #${snapshot.turnNumber}] Rescue candidates: $vfsMissing")

        val allDiffs = validServerDiffs + vfsMissing.mapNotNull { createSyntheticDiff(it, snapshot) }
        
        if (allDiffs.isEmpty()) {
            logger.info("[Turn #${snapshot.turnNumber}] No diffs after rescue.")
            return emptyList()
        }

        // 2. Resolve Content & Create Entries
        val entries = allDiffs.map { diff ->
            val resolvedBefore = resolveBeforeContent(diff.file, diff, snapshot)
            DiffEntry(
                file = diff.file,
                diff = diff,
                hasUserEdits = diff.file in snapshot.userEditedFiles,
                isCreatedExplicitly = diff.file in snapshot.aiCreatedFiles,
                resolvedBefore = resolvedBefore,
                resolvedAfter = resolveAfter(diff.file)
            )
        }

        // 3. Filtering (Safety, Content & Server Consistency)
        val filteredEntries = entries.filter { entry ->
            val file = entry.file
            val before = entry.beforeContent
            val after = entry.resolvedAfter

            // A. User Safety (Highest Priority)
            // If the user edited the file during this turn, we MUST NOT show the AI's diff
            // to avoid overwriting user work or showing confusing conflict states.
            if (entry.hasUserEdits) {
                logger.info("[DiffFilter] $file -> SKIP: User edited this file.")
                return@filter false
            }

            // A.2 Binary files: a text diff would show decoded bytes as garbage.
            val absolutePath = absolutePathOf(file)
            if (absolutePath != null && isBinaryFile(absolutePath)) {
                logger.info("[DiffFilter] $file -> SKIP: Binary file.")
                return@filter false
            }

            // B. Server Consistency Check (Prevent Stale Diffs)
            // Server API may return stale/cached diffs from previous turns.
            // CRITICAL RULE: Diffs from Server API MUST have a corresponding session.step.ended event OR be a VFS Rescue.
            //
            // Why: serverEditedFiles contains real-time SSE events for THIS turn.
            // If Server API returns a diff without SSE claim, it's stale data from a previous turn.
            //
            // Exception: Rescued diffs (Synthetic Diffs) which we generated locally.
            // We can identify these by checking if the file is NOT in the original serverDiffs list.
            // BUT: Ideally we should just check if it was touched by VFS or Server in THIS turn.
            
            // Check for "Signal Affinity": Did this file have any activity in this turn?
            val isServerClaimed = file in snapshot.serverEditedFiles
            val isVfsTouched = file in snapshot.vfsChangedFiles || 
                              file in snapshot.aiCreatedFiles || 
                              file in snapshot.capturedBeforeContent.keys ||
                              file in extraVfsEvents
            
            if (!isServerClaimed && !isVfsTouched) {
                logger.info("[DiffFilter] $file -> SKIP: Stale API diff (No SSE or VFS signal in this turn).")
                return@filter false
            }

            // C. Content Filter (Ghost Diffs)
            // If the resolved content is identical, there's nothing to accept/reject.
            // This filters out "noise" where VFS triggered but content didn't actually change,
            // or where Server sent a diff that matches current state.
            if (before == after) {
                logger.info("[DiffFilter] $file -> SKIP: Identical content (len=${before.length}).")
                return@filter false
            }

            logger.info("[DiffFilter] $file -> SHOW: Changed (B:${before.length} -> A:${after.length}, serverClaimed=$isServerClaimed, vfsTouched=$isVfsTouched)")
            true
        }

        // 4. Store Pending
        filteredEntries.forEach { pendingDiffs[it.file] = it }
        
        return filteredEntries
    }

    /**
     * Create a synthetic FileDiff for a file that VFS detected but Server didn't return.
     * This is the "Rescue" mechanism for files that the Server API missed.
     *
     * v2 carries no before/after content and no patch for rescued files, so the returned diff is
     * classified from `File.exists()` (+ VFS creation evidence) and content is re-derived by the
     * caller from local snapshots and the working tree.
     */
    private fun createSyntheticDiff(relativePath: String, snapshot: TurnSnapshot): FileDiff? {
        logger.info("[SyntheticDiff] Creating for: $relativePath")

        val absPath = PathUtil.resolveProjectPath(project, relativePath)
        if (absPath == null) {
            logger.info("[SyntheticDiff] $relativePath -> SKIP: Cannot resolve path")
            return null
        }

        val file = File(absPath)
        val fileExists = file.exists()

        val afterContent = deriveAfter(relativePath)

        var beforeContent = getSnapshotBeforeContent(relativePath, snapshot)
        logger.info("[SyntheticDiff] $relativePath -> beforeContent from snapshot: ${beforeContent?.let { "${it.length} chars" } ?: "null"}")

        if (beforeContent == null) {
            // Determine what to do based on the scenario
            val isServerClaimed = relativePath in snapshot.serverEditedFiles
            val isVfsCreated = relativePath in snapshot.aiCreatedFiles

            when {
                // New file created by AI (VFS + Server confirm): before should be empty
                isVfsCreated && isServerClaimed -> {
                    logger.info("[SyntheticDiff] $relativePath -> New file (VFS + Server), before = empty")
                    beforeContent = ""
                }
                // File deleted but no captured content: we CANNOT rescue safely
                // because we don't know what the original content was.
                // NOTE: This case should NOT happen if Rescue logic is correct,
                // because we only rescue deletions when we HAVE captured content.
                !fileExists -> {
                    logger.warn("[SyntheticDiff] $relativePath -> SKIP: Deleted but no captured before content (this should not happen)")
                    return null
                }
                // File exists but we don't know the before state: skip to be safe
                else -> {
                    logger.info("[SyntheticDiff] $relativePath -> SKIP: Unknown before state (exists=$fileExists, serverClaimed=$isServerClaimed, vfsCreated=$isVfsCreated)")
                    return null
                }
            }
        }

        val finalBefore = beforeContent

        // Final sanity check: if before == after, no point creating this diff
        if (finalBefore == afterContent) {
            logger.info("[SyntheticDiff] $relativePath -> SKIP: before == after (no change)")
            return null
        }

        // Classify the change: the server never reported this file, so derive the status from disk.
        // "added" only when the file is VFS-new AND nothing existed before it, so that the status
        // stays consistent with `resolveBeforeContent` (a Replace must remain a modification).
        val status = when {
            !fileExists -> "deleted"
            finalBefore.isEmpty() && relativePath in snapshot.aiCreatedFiles -> "added"
            else -> "modified"
        }

        logger.info("[SyntheticDiff] $relativePath -> CREATED: status=$status, before=${finalBefore.length} chars, after=${afterContent.length} chars")
        // No patch and no line counts exist for a locally rescued change; content is re-derived
        // from the snapshot and the working tree by `getProcessedDiffs`.
        return FileDiff(relativePath, "", 0, 0, status)
    }

    /**
     * Process incoming diffs using a turn snapshot.
     *
     * Kept as a thin test/back-compat seam over [getProcessedDiffs]; production code calls
     * [getProcessedDiffs] directly.
     */
    @VisibleForTesting
    fun processDiffs(serverDiffs: List<FileDiff>, snapshot: TurnSnapshot): List<DiffEntry> {
        return getProcessedDiffs(serverDiffs, snapshot)
    }

    /**
     * Try to retrieve the "Before" content from the snapshot using all available sources.
     * Order:
     * 1. Pre-emptively Captured Content (Physical)
     * 2. LocalHistory Baseline (Time-based)
     * 3. Known State (Memory)
     */
    fun getSnapshotBeforeContent(relativePath: String, snapshot: TurnSnapshot): String? {
        val absPath = PathUtil.resolveProjectPath(project, relativePath)

        // 1. Captured Content (Highest Priority)
        snapshot.capturedBeforeContent[relativePath]?.let {
            return it
        }
        
        // Note: We deliberately DO NOT check live capturedBeforeContent here.
        // Isolation requires we stick to the snapshot. Late events belong to the next turn.

        // 2. LocalHistory Baseline
        if (snapshot.baselineLabel != null && absPath != null) {
            try {
                snapshot.baselineLabel.getByteContent(absPath)?.let {
                    return String(it.bytes, Charsets.UTF_8)
                }
            } catch (e: Exception) {
                logger.info("[Turn #${snapshot.turnNumber}] LocalHistory lookup failed for $relativePath: ${e.message}")
            }
        }

        // 3. Known State
        return snapshot.knownFileStates[relativePath]
    }
    
    /**
     * VFS changes seen after the last turn ended (late refresh hints).
     *
     * Production reads the per-turn snapshot for turn data; these sets only carry the signals that
     * arrive once the turn is already closed.
     */
    fun getLiveVfsChangedFiles(): Set<String> = lateVfsChangedFiles.toSet()

    fun getLiveAiCreatedFiles(): Set<String> = lateAiCreatedFiles.toSet()
    
    fun getKnownFilePaths(): Set<String> {
        return lastKnownFileStates.keys.filter { isOwnedChange(it) }.toSet()
    }

    fun updateKnownState(files: List<String>) {
        files.forEach { rawPath ->
            if (!isOwnedChange(rawPath)) {
                logger.debug("[Scope] Ignoring known state outside the project: $rawPath")
                return@forEach
            }
            val absPath = PathUtil.resolveProjectPath(project, rawPath)
            if (absPath != null) {
                val file = File(absPath)
                if (file.exists() && file.isFile) {
                    val relativePath = PathUtil.relativizeToProject(project, absPath)
                    try {
                        val content = file.readText()
                        // Ensure we store using the canonical relative path key
                        lastKnownFileStates[relativePath] = content
                        logger.info("[Turn ${turnNumbers()}] Updated known state (late refresh) for $relativePath")
                    } catch (e: Exception) {
                        logger.warn("[Turn ${turnNumbers()}] Failed to update known state for $relativePath: ${e.message}")
                    }
                }
            }
        }
    }

    /**
     * Resolve the "before" content for a file using a snapshot's baseline.
     *
     * v2 carries no `before` content on the wire, so the previous state can only come from local
     * snapshots:
     * 1. `status == "added"` -> the file did not exist before this turn, so before is empty.
     * 2. Captured content / LocalHistory baseline / known state.
     * 3. `status == "deleted"` with nothing captured -> the file may still be on disk in a race, read it.
     * 4. Otherwise nothing is known about the previous state -> empty.
     */
    fun resolveBeforeContent(relativePath: String, diff: FileDiff, snapshot: TurnSnapshot): String? {
        val absPath = PathUtil.resolveProjectPath(project, relativePath)

        // 1. Server status says the file was created in this turn: nothing existed before it.
        if (diff.newFile) {
            logger.info("[Turn #${snapshot.turnNumber}] Server status=added: $relativePath. Forcing before to empty.")
            return ""
        }

        // 2. Try to get content from snapshot sources (Capture, LocalHistory, KnownState)
        getSnapshotBeforeContent(relativePath, snapshot)?.let {
            logger.info("[Turn #${snapshot.turnNumber}] Resolved before content for $relativePath from snapshot (${it.length} chars)")
            return it
        }

        // 3. Deleted with no captured content: read the disk only when the file is still present.
        if (diff.deleted && absPath != null) {
            try {
                val file = File(absPath)
                if (file.exists()) {
                    logger.info("[Turn #${snapshot.turnNumber}] Server status=deleted but file still on disk. Using disk content as before.")
                    return file.readText()
                }
            } catch (e: Exception) {
                logger.warn("[Turn #${snapshot.turnNumber}] Failed to read disk fallback for $relativePath", e)
            }
        }

        // 4. Nothing is known about the previous state. Return null (not "") so callers can tell
        // "unknown" apart from a legitimately empty file and refuse destructive operations.
        logger.info("[Turn #${snapshot.turnNumber}] No before content could be resolved for $relativePath")
        return null
    }

    /**
     * Read a file's current on-disk content, the v2 source of truth for the diff "after" side.
     *
     * @return the file content, or an empty string when the file is missing or unreadable
     *         (a deleted file therefore reports an empty "after").
     */
    private fun deriveAfter(relativePath: String): String {
        val absPath = PathUtil.resolveProjectPath(project, relativePath) ?: return ""
        val file = File(absPath)
        if (!file.exists() || !file.isFile) return ""
        return try {
            file.readText()
        } catch (e: Exception) {
            logger.warn("[SessionManager] Failed to read disk content for $relativePath: ${e.message}")
            ""
        }
    }

    // ==================== Accept/Reject Operations ====================

    /**
     * Accept a diff: stage the file via Git.
     */
    fun acceptDiff(entry: DiffEntry, onComplete: ((Boolean) -> Unit)? = null) {
        val path = entry.file
        logger.info("[SessionManager] Accept: $path")

        AppExecutorUtil.getAppExecutorService().submit {
            var success = false
            try {
                val cmd = GeneralCommandLine("git", "add", path)
                    .withWorkDirectory(project.basePath)
                val handler = OSProcessHandler(cmd)
                handler.startNotify()
                val finished = handler.waitFor(5000)
                val exitCode = handler.exitCode ?: -1
                success = finished && exitCode == 0
                
                if (success) {
                    pendingDiffs.remove(path)
                    logger.info("[SessionManager] Accepted: $path")
                } else {
                    // Special case: File deleted and likely untracked (git add fails with 128)
                    // If the file is gone from disk, we can consider the "delete" accepted.
                    val absPath = PathUtil.resolveProjectPath(project, path)
                    if (exitCode == 128 && absPath != null && !File(absPath).exists()) {
                        logger.info("[SessionManager] Git add failed (128) but file is deleted. Treating as success.")
                        pendingDiffs.remove(path)
                        success = true // Mark as success for callback
                    } else {
                        logger.warn("[SessionManager] Git add failed: exitCode=$exitCode")
                    }
                }
            } catch (e: Exception) {
                logger.error("[SessionManager] Accept failed: $path", e)
            }

            onComplete?.let { cb ->
                ApplicationManager.getApplication().invokeLater { cb(success) }
            }
        }
    }

    /**
     * Reject a diff: restore file to its original state.
     */
    fun rejectDiff(entry: DiffEntry, onComplete: ((Boolean) -> Unit)? = null) {
        val path = entry.file
        val absPath = PathUtil.resolveProjectPath(project, path)
        
        if (absPath == null) {
            logger.warn("[SessionManager] Reject: cannot resolve path: $path")
            onComplete?.let { ApplicationManager.getApplication().invokeLater { it(false) } }
            return
        }

        // Use the entry's resolved before content
        val beforeContent = entry.beforeContent
        logger.info("[SessionManager] Reject: $path (restore ${beforeContent.length} chars)")

        ApplicationManager.getApplication().invokeLater {
            var success = false
            runWriteAction {
                try {
                    val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(absPath)
                    
                    when {
                        vf != null && entry.isNewFile -> {
                            vf.delete(this@SessionManager)
                            success = true
                            logger.info("[SessionManager] Rejected (deleted new file): $path")
                        }
                        vf != null -> {
                            // Safety: never truncate an existing, non-empty file when the "before"
                            // content is unknown (resolvedBefore == null). Writing "" here would
                            // destroy real on-disk content, so refuse and leave the file untouched.
                            val onDisk = try {
                                String(vf.contentsToByteArray(), Charsets.UTF_8)
                            } catch (e: Exception) {
                                logger.warn("[SessionManager] Reject: could not read $path to check for data loss", e)
                                null
                            }
                            if (entry.resolvedBefore == null && onDisk?.isNotEmpty() == true) {
                                logger.warn(
                                    "[SessionManager] Reject REFUSED for $path: before content is unknown and the " +
                                        "file holds ${onDisk.length} chars; writing an empty string would lose data."
                                )
                            } else {
                                VfsUtil.saveText(vf, beforeContent)
                                success = true
                                logger.info("[SessionManager] Rejected (restored): $path")
                            }
                        }
                        beforeContent.isNotEmpty() -> {
                            val file = File(absPath)
                            file.parentFile?.mkdirs()
                            file.writeText(beforeContent)
                            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
                            success = true
                            logger.info("[SessionManager] Rejected (recreated): $path")
                        }
                        else -> {
                            success = true
                            logger.info("[SessionManager] Rejected (no-op): $path")
                        }
                    }

                    if (success) {
                        pendingDiffs.remove(path)
                        createSystemLabel("OpenCode Rejected: $path")
                        
                        // Update Known State for next turn safety
                        if (entry.isNewFile) {
                            lastKnownFileStates.remove(path)
                        } else {
                            lastKnownFileStates[path] = beforeContent
                        }
                    }
                } catch (e: Exception) {
                    logger.error("[SessionManager] Reject failed: $path", e)
                }
            }

            onComplete?.invoke(success)
        }
    }

    // ==================== Queries ====================

    fun getDiffForFile(path: String): DiffEntry? {
        val relativePath = PathUtil.relativizeToProject(project, path)
        return pendingDiffs[relativePath]
    }

    /**
     * Every diff the user has not accepted or rejected yet, in a stable (path) order.
     *
     * The order matters: the diff viewer pages through this list by index, and a raw
     * `ConcurrentHashMap` iteration order made "next" jump to an arbitrary entry.
     */
    fun getAllDiffEntries(): List<DiffEntry> =
        pendingDiffs.values.filter { isOwnedChange(it.file) }.sortedBy { it.file }

    // ==================== Lifecycle ====================

    fun clear() {
        turns.clear()
        gapChangedFiles.clear()
        lateVfsChangedFiles.clear()
        lateAiCreatedFiles.clear()
        pendingDiffs.clear()
        lastKnownFileStates.clear()
        changeScope.clear()
        turnCounter.set(0)
    }

    override fun dispose() {
        clear()
    }
}

/**
 * Immutable snapshot of a turn's state, captured at turn end.
 * Used to ensure diff processing uses the correct turn's data even if a new turn starts.
 */
data class TurnSnapshot(
    val turnNumber: Int,
    val vfsChangedFiles: Set<String>,
    val serverEditedFiles: Set<String>,
    val aiCreatedFiles: Set<String>,
    val userEditedFiles: Set<String>,
    val baselineLabel: Label?,
    val knownFileStates: Map<String, String> = emptyMap(),
    val capturedBeforeContent: Map<String, String> = emptyMap()
)
