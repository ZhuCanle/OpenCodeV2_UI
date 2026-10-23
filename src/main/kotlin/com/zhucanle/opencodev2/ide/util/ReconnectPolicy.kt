package com.zhucanle.opencodev2.ide.util

/**
 * Decides what the connection manager should do after a failed health check.
 *
 * Kept free of IntelliJ types so the decision table is unit-testable. The caller is responsible
 * for ignoring failures when it never connected in the first place.
 */
internal class ReconnectPolicy(
    private val rediscoverAfterFailures: Int = 2,
    private val askUserAfterFailures: Int = 6,
    private val maxRediscoveries: Int = 2,
    private val rediscoveryIntervalMs: Long = 15_000L
) {

    enum class Action {
        /** Keep retrying the current endpoint. */
        RETRY,

        /** Ask the service CLI for the current endpoint and reconnect when it changed. */
        REDISCOVER,

        /** No progress after repeated attempts: ask the user. */
        ASK_USER
    }

    var failures: Int = 0
        private set

    private var rediscoveries: Int = 0
    private var lastRediscoveryAt: Long? = null
    private var askUserShown: Boolean = false

    /**
     * Records a failed health check and returns the next action.
     *
     * @param isLocal true for a service-managed local endpoint (rediscovery is possible).
     * @param nowMs current time in milliseconds, injected for testability.
     */
    fun onFailure(isLocal: Boolean, nowMs: Long): Action {
        failures++

        if (isLocal && rediscoveries < maxRediscoveries && intervalElapsed(nowMs)) {
            if (failures < rediscoverAfterFailures) return Action.RETRY
            rediscoveries++
            lastRediscoveryAt = nowMs
            return Action.REDISCOVER
        }

        if (failures >= askUserAfterFailures && !askUserShown) {
            askUserShown = true
            return Action.ASK_USER
        }

        return Action.RETRY
    }

    /** Called after a successful connection: everything rearms for the next outage. */
    fun onConnected() = reset()

    /** Rearms the policy, e.g. when a new connection attempt starts. */
    fun reset() {
        failures = 0
        rediscoveries = 0
        lastRediscoveryAt = null
        askUserShown = false
    }

    private fun intervalElapsed(nowMs: Long): Boolean {
        val last = lastRediscoveryAt ?: return true
        return nowMs - last >= rediscoveryIntervalMs
    }
}
