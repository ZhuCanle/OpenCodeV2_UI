package com.zhucanle.opencodev2.ide.util

import com.zhucanle.opencodev2.ide.util.ReconnectPolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure unit tests for [ReconnectPolicy].
 *
 * The policy decides what the connection manager should do after a failed health check:
 * keep retrying, rediscover the local service, or ask the user.
 */
class ReconnectPolicyTest {

    private fun policy() = ReconnectPolicy(
        rediscoverAfterFailures = 2,
        askUserAfterFailures = 6,
        maxRediscoveries = 2,
        rediscoveryIntervalMs = 15_000L
    )

    @Test
    fun localRetriesOnceThenRediscovers() {
        val policy = policy()
        assertEquals(Action.RETRY, policy.onFailure(isLocal = true, nowMs = 0L))
        assertEquals(Action.REDISCOVER, policy.onFailure(isLocal = true, nowMs = 5_000L))
    }

    @Test
    fun localRediscoveryIsThrottled() {
        val policy = policy()
        policy.onFailure(isLocal = true, nowMs = 0L)
        assertEquals(Action.REDISCOVER, policy.onFailure(isLocal = true, nowMs = 5_000L))

        assertEquals(Action.RETRY, policy.onFailure(isLocal = true, nowMs = 10_000L))
        assertEquals(Action.REDISCOVER, policy.onFailure(isLocal = true, nowMs = 20_000L))
    }

    @Test
    fun localAsksTheUserOnceRediscoveriesAreExhausted() {
        val policy = policy()
        assertEquals(Action.RETRY, policy.onFailure(isLocal = true, nowMs = 0L))
        assertEquals(Action.REDISCOVER, policy.onFailure(isLocal = true, nowMs = 5_000L))
        assertEquals(Action.REDISCOVER, policy.onFailure(isLocal = true, nowMs = 20_000L))

        assertEquals(Action.RETRY, policy.onFailure(isLocal = true, nowMs = 35_000L))
        assertEquals(Action.RETRY, policy.onFailure(isLocal = true, nowMs = 40_000L))
        assertEquals(Action.ASK_USER, policy.onFailure(isLocal = true, nowMs = 45_000L))
        assertEquals(Action.RETRY, policy.onFailure(isLocal = true, nowMs = 50_000L))
    }

    @Test
    fun remoteNeverRediscovers() {
        val policy = policy()
        repeat(5) { attempt ->
            assertEquals(Action.RETRY, policy.onFailure(isLocal = false, nowMs = attempt * 5_000L))
        }
        assertEquals(Action.ASK_USER, policy.onFailure(isLocal = false, nowMs = 25_000L))
    }

    @Test
    fun remoteAsksTheUserOnlyOnce() {
        val policy = policy()
        repeat(6) { attempt -> policy.onFailure(isLocal = false, nowMs = attempt * 5_000L) }

        assertEquals(Action.RETRY, policy.onFailure(isLocal = false, nowMs = 60_000L))
    }

    @Test
    fun connectingAgainResetsThePolicy() {
        val policy = policy()
        repeat(6) { attempt -> policy.onFailure(isLocal = false, nowMs = attempt * 5_000L) }

        policy.onConnected()

        assertEquals(0, policy.failures)
        assertEquals(Action.RETRY, policy.onFailure(isLocal = false, nowMs = 100_000L))
    }

    @Test
    fun resetRearmsTheUserPrompt() {
        val policy = policy()
        repeat(6) { attempt -> policy.onFailure(isLocal = false, nowMs = attempt * 5_000L) }
        policy.reset()

        repeat(5) { attempt -> assertEquals(Action.RETRY, policy.onFailure(isLocal = false, nowMs = 200_000L + attempt * 5_000L)) }
        assertEquals(Action.ASK_USER, policy.onFailure(isLocal = false, nowMs = 230_000L))
    }
}
