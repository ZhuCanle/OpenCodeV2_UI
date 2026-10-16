package com.zhucanle.opencodev2.ide.integration

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.OutputStream
import java.net.InetSocketAddress
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * In-process fake of the opencode v2 server.
 *
 * Serves the `/api/` routes the plugin talks to. opencode v2 requires
 * `Authorization: Basic opencode:<password>` on every `/api/` request, but the plugin resolves that
 * password from service discovery rather than the connect dialog, so auth is only enforced when a
 * test opts in via [requireAuth]. Responses are the v2 `{data: ...}` envelopes, and every setter
 * takes the *full envelope body* so tests stay explicit about the wire shape they feed the client.
 */
class FakeOpenCodeServer(val port: Int) {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
    private val sseClients = CopyOnWriteArrayList<OutputStream>()

    /** `GET /api/session/{id}/diff` response bodies, keyed by the `from` message id. */
    private val diffResponses = ConcurrentHashMap<String, String>()
    private val diffDelays = ConcurrentHashMap<String, Long>()

    /**
     * Body of `GET /api/session/{id}/diff` when `from` is omitted, i.e. the newest user turn. This
     * is the shape the plugin actually uses on v2 (it never sends `from`).
     */
    private var currentDiffResponse = EMPTY_DIFF

    private var infoResponse = DEFAULT_INFO
    private var sessionsResponse = """{"data":[]}"""
    private var activeSessionResponse = """{"data":{}}"""

    /** Query strings received on `GET /api/session/{id}/diff`. */
    val diffQueries = CopyOnWriteArrayList<String>()

    /** Expected `Authorization` header value; null means auth is not enforced. */
    private var expectedAuth: String? = null

    val activePort: Int
        get() = server.address.port

    init {
        // Catch-all handler for debugging
        server.createContext("/") { ex ->
            println("  [FakeServer] Unhandled Request: ${ex.requestURI}")
            respond(ex, 404, "No context found for request", "text/plain")
        }

        server.createContext("/api/info") { ex ->
            withAuth(ex) { respondJson(ex, 200, infoResponse) }
        }

        server.createContext("/api/session/active") { ex ->
            withAuth(ex) { respondJson(ex, 200, activeSessionResponse) }
        }

        server.createContext("/api/session") { ex ->
            withAuth(ex) {
                val path = ex.requestURI.path
                when {
                    path == "/api/session" -> respondJson(ex, 200, sessionsResponse)
                    path.endsWith("/diff") -> respondDiff(ex)
                    else -> respond(ex, 404, "not found", "text/plain")
                }
            }
        }

        server.createContext("/api/event") { ex ->
            withAuth(ex) { streamEvents(ex) }
        }

        server.executor = Executors.newCachedThreadPool()
    }

    fun start() {
        server.start()
        println("  [FakeServer] Started on port $port")
    }

    fun stop() {
        server.stop(0)
        println("  [FakeServer] Stopped")
    }

    /** Require this Basic auth credential pair; requests without it get HTTP 401. */
    fun requireAuth(username: String, password: String) {
        expectedAuth = basicAuth(username, password)
    }

    /** True once an SSE consumer is attached to `GET /api/event`. */
    fun hasSseClient(): Boolean = sseClients.isNotEmpty()

    fun setInfoResponse(json: String) {
        infoResponse = json
    }

    /** Provide the raw body of `GET /api/session?directory=...` (a `{data:[...]}` envelope). */
    fun setSessions(json: String) {
        sessionsResponse = json
    }

    /** Provide the raw body of `GET /api/session/active` (a `{data:...}` envelope). */
    fun setActiveSession(json: String) {
        activeSessionResponse = json
    }

    /**
     * Provide the raw body of `GET /api/session/{id}/diff` when `from` is omitted
     * (the newest user turn, which is what the plugin requests on v2), as a `{data:[...]}` envelope.
     */
    fun setCurrentDiffResponse(json: String) {
        currentDiffResponse = json
    }

    /**
     * Provide the raw body of `GET /api/session/{id}/diff?from=<fromMessageId>`
     * (a `{data:[...]}` envelope).
     */
    fun setDiffResponse(fromMessageId: String, json: String, delayMs: Long = 0) {
        diffResponses[fromMessageId] = json
        if (delayMs > 0) diffDelays[fromMessageId] = delayMs
    }

    fun broadcast(json: String) {
        val msg = "data: $json\n\n".toByteArray(Charsets.UTF_8)
        for (client in sseClients) {
            try {
                client.write(msg)
                client.flush()
            } catch (e: Exception) {
                sseClients.remove(client)
            }
        }
        println("  [FakeServer] Broadcast: $json")
    }

    private fun respondDiff(ex: HttpExchange) {
        val query = ex.requestURI.query ?: ""
        diffQueries.add(query)

        val from = query.split("&")
            .firstOrNull { it.startsWith("from=") }
            ?.substringAfter("=")
        // `from` is optional: without it the server defaults to the newest user turn.
        val body = if (from == null) currentDiffResponse else diffResponses[from] ?: EMPTY_DIFF
        val delay = from?.let { diffDelays[it] } ?: 0L

        if (delay > 0) Thread.sleep(delay)

        println("  [FakeServer] GET /api/session/.../diff from=$from -> $body")
        respondJson(ex, 200, body)
    }

    private fun streamEvents(ex: HttpExchange) {
        ex.responseHeaders.add("Content-Type", "text/event-stream")
        ex.responseHeaders.add("Cache-Control", "no-cache")
        ex.sendResponseHeaders(200, 0)

        val os = ex.responseBody
        sseClients.add(os)
        println("  [FakeServer] SSE client connected")

        try {
            // Initial ping to confirm connection
            os.write(": ping\n\n".toByteArray())
            os.flush()

            // Keep this thread alive but allow interruption.
            while (!Thread.currentThread().isInterrupted) {
                Thread.sleep(1000)
            }
        } catch (e: Exception) {
            // Client disconnected.
        } finally {
            sseClients.remove(os)
        }
    }

    private fun withAuth(ex: HttpExchange, block: () -> Unit) {
        val expected = expectedAuth
        if (expected == null || ex.requestHeaders.getFirst("Authorization") == expected) {
            block()
        } else {
            println("  [FakeServer] 401 Unauthorized: ${ex.requestURI}")
            respondJson(ex, 401, UNAUTHORIZED)
        }
    }

    private fun respondJson(ex: HttpExchange, code: Int, body: String) {
        respond(ex, code, body, "application/json")
    }

    private fun respond(ex: HttpExchange, code: Int, body: String, contentType: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", contentType)
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    companion object {
        const val DEFAULT_USER = "opencode"
        const val DEFAULT_PASSWORD = "pw"

        private const val DEFAULT_INFO =
            """{"version":"2.0.29","pid":1,"urls":["http://127.0.0.1:0"],""" +
                """"paths":{"tmp":"/tmp"},"capabilities":{"persistentPty":true}}"""
        private const val EMPTY_DIFF = """{"data":[]}"""
        private const val UNAUTHORIZED =
            """{"_tag":"UnauthorizedError","message":"Authentication required"}"""

        private fun basicAuth(username: String, password: String): String {
            val raw = "$username:$password".toByteArray(Charsets.UTF_8)
            return "Basic " + Base64.getEncoder().encodeToString(raw)
        }
    }
}
