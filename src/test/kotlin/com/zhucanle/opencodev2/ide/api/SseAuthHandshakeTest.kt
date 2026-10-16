package com.zhucanle.opencodev2.ide.api

import com.zhucanle.opencodev2.ide.integration.FakeOpenCodeServer
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Regression test for the `/api/event` SSE handshake credentials.
 *
 * Production builds the listener from the connect path, which historically left the Basic-auth
 * username unset while a password was present (the local service only contributes a password). The
 * handshake interceptor must therefore attach `Authorization` whenever a password exists, mirroring
 * [OpenCodeApiClient]. Otherwise the local path connects anonymously, `/api/event` answers 401 and
 * every SSE-driven v2 feature (turn tracking, diff review, notifications) stays dead.
 */
class SseAuthHandshakeTest {

    private lateinit var server: FakeOpenCodeServer

    @Before
    fun setUp() {
        server = FakeOpenCodeServer(0)
        server.start()
        server.requireAuth(FakeOpenCodeServer.DEFAULT_USER, FakeOpenCodeServer.DEFAULT_PASSWORD)
    }

    @After
    fun tearDown() {
        server.stop()
    }

    @Test
    fun connectsWithPasswordWhenUsernameIsNull() {
        // Exactly the credential state the local connect path produces: a password from service
        // discovery with the username never assigned. The test must NOT pass an explicit username;
        // the point is that a null username must not disable auth.
        val client = OpenCodeApiClient(
            "127.0.0.1",
            server.activePort,
            username = null,
            password = FakeOpenCodeServer.DEFAULT_PASSWORD
        )

        val connected = AtomicBoolean(false)
        val error = AtomicReference<Throwable?>(null)
        val listener = client.createEventListener(
            directory = "/tmp",
            onEvent = {},
            onError = { error.set(it) },
            onConnected = { connected.set(true) }
        )

        try {
            listener.connect()

            val deadline = System.currentTimeMillis() + 5000
            while (!connected.get() && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
            }

            assertTrue(
                "SSE handshake must authenticate when only the password is known (username == null); " +
                    "last error=${error.get()?.message}",
                connected.get()
            )
            assertFalse("onError must not fire on a successful handshake", error.get() != null)
        } finally {
            listener.disconnect()
        }
    }
}
