package com.zhucanle.opencodev2.ide.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pins the turn barrier: a turn ends with `session.execution.*`, the assistant message id arrives
 * later in `session.step.ended`, and the diff pipeline must run exactly once per turn, bounded by
 * a timeout, without mixing concurrent sessions.
 */
class TurnTrackerTest {

    /** Records scheduled timeouts so the test can fire them by hand. */
    private class FakeScheduler {
        private class Task(val delayMs: Long, val action: () -> Unit) {
            var cancelled = false
        }

        private val tasks = mutableListOf<Task>()

        val scheduler: (Long, () -> Unit) -> TurnTracker.Cancellable = { delayMs, action ->
            val task = Task(delayMs, action)
            tasks += task
            TurnTracker.Cancellable { task.cancelled = true }
        }

        val pending: Int get() = tasks.count { !it.cancelled }

        /** Runs every task that was not cancelled, like the real scheduled executor would. */
        fun runPending() {
            val due = tasks.toList()
            tasks.clear()
            due.filterNot { it.cancelled }.forEach { it.action() }
        }
    }

    private lateinit var scheduler: FakeScheduler
    private lateinit var tracker: TurnTracker
    private val fired = mutableListOf<Pair<String, TurnSnapshot>>()
    private var now = 10_000L

    @Before
    fun setUp() {
        scheduler = FakeScheduler()
        tracker = TurnTracker(
            onDiffReady = { sessionId, snapshot -> fired += sessionId to snapshot },
            debounceMs = { 1_500L },
            barrierTimeoutMs = 2_000L,
            clock = { now },
            scheduler = scheduler.scheduler
        )
    }

    private fun snapshot(turn: Int) = TurnSnapshot(
        turnNumber = turn,
        vfsChangedFiles = emptySet(),
        serverEditedFiles = emptySet(),
        aiCreatedFiles = emptySet(),
        userEditedFiles = emptySet(),
        baselineLabel = null
    )

    @Test
    fun firesWhenTheMessageIdArrivesAfterTheTurnEnded() {
        tracker.onExecutionEnded("s1", snapshot(1))
        assertTrue(fired.isEmpty())

        tracker.onMessageId("s1", "msg_1")

        assertEquals(1, fired.size)
        assertEquals("s1", fired.single().first)
        assertEquals(1, fired.single().second.turnNumber)
    }

    @Test
    fun firesWhenTheMessageIdArrivesBeforeTheTurnEnded() {
        tracker.onMessageId("s1", "msg_1")
        assertTrue(fired.isEmpty())

        tracker.onExecutionEnded("s1", snapshot(1))

        assertEquals(1, fired.size)
    }

    @Test
    fun firesOnTheTimeoutWhenNoMessageIdArrives() {
        tracker.onExecutionEnded("s1", snapshot(1))
        assertEquals(1, scheduler.pending)

        scheduler.runPending()

        assertEquals(1, fired.size)
    }

    @Test
    fun doesNotFireTwiceWhenTheMessageIdArrivesAfterTheTimeout() {
        tracker.onExecutionEnded("s1", snapshot(1))
        scheduler.runPending()
        assertEquals(1, fired.size)

        tracker.onMessageId("s1", "msg_late")

        assertEquals(1, fired.size)
    }

    @Test
    fun ignoresASecondMessageId() {
        tracker.onExecutionEnded("s1", snapshot(1))
        tracker.onMessageId("s1", "msg_1")
        tracker.onMessageId("s1", "msg_2")

        assertEquals(1, fired.size)
    }

    @Test
    fun doesNotFireWithoutASnapshot() {
        tracker.onExecutionEnded("s1", null)

        scheduler.runPending()

        assertTrue(fired.isEmpty())
    }

    @Test
    fun aNewExecutionForgetsThePreviousTurn() {
        tracker.onExecutionEnded("s1", snapshot(1))
        tracker.onExecutionStarted("s1")

        // The stale timeout must not fire anything for the finished turn.
        scheduler.runPending()

        assertTrue(fired.isEmpty())
        assertFalse(tracker.isWaiting("s1"))
    }

    @Test
    fun sessionsAreIsolated() {
        tracker.onExecutionEnded("s1", snapshot(1))
        tracker.onExecutionEnded("s2", snapshot(2))

        tracker.onMessageId("s2", "msg_2")

        assertEquals(listOf("s2" to 2), fired.map { it.first to it.second.turnNumber })
        assertTrue(tracker.isWaiting("s1"))
    }

    @Test
    fun debouncesASecondTriggerInsideTheWindow() {
        tracker.onExecutionEnded("s1", snapshot(1))
        tracker.onMessageId("s1", "msg_1")
        assertEquals(1, fired.size)

        // Same turn, fired again within the debounce window: ignored.
        now += 100
        tracker.onExecutionEnded("s1", snapshot(1))
        tracker.onMessageId("s1", "msg_1b")
        assertEquals(1, fired.size)

        // Outside the window a new turn fires normally.
        now += 2_000
        tracker.onExecutionStarted("s1")
        tracker.onExecutionEnded("s1", snapshot(2))
        tracker.onMessageId("s1", "msg_2")
        assertEquals(2, fired.size)
    }

    @Test
    fun clearAllStopsPendingTimeouts() {
        tracker.onExecutionEnded("s1", snapshot(1))
        tracker.clearAll()

        scheduler.runPending()

        assertTrue(fired.isEmpty())
    }
}
