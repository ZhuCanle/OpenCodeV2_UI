package com.zhucanle.opencodev2.ide.session

import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.concurrency.AppExecutorUtil

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Arms and fires the diff pipeline of a turn.
 *
 * A turn ends with `session.execution.succeeded|failed|interrupted`, but the assistant message id
 * that the diff route needs arrives later, in `session.step.ended`. The tracker waits for both —
 * bounded by [barrierTimeoutMs] — and then hands the turn snapshot to [onDiffReady] at most once
 * per [debounceMs]. State is keyed by session id, so concurrent sessions never mix.
 *
 * The tracker is deliberately free of IDE services: the scheduler and the clock are injected, which
 * is what makes the barrier testable without a running IDE.
 *
 * @param onDiffReady invoked on the scheduler thread once the turn is ready
 * @param debounceMs minimum interval between two diff pipelines of the same session
 * @param barrierTimeoutMs how long to wait for the message id before firing anyway
 * @param clock wall-clock source, injected for tests
 * @param scheduler schedules a delayed action and returns its cancellation handle
 */
internal class TurnTracker(
    private val onDiffReady: (sessionId: String, snapshot: TurnSnapshot) -> Unit,
    private val debounceMs: () -> Long = { 1_500L },
    private val barrierTimeoutMs: Long = 2_000L,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val scheduler: (delayMs: Long, action: () -> Unit) -> Cancellable = AppScheduler
) {

    fun interface Cancellable {
        fun cancel()
    }

    private class State {
        @Volatile var snapshot: TurnSnapshot? = null
        @Volatile var messageId: String? = null
        @Volatile var idleWaiting: Boolean = false
        @Volatile var barrierTask: Cancellable? = null
        @Volatile var lastTriggerAt: Long = 0
    }

    private val logger = Logger.getInstance(TurnTracker::class.java)
    private val states = ConcurrentHashMap<String, State>()

    /** A new execution started: forget the previous turn of that session. */
    fun onExecutionStarted(sessionId: String) {
        states.remove(sessionId)?.barrierTask?.cancel()
    }

    /** The execution ended: remember its snapshot and try to fire the diff pipeline. */
    fun onExecutionEnded(sessionId: String, snapshot: TurnSnapshot?) {
        val state = stateOf(sessionId)
        state.snapshot = snapshot
        state.idleWaiting = true
        attempt(sessionId, state)
    }

    /** `session.step.ended` reported the assistant message id of the current turn. */
    fun onMessageId(sessionId: String, messageId: String) {
        val state = stateOf(sessionId)
        if (state.messageId != null) return
        state.messageId = messageId
        attempt(sessionId, state)
    }

    /** Forgets one session (its execution was restarted or the connection was dropped). */
    fun clear(sessionId: String) {
        states.remove(sessionId)?.barrierTask?.cancel()
    }

    /** Forgets every session, cancelling pending timeouts. */
    fun clearAll() {
        states.values.forEach { it.barrierTask?.cancel() }
        states.clear()
    }

    /** True while [sessionId] waits for its turn to fire (message id or timeout). */
    internal fun isWaiting(sessionId: String): Boolean = states[sessionId]?.idleWaiting == true

    private fun stateOf(sessionId: String): State = states.computeIfAbsent(sessionId) { State() }

    private fun attempt(sessionId: String, state: State) {
        val ready = state.idleWaiting
        val hasMessageId = state.messageId != null
        if (ready && hasMessageId) {
            state.idleWaiting = false
            state.barrierTask?.cancel()
            state.barrierTask = null
            fire(sessionId, state)
        } else if (ready) {
            scheduleTimeout(sessionId, state)
        }
    }

    private fun scheduleTimeout(sessionId: String, state: State) {
        if (state.barrierTask != null) return
        state.barrierTask = scheduler(barrierTimeoutMs) {
            state.barrierTask = null
            if (state.idleWaiting) {
                state.idleWaiting = false
                logger.warn("[Session $sessionId] No message id after ${barrierTimeoutMs}ms; firing the diff pipeline anyway")
                fire(sessionId, state)
            }
        }
    }

    private fun fire(sessionId: String, state: State) {
        val snapshot = state.snapshot
        if (snapshot == null) {
            logger.warn("[Session $sessionId] Turn ended without a snapshot; nothing to show")
            return
        }
        val now = clock()
        if (now - state.lastTriggerAt < debounceMs()) {
            logger.debug("[Turn #${snapshot.turnNumber}] Debounced (too soon)")
            return
        }
        state.lastTriggerAt = now
        logger.info("[Turn #${snapshot.turnNumber}] Firing the diff pipeline (session $sessionId)")
        onDiffReady(sessionId, snapshot)
    }

    private object AppScheduler : (Long, () -> Unit) -> Cancellable {
        override fun invoke(delayMs: Long, action: () -> Unit): Cancellable {
            val future: ScheduledFuture<*> = AppExecutorUtil.getAppScheduledExecutorService()
                .schedule(action, delayMs, TimeUnit.MILLISECONDS)
            return Cancellable { future.cancel(false) }
        }
    }
}
