package com.zhucanle.opencodev2.ide.api

import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SseResponseCleanupTest {
    @Test
    fun failedHandshakeClosesItsResponseBody() = assertResponseClosed(503)

    @Test
    fun endedStreamClosesItsResponseBody() = assertResponseClosed(200)

    private fun assertResponseClosed(status: Int) {
        val closed = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val source = object : ForwardingSource(Buffer().writeUtf8(": ${"x".repeat(4096)}\n\n")) {
            override fun close() {
                super.close()
                closed.countDown()
            }
        }.buffer()
        val body = object : ResponseBody() {
            override fun contentType(): MediaType? = null
            override fun contentLength(): Long = -1
            override fun source(): BufferedSource = source
        }
        val transport = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(status).message("fixture").body(body).build()
        }.build()
        lateinit var listener: SseEventListener
        listener = SseEventListener("http://localhost:1", "/project", {}, {}, onDisconnected = {
            listener.disconnect()
            finished.countDown()
        })
        SseEventListener::class.java.getDeclaredField("client").apply { isAccessible = true }
            .set(listener, transport)
        try {
            listener.connect()
            assertTrue("Response processing must finish", finished.await(5, TimeUnit.SECONDS))
            assertTrue("Every response body must be closed", closed.await(1, TimeUnit.SECONDS))
        } finally {
            listener.disconnect()
            transport.dispatcher.executorService.shutdownNow()
            transport.connectionPool.evictAll()
        }
    }
}
