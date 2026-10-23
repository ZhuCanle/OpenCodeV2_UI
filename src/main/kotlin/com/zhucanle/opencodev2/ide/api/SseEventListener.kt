package com.zhucanle.opencodev2.ide.api

import com.zhucanle.opencodev2.ide.api.models.OpenCodeEvent
import com.zhucanle.opencodev2.ide.api.models.OpenCodeEventDeserializer
import com.zhucanle.opencodev2.ide.util.PathUtil
import com.zhucanle.opencodev2.ide.util.ServerAuth
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.concurrency.AppExecutorUtil
import okhttp3.*
import java.io.IOException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Raised when the `/api/event` handshake is rejected with HTTP 401/403.
 *
 * It is a distinct type so the caller can surface an actionable "check the server password"
 * message instead of treating it as a generic transient connection failure.
 */
class SseAuthException(val statusCode: Int) : IOException("SSE handshake rejected with HTTP $statusCode")

/**
 * SSE (Server-Sent Events) listener for OpenCode server.
 */
class SseEventListener(
    private val baseUrl: String,
    private val projectPath: String,
    private val onEvent: (OpenCodeEvent) -> Unit,
    private val onError: (Throwable) -> Unit,
    private val onConnected: () -> Unit = {},
    private val onDisconnected: () -> Unit = {},
    private val username: String? = null,
    private val password: String? = null
) {
    private val logger = Logger.getInstance(SseEventListener::class.java)

    companion object {
        private const val SSE_RECONNECT_DELAY_MS = 3000L

        private val frameGson = GsonBuilder()
            .registerTypeAdapter(OpenCodeEvent::class.java, OpenCodeEventDeserializer())
            .create()

        /**
         * Decodes a single `/api/event` frame payload (the text after `data:`) into an
         * [OpenCodeEvent], or null when it is not a parseable event object.
         *
         * The v2 wire format sends a **raw JSON object** on the `data:` line (see the wire-format
         * doc); there is no `event:` line and the payload is not double-encoded.
         */
        fun decodeFrame(payload: String): OpenCodeEvent? {
            return try {
                frameGson.fromJson(payload, OpenCodeEvent::class.java)
            } catch (_: Exception) {
                null
            }
        }

        /** [decodeFrame] for an already parsed frame, so a frame is parsed only once. */
        internal fun decodeFrame(frame: JsonElement?): OpenCodeEvent? =
            if (frame == null) {
                null
            } else {
                try {
                    frameGson.fromJson(frame, OpenCodeEvent::class.java)
                } catch (_: Exception) {
                    null
                }
            }

        /** Parses a raw `data:` payload, or null when it is not valid JSON. */
        internal fun parseFrame(payload: String): JsonElement? = try {
            JsonParser.parseString(payload)
        } catch (_: Exception) {
            null
        }

        /**
         * True when the frame belongs to [directory].
         *
         * The server does **not** filter `/api/event` by its `directory` query parameter: every
         * subscriber receives every directory's events, each tagged with `location.directory`.
         * Frames without a usable location are kept — they cannot be attributed, and the decoder
         * already drops the ones the plugin does not handle.
         */
        internal fun isScopedTo(payload: String, directory: String): Boolean =
            isScopedTo(parseFrame(payload), directory)

        /** [isScopedTo] for an already parsed frame. */
        internal fun isScopedTo(frame: JsonElement?, directory: String): Boolean {
            val locationDirectory = try {
                if (frame == null || !frame.isJsonObject) return true
                val location = frame.asJsonObject.get("location")
                if (location == null || !location.isJsonObject) return true
                val dir = location.asJsonObject.get("directory")
                if (dir == null || !dir.isJsonPrimitive) return true
                dir.asString.takeIf { it.isNotBlank() }
            } catch (_: Exception) {
                return true
            } ?: return true
            return PathUtil.sameDirectory(locationDirectory, directory)
        }
    }

    private val client = OkHttpClient.Builder()
        .proxy(java.net.Proxy.NO_PROXY)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .apply {
            // Attach Basic auth whenever a password is known, regardless of whether the caller also
            // set a username: the local path only resolves a password from service discovery, so a
            // null username must not silently downgrade the handshake to anonymous (HTTP 401).
            if (password != null) {
                val user = username ?: ServerAuth.DEFAULT_USERNAME
                addInterceptor { chain ->
                    val request = chain.request().newBuilder()
                        .header("Authorization", Credentials.basic(user, password))
                        .build()
                    chain.proceed(request)
                }
            }
        }
        .build()

    private var call: Call? = null
    private val isConnected = AtomicBoolean(false)
    private val shouldReconnect = AtomicBoolean(true)
    private var reconnectTask: ScheduledFuture<*>? = null
    private var reconnectAttemptCount = 0

    fun connect() {
        if (isConnected.get()) return
        shouldReconnect.set(true)
        reconnectAttemptCount = 0
        doConnect()
    }

    private fun doConnect() {
        // A disconnect can race a scheduled reconnect: without this check the new call would be
        // assigned after disconnect() cancelled the previous one, leaving a stream nobody owns.
        if (!shouldReconnect.get()) return
        reconnectAttemptCount++
        val encodedPath = java.net.URLEncoder.encode(projectPath, "UTF-8")
        val url = "$baseUrl/api/event?directory=$encodedPath"

        logger.info("[SSE] Connecting to $url (attempt #$reconnectAttemptCount)...")
        val request = Request.Builder()
            .url(url)
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            .build()

        call = client.newCall(request)
        call?.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                isConnected.set(false)
                logger.warn("[SSE] Connection failure: ${e.message} (attempt #$reconnectAttemptCount)")
                onError(e)
                onDisconnected()
                if (shouldReconnect.get()) scheduleReconnect()
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    isConnected.set(false)
                    val body = try { response.peekBody(1024).string() } catch (_: Exception) { "no body" }
                    logger.error("[SSE] Handshake failed: HTTP ${response.code} (attempt #$reconnectAttemptCount). Response: $body")
                    onError(handshakeError(response.code))
                    onDisconnected()
                    if (shouldReconnect.get()) scheduleReconnect()
                    return
                }

                // The call may have been cancelled (or the listener disconnected) while the
                // handshake was in flight; consuming the stream then would leak it.
                if (call.isCanceled() || !shouldReconnect.get()) {
                    response.close()
                    return
                }

                isConnected.set(true)
                reconnectAttemptCount = 0
                onConnected()
                logger.info("[SSE] Connection established")

                response.body?.source()?.let { source ->
                    try {
                        while (!source.exhausted() && !call.isCanceled() && shouldReconnect.get()) {
                            val line = source.readUtf8Line() ?: break
                            // Frames are either `data: <raw JSON object>` or `: heartbeat` comments;
                            // only the data lines carry an event.
                            if (line.startsWith("data:")) {
                                val json = line.substring(5).trim()
                                if (json.isNotEmpty()) parseAndDispatchEvent(json)
                            }
                        }
                    } catch (e: Exception) {
                        if (!call.isCanceled()) logger.warn("[SSE] Stream interrupted: ${e.message}")
                    } finally {
                        isConnected.set(false)
                        onDisconnected()
                        if (shouldReconnect.get() && !call.isCanceled()) {
                            logger.info("[SSE] Connection lost. Scheduling reconnect.")
                            scheduleReconnect()
                        }
                    }
                }
            }
        })
    }

    private fun parseAndDispatchEvent(json: String) {
        try {
            logger.debug("[SSE-Raw] $json")
            // Parse once: the scope check and the decoder used to parse the same frame separately.
            val frame = parseFrame(json)
            if (!isScopedTo(frame, projectPath)) {
                logger.debug("[SSE] Ignoring event from another directory")
                return
            }
            val event = decodeFrame(frame)
            if (event != null) onEvent(event)
        } catch (e: Exception) {
            logger.debug("[SSE] Parse error: ${e.message}. JSON: $json")
        }
    }

    /**
     * Maps a non-2xx handshake response to the error surfaced to the caller. 401/403 become an
     * [SseAuthException] so the UI can point the user at the server password.
     */
    private fun handshakeError(statusCode: Int): IOException =
        if (statusCode == 401 || statusCode == 403) SseAuthException(statusCode)
        else IOException("SSE failed: $statusCode")

    private fun scheduleReconnect() {
        if (!shouldReconnect.get()) return
        reconnectTask?.cancel(false)
        reconnectTask = AppExecutorUtil.getAppScheduledExecutorService().schedule({
            if (shouldReconnect.get()) doConnect()
        }, SSE_RECONNECT_DELAY_MS, TimeUnit.MILLISECONDS)
    }

    fun disconnect() {
        shouldReconnect.set(false)
        reconnectTask?.cancel(true)
        call?.cancel()
        isConnected.set(false)
    }
}
