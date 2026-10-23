package com.zhucanle.opencodev2.ide

import com.zhucanle.opencodev2.ide.api.OpenCodeApiClient
import com.zhucanle.opencodev2.ide.api.SseAuthException
import com.zhucanle.opencodev2.ide.api.SseEventListener
import com.zhucanle.opencodev2.ide.api.models.*
import com.zhucanle.opencodev2.ide.diff.DiffViewerService
import com.zhucanle.opencodev2.ide.session.SessionManager
import com.zhucanle.opencodev2.ide.session.TurnSnapshot
import com.zhucanle.opencodev2.ide.terminal.OpenCodeTerminalFileEditor
import com.zhucanle.opencodev2.ide.terminal.OpenCodeTerminalFileEditorProvider
import com.zhucanle.opencodev2.ide.terminal.OpenCodeTerminalLinkFilter
import com.zhucanle.opencodev2.ide.terminal.OpenCodeTerminalVirtualFile
import com.zhucanle.opencodev2.ide.terminal.TerminalTextWriter
import com.zhucanle.opencodev2.ide.ui.OpenCodeConnectDialog
import com.zhucanle.opencodev2.ide.util.OpenCodeCliResolver
import com.zhucanle.opencodev2.ide.util.OpenCodeCommand
import com.zhucanle.opencodev2.ide.util.OpenCodeServiceDiscovery
import com.zhucanle.opencodev2.ide.util.PathUtil
import com.zhucanle.opencodev2.ide.util.PortFinder
import com.zhucanle.opencodev2.ide.util.ReconnectPolicy
import com.zhucanle.opencodev2.ide.util.ServerAuth
import com.zhucanle.opencodev2.ide.util.ServiceEndpoint
import com.zhucanle.opencodev2.ide.web.OpenCodeWebVirtualFile
import com.zhucanle.opencodev2.ide.web.OpenCodeWebFileEditorProvider
import com.zhucanle.opencodev2.ide.web.WebModeSupport

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.notification.Notification
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.ui.SystemNotifications
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.AppExecutorUtil

import org.jetbrains.plugins.terminal.TerminalProjectOptionsProvider
import org.jetbrains.plugins.terminal.TerminalView

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@Service(Service.Level.PROJECT)
class OpenCodeService(private val project: Project) : Disposable {

    private val logger = Logger.getInstance(OpenCodeService::class.java)

    private enum class ConnectionMode { NONE, TERMINAL, WEB, REMOTE }

    /** UI transition a launch request ([focusOrCreateTerminal]) must perform. */
    internal enum class LaunchAction {
        /** Connected and the tab is open: bring it to front. */
        FOCUS_UI,

        /** Nothing to do: the tab is already open and the request is not interactive. */
        NO_OP,

        /** Alive endpoint: offer to restore its UI or start over. */
        ASK_RESTORE,

        /** Dead endpoint: offer to restart the server or start over. */
        ASK_RESTART,

        /** Dead or alive, the remembered mode can be restored without asking. */
        RESTORE_UI,

        /** No usable endpoint: ask the user to connect. */
        SHOW_CONNECT_DIALOG
    }

    companion object {
        /**
         * Decides what a launch request does, given the state the liveness probe reported.
         *
         * Pure so it can be unit-tested: production evaluates it on the EDT after the blocking
         * probe has run on a worker thread. `hasMode` keeps a remembered endpoint without a
         * remembered mode (after [disconnectAndReset]) from being "restored" into nothing.
         */
        internal fun launchAction(
            interactive: Boolean,
            connected: Boolean,
            uiOpen: Boolean,
            hasEndpoint: Boolean,
            hasMode: Boolean,
            running: Boolean
        ): LaunchAction = when {
            connected && uiOpen -> if (interactive) LaunchAction.FOCUS_UI else LaunchAction.NO_OP
            !hasEndpoint || !hasMode -> LaunchAction.SHOW_CONNECT_DIALOG
            running -> if (interactive) LaunchAction.ASK_RESTORE else LaunchAction.RESTORE_UI
            else -> if (interactive) LaunchAction.ASK_RESTART else LaunchAction.SHOW_CONNECT_DIALOG
        }
        private const val OPEN_CODE_TAB_PREFIX = "OpenCode"
        private const val RETRY_INTERVAL_MS = 5000L
        private const val BARRIER_TIMEOUT_MS = 2000L
        /** Major opencode version this plugin targets (v2). */
        private const val SUPPORTED_MAJOR_VERSION = 2
        /** Readiness poll for the v2 background service: 10 short tries against `/api/info`. */
        private const val READINESS_ATTEMPTS = 10
        private const val READINESS_DELAY_MS = 300L
        internal var DEBOUNCE_MS = 1500L
        /** Hosts treated as "local": their endpoint is service-managed and never dialog-typed. */
        private val LOCAL_HOSTS = listOf("0.0.0.0", "127.0.0.1", "localhost")


        /** Title/content of the notification shown when a turn ends. */
        internal data class TurnOutcomeNotification(val title: String, val content: String, val warning: Boolean)

        /**
         * Reads the terminal shell path, but only off the EDT.
         *
         * `TerminalProjectOptionsProvider.getShellPath()` blocks (`runBlockingMaybeCancellable`)
         * since IntelliJ 2026.2 and throws "This method is forbidden on EDT" when called there, so
         * the provider must never be touched on the dispatch thread (issue #3). A failed read is
         * not fatal: `detectShell(null)` falls back to the form every shell accepts.
         *
         * @param onEdt whether the caller runs on the Event Dispatch Thread
         * @param read the provider call; not invoked when [onEdt] is true
         * @return the shell path, or null when it cannot be read safely
         */
        internal fun readShellPathSafely(onEdt: Boolean, read: () -> String?): String? =
            if (onEdt) null else runCatching(read).getOrNull()

        /**
         * Derives the turn-end notification from the outcome (pure; unit-testable).
         * `succeeded` keeps the familiar informational "Task Completed" message; every other outcome
         * produces a warning that names the outcome and, when present, the provider error.
         */
        internal fun turnOutcomeNotification(outcome: String, errorMessage: String?): TurnOutcomeNotification =
            if (outcome == "succeeded") {
                TurnOutcomeNotification(
                    "OpenCode Task Completed",
                    "Session is now idle. Checking for changes...",
                    warning = false
                )
            } else {
                val detail = errorMessage?.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
                TurnOutcomeNotification(
                    "OpenCode Turn $outcome",
                    "The turn ended with outcome '$outcome'$detail",
                    warning = true
                )
            }

        /**
         * Resolves the credentials for a connection attempt.
         *
         * Local hosts are service-managed: their password always comes from the discovered v2
         * service endpoint (via [OpenCodeServiceDiscovery]) and [dialogPassword] is ignored — a
         * dialog-typed password must never authenticate against a local service. Remote hosts use
         * the dialog-typed [dialogPassword].
         *
         * @param discover lazily resolves the local endpoint; only invoked for local hosts.
         */
        internal fun resolveConnectionAuth(
            local: Boolean,
            dialogPassword: String?,
            discover: () -> ServiceEndpoint?
        ): ServerAuth =
            if (local) ServerAuth(password = discover()?.password)
            else ServerAuth(password = dialogPassword)
    }

    private var hostname: String = "127.0.0.1"
    private var port: Int? = null
    private var username: String? = null
    private var password: String? = null
    private var apiClient: OpenCodeApiClient? = null
    private var sseListener: SseEventListener? = null
    private val isConnected = AtomicBoolean(false)
    private val isConnecting = AtomicBoolean(false)
    private var lastMode: ConnectionMode = ConnectionMode.NONE
    private var wasEverConnected = false
    /** Decides retry / rediscover / ask-user after repeated health-check failures. */
    private val reconnectPolicy = ReconnectPolicy()
    /** Guards against overlapping service rediscovery attempts. */
    private val rediscoveryInFlight = AtomicBoolean(false)
    /** Working directory chosen in the connect dialog, reused when the terminal is restarted. */
    private var activeBasePath: String? = null
    /** Guards the one-shot connect-time version-mismatch warning. */
    @Volatile private var versionWarningShown = false
    /** Guards the one-shot authentication-failure notification (connect probe + SSE handshake). */
    @Volatile private var authFailureNotified = false

    private var terminalVirtualFile: OpenCodeTerminalVirtualFile? = null
    private var terminalEditor: OpenCodeTerminalFileEditor? = null
    private var webVirtualFile: OpenCodeWebVirtualFile? = null

    private val connectionListeners = CopyOnWriteArrayList<(Boolean) -> Unit>()
    private var connectionManagerTask: ScheduledFuture<*>? = null
    @Volatile private var lastIdleNotification: Notification? = null
    
    // Turn state: keyed by sessionId
    private val turnMessageIds = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val turnSnapshots = java.util.concurrent.ConcurrentHashMap<String, TurnSnapshot>()
    private val turnIdleWaiting = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val turnBarrierTasks = java.util.concurrent.ConcurrentHashMap<String, ScheduledFuture<*>>()
    private val turnLastTriggerTimes = java.util.concurrent.ConcurrentHashMap<String, Long>()

    val sessionManager: SessionManager get() = _sessionManager ?: project.service()
    private val diffViewerService: DiffViewerService get() = _diffViewerService ?: project.service()

    // Test hooks
    private var _sessionManager: SessionManager? = null
    private var _diffViewerService: DiffViewerService? = null

    /**
     * Test seam: resolves the sink [pasteToTerminal] writes to. Production targets the live
     * terminal editor's TTY; tests substitute a fake [TerminalTextWriter].
     */
    internal var terminalTextWriterProvider: () -> TerminalTextWriter? = {
        terminalEditor?.let { editor -> TerminalTextWriter { editor.sendText(it) } }
    }

    internal fun setTestDeps(sm: SessionManager, dvs: DiffViewerService, client: OpenCodeApiClient) {
        _sessionManager = sm
        _diffViewerService = dvs
        apiClient = client
    }
    
    internal var invokeLater: (Runnable) -> Unit = { 
        ApplicationManager.getApplication().invokeLater(it) 
    }

    // ==================== Public API ====================

    fun focusOrCreateTerminal(interactive: Boolean = false) {
        val uiOpen = hasTerminalUI()
        val endpoint = port ?: return showConnectionDialog()
        // The liveness probe is a blocking HTTP request (5s connect + 5s read), so it must never
        // run on the EDT; the decision table is evaluated back on the EDT once it returns.
        AppExecutorUtil.getAppExecutorService().submit {
            val running = PortFinder.isOpenCodeRunningOnPort(endpoint, hostname, username, password)
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                if (isConnected.get() && !running) isConnected.set(false)
                when (launchAction(
                    interactive = interactive,
                    connected = isConnected.get(),
                    uiOpen = uiOpen,
                    hasEndpoint = true,
                    hasMode = lastMode != ConnectionMode.NONE,
                    running = running
                )) {
                    LaunchAction.FOCUS_UI -> focusTerminalUI()
                    LaunchAction.NO_OP -> Unit
                    LaunchAction.ASK_RESTORE -> showRestoreOrRestartDialog(running = true)
                    LaunchAction.ASK_RESTART -> showRestoreOrRestartDialog(running = false)
                    LaunchAction.RESTORE_UI -> restoreUiForMode()
                    LaunchAction.SHOW_CONNECT_DIALOG -> showConnectionDialog()
                }
            }
        }
    }

    fun hasTerminalUI(): Boolean {
        val tf = terminalVirtualFile
        if (tf != null && FileEditorManager.getInstance(project).openFiles.contains(tf)) return true
        val wf = webVirtualFile
        if (wf != null && FileEditorManager.getInstance(project).openFiles.contains(wf)) return true
        return false
    }
    
    /**
     * Asks whether to reuse the remembered endpoint ([running] = true) or restart the server
     * ([running] = false). "New" drops the connection state and opens the connect dialog.
     */
    private fun showRestoreOrRestartDialog(running: Boolean) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            val res = Messages.showYesNoCancelDialog(
                project,
                if (running) "Session active. Restore UI?" else "Server dead. Restart?",
                "OpenCode",
                if (running) "Restore" else "Restart",
                "New",
                "Cancel",
                if (running) Messages.getQuestionIcon() else Messages.getWarningIcon()
            )
            when (res) {
                Messages.YES -> if (running) restoreUiForMode() else restartServer(lastMode)
                Messages.NO -> { disconnectAndReset(); showConnectionDialog() }
                else -> Unit
            }
        }
    }

    fun focusOrCreateTerminalAndPaste(text: String) {
        if (text.isBlank() || project.isDisposed) return
        if (apiClient == null && terminalEditor == null) { ApplicationManager.getApplication().invokeLater { Messages.showInfoMessage(project, "OpenCode not running.", "OpenCode") }; return }
        schedulePasteAttempt(text, 20, 100L)
        ApplicationManager.getApplication().invokeLater { focusTerminalUI() }
    }

    /**
     * Injects [text] (an `@path#Lx-y` reference) into the OpenCode terminal as typed input.
     *
     * opencode v2 removed `POST /tui/append-prompt`, so the reference is written to the terminal's
     * TTY instead. Returns false (so [schedulePasteAttempt] keeps retrying) while the terminal is
     * still starting up.
     */
    fun pasteToTerminal(text: String): Boolean {
        if (text.isBlank()) return false
        val written = terminalTextWriterProvider()?.write(text) ?: false
        if (!written) logger.warn("[Paste] No terminal TTY accepted the reference: $text")
        return written
    }

    fun addConnectionListener(listener: (Boolean) -> Unit) {
        connectionListeners.add(listener); listener(isConnected.get())
    }

    fun removeConnectionListener(listener: (Boolean) -> Unit) { connectionListeners.remove(listener) }

    fun onTerminalDisposed() {
        if (isConnected.get() || port != null) {
            logger.info("[OpenCode] Terminal disposed (timeout or closed). Resetting connection state.")
            disconnectAndReset()
        }
    }

    // ==================== Event Handling & Barrier ====================

    private fun handleEvent(event: OpenCodeEvent) {
        if (event !is UnknownEvent) logger.info("[OpenCode] SSE: ${event.type}")
        when (event) {
            // v2 turn start (`session.execution.started`): the real "busy" signal on the v2 wire.
            is SessionExecutionStartedEvent -> {
                val sId = event.sessionID
                val started = sessionManager.onTurnStart()
                if (started) clearTurnState(sId)
            }
            // v2 turn end (`session.execution.succeeded|failed|interrupted`): any outcome ends the turn.
            is SessionExecutionEndedEvent -> handleTurnEnd(event.sessionID, event.outcome, event.errorMessage)
            // v2 step end (`session.step.ended`): carries the changed files and the assistant message id.
            is SessionStepEndedEvent -> {
                val sId = event.sessionID
                event.assistantMessageID?.let { recordTurnMessageId(sId, it) }
                event.files.forEach { sessionManager.onFileEdited(it) }
            }
            else -> {}
        }
    }

    /**
     * Ends the current turn: captures the snapshot, notifies the user with the turn's outcome, and
     * arms the barrier so the diff pipeline runs as soon as the turn's message id (or the barrier
     * timeout) is available.
     *
     * @param outcome `succeeded`, `failed` or `interrupted`
     * @param errorMessage provider error carried by `session.execution.failed`, when present
     */
    private fun handleTurnEnd(sessionId: String, outcome: String = "succeeded", errorMessage: String? = null) {
        val snapshot = sessionManager.onTurnEnd()
        if (snapshot != null) {
            turnSnapshots[sessionId] = snapshot
            logger.info("[OpenCode] Turn #${snapshot.turnNumber} snapshot captured (outcome=$outcome)")
            notifyTurnOutcome(outcome, errorMessage)
        }
        turnIdleWaiting[sessionId] = true
        attemptBarrierTrigger(sessionId)
    }

    /** Reports the turn result: an info notification on success, a warning otherwise. */
    private fun notifyTurnOutcome(outcome: String, errorMessage: String?) {
        val (title, content, warning) = turnOutcomeNotification(outcome, errorMessage)
        sendNotification(
            title,
            content,
            if (warning) NotificationType.WARNING else NotificationType.INFORMATION,
            replacePrevious = true
        )
    }

    private fun clearTurnState(sessionId: String) {
        val oldSnapshot = turnSnapshots.remove(sessionId)
        turnMessageIds.remove(sessionId)
        turnIdleWaiting.remove(sessionId)
        turnBarrierTasks.remove(sessionId)?.cancel(false)
        turnLastTriggerTimes.remove(sessionId)
        
        if (oldSnapshot != null) {
            logger.info("[OpenCode] Turn state cleared (was Turn #${oldSnapshot.turnNumber})")
        } else {
            logger.info("[OpenCode] Turn state cleared (no previous snapshot)")
        }
    }

    private fun recordTurnMessageId(sessionId: String, messageId: String) {
        if (turnMessageIds.containsKey(sessionId)) return
        turnMessageIds[sessionId] = messageId
        val turnNum = turnSnapshots[sessionId]?.turnNumber ?: sessionManager.getCurrentTurnNumber()
        logger.info("[OpenCode] Turn #$turnNum MessageID: $messageId")
        attemptBarrierTrigger(sessionId)
    }

    private fun attemptBarrierTrigger(sessionId: String) {
        val ready = turnIdleWaiting[sessionId] == true
        val hasId = turnMessageIds.containsKey(sessionId)
        val snapshot = turnSnapshots[sessionId]

        logger.debug("[OpenCode] Barrier check: ready=$ready, hasId=$hasId, hasSnapshot=${snapshot != null}")

        if (ready && hasId) {
            turnIdleWaiting[sessionId] = false
            turnBarrierTasks.remove(sessionId)?.cancel(false)
            if (snapshot != null) {
                logger.info("[OpenCode] Turn #${snapshot.turnNumber} Barrier triggered → fetching diffs")
                triggerDiffFetch(sessionId, snapshot)
            } else {
                logger.warn("[OpenCode] Barrier triggered but no snapshot available!")
            }
        } else if (ready) {
            scheduleBarrierTimeout(sessionId)
        }
    }

    private fun scheduleBarrierTimeout(sessionId: String) {
        if (turnBarrierTasks.containsKey(sessionId)) return
        val turnNum = turnSnapshots[sessionId]?.turnNumber ?: "?"
        logger.debug("[OpenCode] Turn #$turnNum Barrier timeout scheduled (${BARRIER_TIMEOUT_MS}ms)")
        val task = AppExecutorUtil.getAppScheduledExecutorService().schedule({
            val snapshot = turnSnapshots[sessionId]
            if (turnIdleWaiting[sessionId] == true && snapshot != null) {
                logger.warn("[OpenCode] Turn #${snapshot.turnNumber} Barrier timeout → forcing diff fetch")
                turnIdleWaiting[sessionId] = false
                triggerDiffFetch(sessionId, snapshot)
            }
            turnBarrierTasks.remove(sessionId)
        }, BARRIER_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        turnBarrierTasks[sessionId] = task
    }

    private fun triggerDiffFetch(sessionId: String, snapshot: TurnSnapshot) {
        val now = System.currentTimeMillis()
        if (now - (turnLastTriggerTimes[sessionId] ?: 0) < DEBOUNCE_MS) {
            logger.debug("[OpenCode] Turn #${snapshot.turnNumber} Debounced (too soon)")
            return
        }
        turnLastTriggerTimes[sessionId] = now
        fetchAndShowDiffs(sessionId, snapshot)
    }

    private fun forceVfsRefresh(diffs: List<FileDiff>) {
        if (diffs.isEmpty()) return
        try {
            val files = diffs.mapNotNull { diff -> 
                PathUtil.resolveProjectPath(project, diff.file)?.let { 
                    val ioFile = java.io.File(it)
                    // If file is deleted, we must refresh the parent directory to detect deletion
                    if (!ioFile.exists()) ioFile.parentFile else ioFile
                } 
            }
            if (files.isNotEmpty()) {
                logger.info("[OpenCode] Forcing VFS refresh for ${files.size} paths: ${files.map { it.absolutePath }}")
                com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshIoFiles(files, false, false, null)
            }
        } catch (e: Exception) {
            logger.debug("[OpenCode] VFS refresh skipped: ${e.message}")
        }
    }

    private fun fetchAndShowDiffs(sessionId: String, snapshot: TurnSnapshot) {
        val client = apiClient ?: return
        val path = project.basePath ?: return
        val assistantMessageId = turnMessageIds[sessionId]

        logger.info("[OpenCode] Turn #${snapshot.turnNumber} fetchAndShowDiffs: assistantMessageId=$assistantMessageId")

        AppExecutorUtil.getAppExecutorService().submit {
            try {
                // The v2 diff route. `from` is omitted on purpose: it only accepts a
                // *user* message id, and omitting it defaults to the newest user turn.
                logger.info("[OpenCode] Turn #${snapshot.turnNumber} Fetching diffs for session $sessionId (newest user turn)")
                val diffs = client.getSessionDiff(sessionId, path)
                logger.info("[OpenCode] Turn #${snapshot.turnNumber} Server returned ${diffs.size} diffs: ${diffs.map { "${it.file}(+${it.additions}/-${it.deletions})" }}")

                // 1. Force VFS refresh for Server files and Known files BEFORE processing
                // This ensures we catch deletions of files that Server missed but AI previously touched.
                // We rely on Server Authoritative logic: if Server missed a file and we have no history of it, we skip it.
                val serverFiles = diffs.map { it.file }
                val knownFiles = sessionManager.getKnownFilePaths()
                val filesToRefresh = (serverFiles + knownFiles).distinct()
                
                if (filesToRefresh.isNotEmpty()) {
                    // Create dummy FileDiffs just to pass the filename (v2 wires no before/after content)
                    forceVfsRefresh(filesToRefresh.map { FileDiff(it, "", 0, 0, "modified") })
                }
                
                // Capture any late VFS events triggered by the refresh
                val lateVfsEvents = sessionManager.getLiveVfsChangedFiles()
                val lateAiCreatedFiles = sessionManager.getLiveAiCreatedFiles()

                // Process using the snapshot via SessionManager (centralized logic)
                // Pass late VFS events to help with affinity checks
                val entries = sessionManager.getProcessedDiffs(diffs, snapshot, lateVfsEvents, lateAiCreatedFiles)
                
                if (entries.isNotEmpty()) {
                    sessionManager.updateKnownState(entries.map { it.file })
                    
                    logger.info("[OpenCode] Turn #${snapshot.turnNumber} Showing ${entries.size} diffs")
                    invokeLater {
                        if (!project.isDisposed) diffViewerService.showMultiFileDiff(entries)
                    }
                } else {
                    logger.info("[OpenCode] Turn #${snapshot.turnNumber} No diffs to show after processing.")
                }
            } catch (e: Exception) {
                logger.error("[OpenCode] Turn #${snapshot.turnNumber} Diff fetch error", e)
            }
        }
    }
    
    // createSyntheticDiff removed - logic moved to SessionManager


    // ==================== Lifecycle & Connection ====================

    private fun initializeApiClient(host: String, port: Int, pwd: String? = password) {
        val apiHost = if (host == "0.0.0.0") "127.0.0.1" else host
        password = pwd
        // opencode v2 always authenticates as the fixed Basic-auth user. Normalise here so a
        // password can never coexist with a null/blank username: the local path only resolves a
        // password (service discovery), and a null username used to make the SSE handshake
        // anonymous. Anonymous endpoints (no password) keep whatever username was set.
        if (pwd != null && username.isNullOrBlank()) username = ServerAuth.DEFAULT_USERNAME
        apiClient = OpenCodeApiClient(apiHost, port, username, pwd)
    }

    private fun startConnectionManager() {
        if (connectionManagerTask?.isDone == false || port == null || apiClient == null) return
        // A new connect attempt re-arms the one-shot auth warning so a stale transient failure
        // cannot suppress a later genuine 401.
        authFailureNotified = false
        connectionManagerTask = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay({ if (!project.isDisposed) tryConnect() }, 0, RETRY_INTERVAL_MS, TimeUnit.MILLISECONDS)
    }

    private fun tryConnect() {
        if (isConnected.get() || !isConnecting.compareAndSet(false, true)) return
        try {
            val client = apiClient
            val basePath = project.basePath
            if (client != null && basePath != null) {
                if (client.checkHealth(basePath)) {
                    // Version gate is non-blocking; the credential probe is not: a wrong/missing
                    // password must surface instead of silently pretending to be connected.
                    warnIfUnsupportedVersion(client)
                    when (client.probeAuth(basePath)) {
                        OpenCodeApiClient.AuthProbeResult.ACCEPTED -> {
                            reconnectPolicy.onConnected()
                            wasEverConnected = true
                            connectToSse()
                        }
                        OpenCodeApiClient.AuthProbeResult.REJECTED -> {
                            // Only 401/403 count as a rejected password; anything else (5xx, 404,
                            // network flake) takes the generic failure path.
                            logger.warn("[OpenCode] Credentials rejected for $hostname:$port; not connecting")
                            notifyAuthFailure()
                            handleConnectionFailure()
                        }
                        OpenCodeApiClient.AuthProbeResult.INDETERMINATE -> {
                            logger.warn("[OpenCode] Authenticated probe failed for $hostname:$port; not connecting")
                            handleConnectionFailure()
                        }
                    }
                } else {
                    handleConnectionFailure()
                }
            }
        } catch (_: Exception) {
            handleConnectionFailure()
        } finally {
            isConnecting.set(false)
        }
    }

    /**
     * Non-blocking version gate: read `GET /api/info`'s `version` and warn once when its major
     * component is not [SUPPORTED_MAJOR_VERSION]. The connection is never blocked — the plugin
     * still connects, since the v2 API surface may still be compatible.
     */
    private fun warnIfUnsupportedVersion(client: OpenCodeApiClient) {
        if (versionWarningShown) return
        val version = try { client.getServerVersion() } catch (_: Exception) { null } ?: return
        val major = OpenCodeApiClient.parseMajorVersion(version) ?: return
        if (major == SUPPORTED_MAJOR_VERSION) return
        versionWarningShown = true
        logger.warn("[OpenCode] opencode v$version detected; plugin targets v$SUPPORTED_MAJOR_VERSION")
        sendNotification(
            "OpenCode Version Mismatch",
            "opencode v$version detected; plugin targets v$SUPPORTED_MAJOR_VERSION.",
            NotificationType.WARNING
        )
    }

    /**
     * Failure sink for the connection manager. A local endpoint self-heals by rediscovering the
     * service (which restarts it when needed); the user is only prompted once rediscovery can no
     * longer make progress, and remote endpoints are never rediscovered.
     */
    private fun handleConnectionFailure() {
        if (lastMode == ConnectionMode.NONE || !wasEverConnected) return
        val local = lastMode != ConnectionMode.REMOTE
        when (reconnectPolicy.onFailure(local, System.currentTimeMillis())) {
            ReconnectPolicy.Action.RETRY -> Unit
            ReconnectPolicy.Action.REDISCOVER -> rediscoverLocalServiceAsync()
            ReconnectPolicy.Action.ASK_USER -> showRemoteReconnectDialog()
        }
    }

    /**
     * Asks the service CLI for the current endpoint on a worker thread and reconnects when it
     * changed (a restarted daemon usually listens on a new port).
     */
    private fun rediscoverLocalServiceAsync() {
        if (!rediscoveryInFlight.compareAndSet(false, true)) return
        AppExecutorUtil.getAppExecutorService().submit {
            try {
                val endpoint = discoverLocalService()?.endpoint
                if (endpoint == null) {
                    logger.info("[OpenCode] Service rediscovery found no endpoint")
                    return@submit
                }
                if (endpoint.host == hostname && endpoint.port == port) {
                    // Same endpoint: the daemon is probably busy or restarting; keep retrying.
                    return@submit
                }
                logger.info("[OpenCode] Service endpoint changed to ${endpoint.host}:${endpoint.port}, reconnecting")
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed || isConnected.get()) return@invokeLater
                    reconnectPolicy.reset()
                    restartServer(lastMode)
                }
            } catch (e: Exception) {
                logger.warn("[OpenCode] Service rediscovery failed", e)
            } finally {
                rediscoveryInFlight.set(false)
            }
        }
    }

    private fun showRemoteReconnectDialog() {
        ApplicationManager.getApplication().invokeLater {
            if (isConnected.get()) return@invokeLater
            if (lastMode == ConnectionMode.NONE) { disconnectAndReset(); showConnectionDialog(); return@invokeLater }
            when (Messages.showYesNoCancelDialog(project, "Connection lost. Reconnect?", "OpenCode", "Reconnect", "New", "Cancel", Messages.getWarningIcon())) {
                Messages.YES -> restartServer(lastMode)
                Messages.NO -> { disconnectAndReset(); showConnectionDialog() }
                else -> Unit
            }
        }
    }

    private fun connectToSse() {
        val basePath = project.basePath ?: return
        sseListener?.disconnect()
        apiClient?.let {
            sseListener = it.createEventListener(
                basePath,
                { handleEvent(it) },
                { onSseError(it) },
                { updateConnectionState(true) },
                { updateConnectionState(false) }
            ).apply { connect() }
        }
    }

    /**
     * SSE error sink. Connection state always drops, and a rejected handshake (401/403) is surfaced
     * to the user instead of being swallowed into the disconnected state.
     */
    private fun onSseError(error: Throwable) {
        updateConnectionState(false)
        if (error is SseAuthException) notifyAuthFailure()
    }

    /** Shows the one-shot authentication-failure warning (connect probe or SSE handshake). */
    private fun notifyAuthFailure() {
        if (authFailureNotified) return
        authFailureNotified = true
        logger.warn("[OpenCode] Authentication failed; check the server password")
        sendNotification(
            "OpenCode Authentication Failed",
            "OpenCode rejected the credentials. Check the server password.",
            NotificationType.WARNING
        )
    }

    private fun updateConnectionState(connected: Boolean) { if (isConnected.getAndSet(connected) != connected) connectionListeners.forEach { it(connected) } }
    private fun sendNotification(
        title: String,
        content: String,
        type: NotificationType = NotificationType.INFORMATION,
        replacePrevious: Boolean = false
    ) {
        val time = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        val message = "[$time] $content"
        invokeLater {
            if (project.isDisposed) return@invokeLater
            if (replacePrevious) lastIdleNotification?.expire()
            val notification = NotificationGroupManager.getInstance()
                .getNotificationGroup("OpenCodeV2")
                .createNotification(title, message, type)
                .setImportant(true)
            if (replacePrevious) lastIdleNotification = notification
            notification.notify(project)
            try {
                SystemNotifications.getInstance().notify("OpenCode", title, message)
            } catch (e: Throwable) {
                logger.debug("[OpenCode] System notification failed: ${e.message}")
            }
        }
    }

    override fun dispose() {
        disconnectAndReset()
        // Drop the web tab's close callback too: it captures this service (and therefore the
        // project) and would otherwise stay in the provider's static map for the whole session.
        webVirtualFile?.let { OpenCodeWebFileEditorProvider.unregisterCallback(it) }
        OpenCodeTerminalFileEditorProvider.clearAll(project)
    }

    private fun disconnectAndReset() {
        connectionManagerTask?.cancel(true); sseListener?.disconnect(); isConnected.set(false); isConnecting.set(false)
        reconnectPolicy.reset()
        versionWarningShown = false
        authFailureNotified = false
        turnMessageIds.clear(); turnSnapshots.clear(); turnIdleWaiting.clear(); turnBarrierTasks.values.forEach { it.cancel(false) }; turnBarrierTasks.clear()
        turnLastTriggerTimes.clear()
        terminateProcess()
        terminalVirtualFile?.let { OpenCodeTerminalFileEditorProvider.disposeWidget(it, project, notifyService = false) }
        terminalVirtualFile = null; terminalEditor = null; webVirtualFile = null; port = null; hostname = "127.0.0.1"; apiClient = null
    }

    private fun terminateProcess() {
        try {
            val process = terminalEditor?.terminalWidget?.processTtyConnector?.process
            if (process?.isAlive == true) {
                process.destroy()
                if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
            }
        } catch (_: Exception) {}
    }

    private fun showConnectionDialog() {
        AppExecutorUtil.getAppExecutorService().submit {
            val suggested = PortFinder.findAvailablePort()
            ApplicationManager.getApplication().invokeLater {
                OpenCodeConnectDialog.show(project, suggested)?.let { 
                    processConnectionChoice(it.hostname, it.port, it.password, it.useWebInterface, it.customBasePath) 
                }
            }
        }
    }

    private fun processConnectionChoice(h: String, p: Int, pwd: String?, web: Boolean, customBasePath: String? = null) {
        val safeH = h.trim().ifBlank { "127.0.0.1" }
        val local = safeH in LOCAL_HOSTS

        AppExecutorUtil.getAppExecutorService().submit {
            val a = resolveConnectionAuth(local, pwd) { discoverLocalService()?.endpoint }
            val running = if (local) PortFinder.isOpenCodeRunningOnPort(p, safeH, a.username, a.password) else true
            val occupied = if (local && !running) !PortFinder.isPortAvailable(p) else false

            ApplicationManager.getApplication().invokeLater {
                if (running) {
                    lastMode = if (!local) ConnectionMode.REMOTE else if (web) ConnectionMode.WEB else ConnectionMode.TERMINAL
                    // For remote (!local), strict headless unless web mode.
                    // For local, if running, we also just connect (headless or web), we do NOT spawn a new terminal.
                    connectToExistingServer(safeH, p, a, web, web, customBasePath)
                } else if (occupied) {
                    val choice = Messages.showYesNoDialog(
                        project,
                        "Port $p is in use but did not pass the health check.\nIt might be an older OpenCode version or a different service.\n\nTry to connect anyway?",
                        "Connection Warning",
                        "Connect Anyway",
                        "Cancel",
                        Messages.getWarningIcon()
                    )
                    if (choice == Messages.YES) {
                        lastMode = if (!local) ConnectionMode.REMOTE else if (web) ConnectionMode.WEB else ConnectionMode.TERMINAL
                        connectToExistingServer(safeH, p, a, web, web, customBasePath)
                    } else {
                        showConnectionDialog()
                    }
                } else {
                    // Not running and not occupied -> Start new server
                    if (!local) {
                         // Safety net: If remote host is not running, we cannot "start" it locally.
                         Messages.showErrorDialog(project, "Cannot connect to remote server $safeH:$p (Not reachable).", "Connection Failed")
                         showConnectionDialog()
                    } else {
                        lastMode = if (web) ConnectionMode.WEB else ConnectionMode.TERMINAL
                        if (web) createWebTerminal(safeH, p, a.password, customBasePath) else createLocalTerminal(safeH, p, a.password, customBasePath)
                    }
                }
            }
        }
    }

    private fun connectToExistingServer(h: String, p: Int, a: ServerAuth, ui: Boolean, web: Boolean, customBasePath: String? = null) {
        hostname = h; port = p; username = a.username; password = a.password
        if (ui) { if (web) createWebUI(h, p) else createTerminalUIInternal(p, false, null, customBasePath) }
        else { ApplicationManager.getApplication().invokeLater { Messages.showInfoMessage(project, "Connected to $h:$p", "OpenCode") } }
        initializeApiClient(h, p); startConnectionManager()
    }

    private fun createWebUI(h: String, p: Int) {
        webVirtualFile = WebModeSupport.openWebTab(project, h, p, password) { if (webVirtualFile == null) terminateProcess() }
    }

    private fun createWebTerminal(h: String, p: Int, pwd: String?, customBasePath: String? = null) {
        AppExecutorUtil.getAppExecutorService().submit {
            val bin = detectOpenCodeBinary()
            if (bin == null) {
                showCliNotFoundError()
                return@submit
            }
            ApplicationManager.getApplication().invokeLater {
                hostname = h; port = p; lastMode = ConnectionMode.WEB
                createTerminalUIInternal(p, true, bin, customBasePath)
            }
            // Bounded readiness poll against `/api/info`; the ConnectionManager retries anyway.
            if (!awaitServiceReady(h, p, pwd)) {
                logger.warn("[OpenCode] Readiness poll (Web) timed out for $h:$p, starting ConnectionManager anyway")
            }
            
            // Even if it timed out, we might still want the UI and the API client to try reconnecting
            ApplicationManager.getApplication().invokeLater { createWebUI(h, p) }
            initializeApiClient(h, p, pwd)
            startConnectionManager()
        }
    }

    /**
     * Starts the local TUI against the discovered v2 background service.
     *
     * The service port is service-managed, so the dialog's host/port/password are ignored for the
     * local case: [OpenCodeServiceDiscovery.discover] resolves the endpoint (starting the service
     * if needed) and the TUI attaches to it.
     */
    private fun createLocalTerminal(h: String, p: Int, pwd: String?, customBasePath: String? = null) {
        val safeH = h.trim().ifBlank { "127.0.0.1" }
        if (safeH !in LOCAL_HOSTS) {
            // Guard: Never attempt to spawn local terminal for remote host
            connectToExistingServer(safeH, p, ServerAuth(password = pwd), false, false, customBasePath)
            return
        }
        AppExecutorUtil.getAppExecutorService().submit {
            val service = discoverLocalService()
            if (service == null) {
                showCliNotFoundError()
                return@submit
            }
            val endpoint = service.endpoint
            if (endpoint == null) {
                showServiceUnavailableError()
                return@submit
            }
            ApplicationManager.getApplication().invokeLater {
                hostname = endpoint.host; port = endpoint.port; password = endpoint.password
                username = ServerAuth.DEFAULT_USERNAME
                lastMode = ConnectionMode.TERMINAL
                createTerminalUIInternal(endpoint.port, true, service.binary, customBasePath)
            }
            // Bounded readiness poll against `/api/info`; the ConnectionManager retries anyway.
            if (!awaitServiceReady(endpoint.host, endpoint.port, endpoint.password)) {
                logger.warn("[OpenCode] Readiness poll timed out for ${endpoint.host}:${endpoint.port}, starting ConnectionManager anyway")
            }
            
            initializeApiClient(endpoint.host, endpoint.port, endpoint.password)
            startConnectionManager()
        }
    }

    /**
     * The local OpenCode v2 service: the CLI binary that drives it plus its discovered endpoint.
     * [endpoint] is null when the service cannot be started or its URL cannot be read.
     */
    private data class LocalService(val binary: String, val endpoint: ServiceEndpoint?)

    /**
     * Resolves the local OpenCode v2 service (CLI binary + endpoint) through
     * [OpenCodeServiceDiscovery], starting the service when needed.
     *
     * This is the single source of truth for local credentials: for local hosts the endpoint and
     * its password come from the opencode `service.json` via discovery, never from the connect
     * dialog. Returns null when no CLI binary is available.
     */
    private fun discoverLocalService(): LocalService? {
        val bin = detectOpenCodeBinary() ?: return null
        return LocalService(bin, OpenCodeServiceDiscovery.discover(bin))
    }

    /**
     * Polls the v2 `/api/info` route a bounded number of times so the service has a moment to
     * accept connections right after discovery. Runs off the EDT (callers are on a worker thread).
     *
     * @return true as soon as the endpoint answers, false once the attempts are exhausted.
     */
    private fun awaitServiceReady(host: String, port: Int, pwd: String?, attempts: Int = READINESS_ATTEMPTS, delayMs: Long = READINESS_DELAY_MS): Boolean {
        repeat(attempts) { attempt ->
            if (PortFinder.isOpenCodeRunningOnPort(port, host, username, pwd)) return true
            if (attempt < attempts - 1) {
                try {
                    Thread.sleep(delayMs)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
        }
        return false
    }

    private fun createTerminalUIInternal(p: Int, cont: Boolean = true, command: String? = null, customBasePath: String? = null) {
        val t = "$OPEN_CODE_TAB_PREFIX($p)"
        activeBasePath = customBasePath
        val wd = customBasePath ?: project.basePath
        val w = TerminalView.getInstance(project).createLocalShellWidget(wd, t)
        OpenCodeTerminalLinkFilter.install(project, w)
        val f = OpenCodeTerminalVirtualFile(t)
        terminalVirtualFile = f; OpenCodeTerminalFileEditorProvider.registerWidget(f, w, project)
        ApplicationManager.getApplication().invokeLater {
            terminalEditor = FileEditorManager.getInstance(project).openFile(f, true).firstOrNull { it is OpenCodeTerminalFileEditor } as? OpenCodeTerminalFileEditor
            terminalEditor?.let { 
                val cmd = command ?: getOpenCodeBinary()
                // v2: no --hostname/--port; the TUI attaches to the background service itself.
                // The command is typed into the user's shell, so the path form depends on that shell.
                // getShellPath() blocks since 2026.2, so it must not be read on this thread.
                resolveShellPathAsync { shellPath ->
                    w.executeCommand(OpenCodeCommand.tui(cmd, cont, OpenCodeCommand.detectShell(shellPath))); pinTerminalTab(f)
                }
            }
        }
    }

    /**
     * Resolves the terminal shell path on a worker thread and hands it back on the EDT.
     *
     * The provider call behind it (`TerminalProjectOptionsProvider.getShellPath`) runs
     * `runBlockingMaybeCancellable`, which throws when invoked on the EDT (issue #3).
     */
    private fun resolveShellPathAsync(onResolved: (String?) -> Unit) {
        AppExecutorUtil.getAppExecutorService().submit {
            val shell = readShellPathSafely(ApplicationManager.getApplication().isDispatchThread) {
                TerminalProjectOptionsProvider.getInstance(project).shellPath
            }
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed) onResolved(shell)
            }
        }
    }

    @Volatile private var _cachedBinary: String? = null

    private fun getOpenCodeBinary(): String {
        _cachedBinary?.let { return it }

        // Fast check common paths (safe for EDT - no process execution)
        val home = System.getProperty("user.home")
        val candidates = if (isWindows()) {
            listOf(
                java.io.File(home, ".opencode/bin/opencode.exe"),
                java.io.File("C:\\Program Files\\opencode\\opencode.exe"),
                java.io.File(System.getenv("LOCALAPPDATA") ?: "", "opencode\\opencode.exe"),
            )
        } else {
            listOf(
                java.io.File(home, ".opencode/bin/opencode"),
                java.io.File("/opt/homebrew/bin/opencode"),
                java.io.File("/usr/local/bin/opencode"),
                java.io.File("/home/linuxbrew/.linuxbrew/bin/opencode"),
                java.io.File(home, ".linuxbrew/bin/opencode"),
                java.io.File("/usr/bin/opencode"),
                java.io.File("/snap/bin/opencode"),
            )
        }
        
        for (candidate in candidates) {
            if (candidate.exists() && candidate.canExecute()) {
                val path = candidate.absolutePath
                logger.info("[OpenCode] Resolved CLI to: $path")
                _cachedBinary = path
                return path
            }
        }

        return "opencode"
    }

    /** Detect OpenCode binary path (Background thread safe) */
    private fun detectOpenCodeBinary(): String? {
        val home = System.getProperty("user.home")
//        val exe = if (isWindows()) ".exe" else ""
        
        // Check common installation paths (ordered by priority)
        val candidates = if (isWindows()) {
            listOf(
                java.io.File(home, ".opencode/bin/opencode.exe"),
                java.io.File("C:\\Program Files\\opencode\\opencode.exe"),
                java.io.File("C:\\Program Files (x86)\\opencode\\opencode.exe"),
                java.io.File(System.getenv("LOCALAPPDATA") ?: "", "opencode\\opencode.exe"),
            )
        } else {
            listOf(
                java.io.File(home, ".opencode/bin/opencode"),           // npm global install
                java.io.File("/opt/homebrew/bin/opencode"),             // macOS ARM Homebrew
                java.io.File("/usr/local/bin/opencode"),                // macOS Intel Homebrew / Linux standard
                java.io.File("/home/linuxbrew/.linuxbrew/bin/opencode"),// Linux Homebrew
                java.io.File(home, ".linuxbrew/bin/opencode"),          // Linux Homebrew (user install)
                java.io.File("/usr/bin/opencode"),                      // System package manager
                java.io.File("/snap/bin/opencode"),                     // Snap install
            )
        }
        
        for (candidate in candidates) {
            if (candidate.exists() && candidate.canExecute()) {
                logger.info("[OpenCode] Detected CLI at: ${candidate.absolutePath}")
                _cachedBinary = candidate.absolutePath
                return candidate.absolutePath
            }
        }

        // Fallback: look the CLI up in PATH (may fail in sandboxed environments like Snap)
        resolveCliFromPath()?.let { path ->
            logger.info("[OpenCode] CLI detected in PATH: $path")
            _cachedBinary = path
            return path
        }

        return null
    }

    /**
     * Resolves the CLI from PATH.
     *
     * On Windows the result must be a file CreateProcess can start, so `where opencode` is parsed
     * down to its first `.exe`/`.com`/`.cmd`/`.bat` match: the bare name fails because Java only
     * appends `.exe` to it, while the first PATH match is usually the extensionless POSIX launcher
     * shipped by npm and Scoop (issue #2). POSIX shells resolve a bare name via `execvp`, so there
     * the name itself is a valid command.
     */
    private fun resolveCliFromPath(): String? {
        if (isWindows()) {
            val whereOutput = runQuietly(listOf("cmd", "/c", "where", "opencode")) ?: return null
            return OpenCodeCliResolver.selectWindowsExecutable(whereOutput)
        }
        val available = runQuietly(listOf("which", "opencode")) != null ||
            runQuietly(listOf("sh", "-lc", "command -v opencode")) != null
        return if (available) "opencode" else null
    }

    /** Runs [command] and returns its stdout, or null when it cannot be started or reports failure. */
    private fun runQuietly(command: List<String>): String? = try {
        val output = CapturingProcessHandler(GeneralCommandLine(command)).runProcess(5000)
        if (output.exitCode == 0) output.stdout else null
    } catch (_: Exception) {
        null
    }
    
    /** Show CLI not found error (must call on any thread, will dispatch to EDT) */
    private fun showCliNotFoundError() {
        ApplicationManager.getApplication().invokeLater {
            Messages.showErrorDialog(project, "OpenCode CLI not found.", "Error")
        }
    }

    /** Show the v2 "service unavailable" error (dispatches to the EDT). */
    private fun showServiceUnavailableError() {
        ApplicationManager.getApplication().invokeLater {
            Messages.showErrorDialog(
                project,
                "OpenCode service unavailable. Could not determine the background service endpoint.",
                "Error"
            )
        }
    }

    private fun isWindows() = System.getProperty("os.name", "").lowercase().contains("windows")
    private fun pinTerminalTab(f: VirtualFile) { try { FileEditorManagerEx.getInstanceEx(project).currentWindow?.setFilePinned(f, true) } catch (_: Exception) {} }
    private fun restartServer(m: ConnectionMode) { 
        val h = hostname; val p = port ?: return; val pwd = password; val basePath = activeBasePath
        disconnectAndReset(); hostname = h; port = p; password = pwd; lastMode = m; activeBasePath = basePath
        if (m == ConnectionMode.WEB) createWebTerminal(h, p, pwd, basePath) 
        else if (m == ConnectionMode.REMOTE) connectToExistingServer(h, p, ServerAuth(password = pwd), false, false, basePath)
        else createLocalTerminal(h, p, pwd, basePath) 
    }
    private fun focusTerminalUI() { terminalVirtualFile?.let { FileEditorManager.getInstance(project).openFile(it, true) } ?: webVirtualFile?.let { FileEditorManager.getInstance(project).openFile(it, true) } }
    private fun restoreUiForMode() { 
        when (lastMode) { 
            ConnectionMode.TERMINAL -> ensureTerminalUi()
            ConnectionMode.WEB -> ensureWebUi()
            ConnectionMode.REMOTE -> restoreRemoteConnection()
            else -> showConnectionDialog() 
        } 
    }
    private fun ensureTerminalUi() { 
        val f = terminalVirtualFile
        if (f != null && OpenCodeTerminalFileEditorProvider.hasWidget(f)) {
            focusTerminalUI()
        } else {
            // Terminal UI doesn't exist, need to create new terminal and start opencode
            try {
                createTerminalUIInternal(port ?: return, false)
            } catch (e: Exception) {
                logger.warn("[OpenCode] Failed to create terminal UI", e)
                ApplicationManager.getApplication().invokeLater {
                    Messages.showErrorDialog(project, "Failed to create terminal: ${e.message}", "OpenCode")
                }
            }
        }
    }
    private fun ensureWebUi() { if (webVirtualFile != null) focusTerminalUI() else createWebUI(hostname, port ?: return) }
    private fun restoreRemoteConnection() {
        // For remote connections, verify connection is alive and show status
        val p = port ?: return
        val h = hostname
        AppExecutorUtil.getAppExecutorService().submit {
            val running = PortFinder.isOpenCodeRunningOnPort(p, h, username, password)
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                if (running) {
                    if (!isConnected.get()) {
                        // Reconnect SSE if not connected
                        initializeApiClient(h, p)
                        startConnectionManager()
                    }
                    Messages.showInfoMessage(project, "Connected to $h:$p", "OpenCode")
                } else {
                    Messages.showWarningDialog(project, "Remote server $h:$p is not reachable.", "OpenCode")
                }
            }
        }
    }
    private fun schedulePasteAttempt(t: String, l: Int, d: Long) { 
        if (l <= 0 || project.isDisposed) {
            if (l <= 0 && !project.isDisposed) {
                logger.warn("[Paste] All retries exhausted for: $t")
            }
            return
        }
        AppExecutorUtil.getAppScheduledExecutorService().schedule({ 
            ApplicationManager.getApplication().invokeLater { 
                if (!project.isDisposed) {
                    if (!pasteToTerminal(t)) {
                        schedulePasteAttempt(t, l - 1, d)
                    }
                }
            } 
        }, d, TimeUnit.MILLISECONDS) 
    }
}
