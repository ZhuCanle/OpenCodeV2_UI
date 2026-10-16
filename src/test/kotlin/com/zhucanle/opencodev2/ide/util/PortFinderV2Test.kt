package com.zhucanle.opencodev2.ide.util

import com.zhucanle.opencodev2.ide.integration.FakeOpenCodeServer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2 liveness-probe tests for [PortFinder.isOpenCodeRunningOnPort].
 *
 * The probe moved from the v1 `/global/health` route to the v2 open `GET /api/info` route; an
 * HTTP 200 means healthy, and 401/403 still means "a server is present but wants auth".
 */
class PortFinderV2Test {

    @Test
    fun reportsRunningWhenApiInfoAnswers200() {
        val fake = FakeOpenCodeServer(0)
        fake.start()
        try {
            assertTrue(PortFinder.isOpenCodeRunningOnPort(fake.activePort))
        } finally {
            fake.stop()
        }
    }

    @Test
    fun treatsAuthRequiredAsRunning() {
        val fake = FakeOpenCodeServer(0)
        fake.requireAuth(FakeOpenCodeServer.DEFAULT_USER, FakeOpenCodeServer.DEFAULT_PASSWORD)
        fake.start()
        try {
            // No credentials are sent, so the server answers 401: still "present".
            assertTrue(PortFinder.isOpenCodeRunningOnPort(fake.activePort))
        } finally {
            fake.stop()
        }
    }

    @Test
    fun reportsNotRunningWhenNothingListens() {
        assertFalse(PortFinder.isOpenCodeRunningOnPort(1))
    }
}
