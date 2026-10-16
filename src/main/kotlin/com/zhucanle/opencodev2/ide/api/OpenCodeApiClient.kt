package com.zhucanle.opencodev2.ide.api

import com.zhucanle.opencodev2.ide.api.models.FileDiff
import com.zhucanle.opencodev2.ide.api.models.OpenCodeEvent
import com.zhucanle.opencodev2.ide.api.models.OpenCodeEventDeserializer
import com.zhucanle.opencodev2.ide.api.models.Session
import com.zhucanle.opencodev2.ide.util.PathUtil
import com.zhucanle.opencodev2.ide.util.ServerAuth
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.diagnostic.Logger
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * HTTP client for the opencode v2 server API.
 *
 * Every route lives under `/api/` and requires HTTP Basic auth (`opencode:<password>`); missing
 * auth is answered with HTTP 401. Responses are `{data: ...}` envelopes, unwrapped centrally by
 * [readData] / [readDataArray] so no caller deals with the envelope.
 */
open class OpenCodeApiClient(
    private val hostname: String,
    private val port: Int,
    private val username: String? = null,
    private val password: String? = null
) {

    private val logger = Logger.getInstance(OpenCodeApiClient::class.java)
    private val baseUrl = "http://$hostname:$port"

    // Make client protected so subclasses can potentially use it or override methods
    protected val client = OkHttpClient.Builder()
        .proxy(java.net.Proxy.NO_PROXY)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .apply {
            // opencode v2 requires Basic auth on every request once a password is known.
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

    private val gson: Gson = GsonBuilder()
        .registerTypeAdapter(OpenCodeEvent::class.java, OpenCodeEventDeserializer())
        .create()

    /**
     * List the sessions whose working directory is [directory]
     * (`GET /api/session?directory=`).
     */
    fun getSessions(directory: String): List<Session> {
        val url = "$baseUrl/api/session?directory=${encode(serverPath(directory))}"
        val type = object : TypeToken<List<Session>>() {}.type
        return readDataArray<Session>(url, type)
    }

    /**
     * The session currently active for [directory] (`GET /api/session/active`), or null when the
     * server answers with an empty `data` object.
     */
    fun getActiveSession(directory: String): Session? {
        val url = "$baseUrl/api/session/active?directory=${encode(serverPath(directory))}"
        return readSession(url)
    }

    /**
     * Get a specific session by ID (`GET /api/session/{id}`).
     */
    open fun getSession(sessionId: String, directory: String): Session? {
        val url = "$baseUrl/api/session/$sessionId?directory=${encode(serverPath(directory))}"
        return readSession(url)
    }

    /**
     * The per-file diff of a session turn (`GET /api/session/{id}/diff`).
     *
     * [fromMessageId] must be a **user** message id; the server rejects anything else with HTTP
     * 400. When it is null the `from` parameter is omitted and the server defaults to the turn of
     * the newest user message.
     */
    open fun getSessionDiff(sessionId: String, directory: String, fromMessageId: String? = null): List<FileDiff> {
        var url = "$baseUrl/api/session/$sessionId/diff?directory=${encode(serverPath(directory))}"
        if (fromMessageId != null) url += "&from=${encode(fromMessageId)}"
        val type = object : TypeToken<List<FileDiff>>() {}.type
        val diffs = readDataArray<FileDiff>(url, type)
        if (diffs.isNotEmpty()) {
            logger.info("[API] Received ${diffs.size} diffs for session $sessionId")
        }
        return diffs
    }

    /**
     * Liveness probe backed by `GET /api/info`.
     *
     * `/api/info` takes no parameters, so [directory] is accepted only to keep the existing call
     * sites unchanged.
     */
    fun checkHealth(@Suppress("UNUSED_PARAMETER") directory: String): Boolean {
        return getRaw("$baseUrl/api/info") != null
    }

    /**
     * Outcome of the authenticated credential probe ([probeAuth]): whether the server accepted the
     * credentials, explicitly rejected them, or answered something that says nothing about them.
     */
    enum class AuthProbeResult {
        /** 2xx: the credentials were accepted. */
        ACCEPTED,

        /** 401/403: the server rejected the credentials. */
        REJECTED,

        /** Any other status or a network failure; not an authentication signal. */
        INDETERMINATE
    }

    /**
     * Typed credential probe: performs a real GET on an authenticated `/api/session` route.
     *
     * `/api/info` is anonymous, so it cannot detect a wrong/missing password; every `/api/session*`
     * route is behind HTTP Basic auth and answers 401/403 when the credentials are bad. Any other
     * non-2xx status (or a network failure) says nothing about the credentials and is reported as
     * [AuthProbeResult.INDETERMINATE] so callers do not misclassify it as a rejected password.
     */
    fun probeAuth(directory: String): AuthProbeResult {
        val url = "$baseUrl/api/session?directory=${encode(serverPath(directory))}"
        val code = getStatusCode(url) ?: return AuthProbeResult.INDETERMINATE
        return when {
            code in 200..299 -> AuthProbeResult.ACCEPTED
            code == 401 || code == 403 -> AuthProbeResult.REJECTED
            else -> AuthProbeResult.INDETERMINATE
        }
    }

    /** Boolean convenience wrapper around [probeAuth]: true **iff** the server answered 2xx. */
    fun checkAuth(directory: String): Boolean = probeAuth(directory) == AuthProbeResult.ACCEPTED

    /**
     * The server version reported by `GET /api/info`, used by the connect-time version gate.
     *
     * Unlike the `/api/session*` routes, `/api/info` returns the plain
     * `{version, pid, urls, paths, capabilities}` object rather than a `{data: ...}` envelope.
     * Returns null when the request fails or the `version` field is absent.
     */
    fun getServerVersion(): String? {
        val body = getRaw("$baseUrl/api/info") ?: return null
        val root = try {
            JsonParser.parseString(body)
        } catch (e: Exception) {
            logger.debug("[API] Malformed /api/info JSON: ${e.message}")
            return null
        }
        if (!root.isJsonObject) return null
        val version = root.asJsonObject.get("version") ?: return null
        return if (version.isJsonPrimitive) version.asString else null
    }

    fun createEventListener(
        directory: String,
        onEvent: (OpenCodeEvent) -> Unit,
        onError: (Throwable) -> Unit,
        onConnected: () -> Unit = {},
        onDisconnected: () -> Unit = {}
    ): SseEventListener {
        val serverPath = PathUtil.toOpenCodeServerPath(directory)
        return SseEventListener(baseUrl, serverPath, onEvent, onError, onConnected, onDisconnected, username, password)
    }

    // ==================== Response envelope handling ====================

    /**
     * Fetches [url] and returns the `data` element of the v2 `{data: ...}` envelope, or null when
     * the request fails (non-2xx, network error) or the envelope carries no `data` element.
     */
    private fun readData(url: String): JsonElement? {
        val body = getRaw(url) ?: return null
        val root = try {
            JsonParser.parseString(body)
        } catch (e: Exception) {
            logger.warn("[API] Malformed JSON from $url: ${e.message}")
            return null
        }
        if (!root.isJsonObject) {
            logger.warn("[API] Unexpected non-object envelope from $url")
            return null
        }
        return root.asJsonObject.get("data")
    }

    /**
     * Fetches [url], unwraps the `data` element, and maps it to a list of [type].
     * Returns an empty list for a missing/failed response or a `{"data":[]}` envelope.
     */
    private fun <T> readDataArray(url: String, type: java.lang.reflect.Type): List<T> {
        val data = readData(url) ?: return emptyList()
        if (!data.isJsonArray) return emptyList()
        return try {
            gson.fromJson<List<T>>(data, type) ?: emptyList()
        } catch (e: Exception) {
            logger.warn("[API] Failed to parse data array from $url: ${e.message}")
            emptyList()
        }
    }

    /**
     * Fetches [url] and maps the `data` object to a [Session]. Returns null when the request
     * fails, the envelope has no `data`, or the server reports "no session" as `{"data":{}}`.
     */
    private fun readSession(url: String): Session? {
        val data = readData(url) ?: return null
        if (!data.isJsonObject || data.asJsonObject.size() == 0) return null
        return try {
            gson.fromJson(data, Session::class.java)
        } catch (e: Exception) {
            logger.warn("[API] Failed to parse session from $url: ${e.message}")
            null
        }
    }

    /**
     * Executes a GET and returns the raw UTF-8 body, or null on non-2xx / network failure.
     */
    private fun getRaw(url: String): String? {
        val request = Request.Builder().url(url).get().build()
        val startTime = System.currentTimeMillis()
        return try {
            client.newCall(request).execute().use { response ->
                val duration = System.currentTimeMillis() - startTime
                if (response.isSuccessful) {
                    logger.debug("[API] Success: GET $url (took ${duration}ms)")
                    // Force UTF-8 decoding: the server may omit a charset and would otherwise
                    // mangle non-ASCII content.
                    response.body?.bytes()?.toString(Charsets.UTF_8)
                } else {
                    val bodyPreview = try { response.peekBody(1024).string() } catch (_: Exception) { "no body" }
                    logger.warn("[API] Failed: GET $url (HTTP ${response.code}, took ${duration}ms). Response: $bodyPreview")
                    null
                }
            }
        } catch (e: Exception) {
            logger.debug("[API] Error: GET $url -> ${e.message}")
            null
        }
    }

    /**
     * Executes a GET and returns the HTTP status code, or null on network failure.
     *
     * Unlike [getRaw] this reports the code even for non-2xx responses, which is what the auth
     * probe needs to distinguish "authenticated" (2xx) from "rejected" (401/403).
     */
    private fun getStatusCode(url: String): Int? {
        val request = Request.Builder().url(url).get().build()
        return try {
            client.newCall(request).execute().use { response -> response.code }
        } catch (e: Exception) {
            logger.debug("[API] Error: GET $url -> ${e.message}")
            null
        }
    }

    private fun serverPath(directory: String): String = PathUtil.toOpenCodeServerPath(directory)

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    companion object {
        /**
         * Major component of a dotted opencode version string: `"2.0.29"` -> `2`.
         *
         * Also accepts a bare major (`"2"`) and ignores any pre-release suffix
         * (`"2.1.0-beta.1"` -> `2`). Returns null when the version is missing or does not start
         * with a number, so callers can skip the check rather than warn spuriously.
         */
        fun parseMajorVersion(version: String?): Int? {
            val head = version?.trim()?.substringBefore('.')?.takeIf { it.isNotEmpty() } ?: return null
            return head.toIntOrNull()
        }
    }
}
