package com.zhucanle.opencodev2.ide.util

import com.intellij.openapi.diagnostic.Logger
import org.jetbrains.annotations.VisibleForTesting
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.Base64

/**
 * Utility for finding available ports for OpenCode server.
 * 
 * Port allocation strategy:
 * - Starts from port 4096 and increments until an available port is found
 * - Once a port is allocated, it is fixed for the lifetime of that session
 * - No scanning for existing servers - each project gets its own server instance
 */
object PortFinder {
    private val logger = Logger.getInstance(PortFinder::class.java)
    private const val START_PORT = 4096
    private const val MAX_ATTEMPTS = 100
    private const val CONNECT_TIMEOUT_MS = 5000
    private const val READ_TIMEOUT_MS = 5000

    /** Responses that prove an HTTP server is listening: 401/403 only mean it wants credentials. */
    private val HEALTHY_RESPONSE_CODES = setOf(200, 401, 403)

    /**
     * Finds an available port starting from START_PORT.
     * Checks both that the port is not bound AND that no OpenCode server is running on it.
     * @return An available port number
     * @throws IOException if no available port is found within MAX_ATTEMPTS
     */
    fun findAvailablePort(): Int {
        logger.info("Searching for available port starting from $START_PORT")
        for (port in START_PORT until START_PORT + MAX_ATTEMPTS) {
            if (isPortAvailable(port)) {
                logger.info("Found available port: $port")
                return port
            }
            logger.debug("Port $port is not available")
        }
        throw IOException("No available port found in range $START_PORT-${START_PORT + MAX_ATTEMPTS - 1}")
    }

    /**
     * Checks if a specific port is available for use (not bound by any process).
     * Checks availability by:
     * 1. Attempting to connect to 127.0.0.1 (If successful, it's occupied)
     * 2. Attempting to bind specifically to 127.0.0.1 (Explicit IPv4 check)
     */
    fun isPortAvailable(port: Int): Boolean {
        // Check 0: Try to connect to 127.0.0.1. If we can connect, someone is listening.
        if (isPortOpen("127.0.0.1", port, timeoutMs = 100)) {
            return false
        }

        // Check 1: Explicit IPv4 localhost bind check
        return try {
            ServerSocket().use { s ->
                s.reuseAddress = false
                s.bind(InetSocketAddress("127.0.0.1", port))
                true
            }
        } catch (e: IOException) {
            false
        }
    }

    /**
     * Checks if an OpenCode v2 server is running on the specified port by probing its open
     * `GET /api/info` route.
     * Uses Proxy.NO_PROXY to bypass system proxy settings.
     * Supports HTTP Basic Authentication if credentials are provided; a 401/403 answer still
     * proves a server is present (it just requires auth).
     */
    fun isOpenCodeRunningOnPort(
        port: Int,
        hostname: String = "127.0.0.1",
        username: String? = null,
        password: String? = null
    ): Boolean {
        val connection = try {
            openInfoConnection(hostname, port, username, password)
        } catch (e: Exception) {
            // Malformed host/port or blocked connect: nothing is listening there.
            logger.debug("Health check failed for $hostname:$port. Error: ${e.message}")
            return false
        }

        return try {
            val responseCode = connection.responseCode
            val healthy = responseCode in HEALTHY_RESPONSE_CODES
            if (!healthy) {
                logger.debug("Health check failed for $hostname:$port. Response code: $responseCode")
            }
            healthy
        } catch (e: IOException) {
            // responseCode throws when the port is taken by a non-HTTP service.
            logger.debug("Health check failed for $hostname:$port. Error: ${e.message}")
            false
        } finally {
            connection.disconnect()
        }
    }

    /** Opens the probe request; the caller owns the connection and must disconnect it. */
    private fun openInfoConnection(
        hostname: String,
        port: Int,
        username: String?,
        password: String?
    ): HttpURLConnection {
        val connection = URL("http://$hostname:$port/api/info")
            .openConnection(Proxy.NO_PROXY) as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.requestMethod = "GET"
        connection.instanceFollowRedirects = false

        if (username != null && password != null) {
            val credentials = "$username:$password"
            val encoded = Base64.getEncoder().encodeToString(credentials.toByteArray())
            connection.setRequestProperty("Authorization", "Basic $encoded")
        }
        return connection
    }

    /**
     * Waits for the specified port to be bound and accepting connections.
     * @param port The port to wait for
     * @param timeoutMs Maximum time to wait in milliseconds
     * @return true if port became available, false if timed out
     */
    @VisibleForTesting
    fun waitForPort(
        port: Int,
        host: String = "127.0.0.1",
        timeoutMs: Long = 10000,
        requireHealth: Boolean = true
    ): Boolean {
        logger.info("Waiting for port $host:$port (timeout=${timeoutMs}ms, requireHealth=$requireHealth)")
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val ready = if (requireHealth) {
                // Important: Health check implies HTTP connectivity and OpenCode readiness.
                isOpenCodeRunningOnPort(port, host)
            } else {
                isPortOpen(host, port)
            }

            if (ready) {
                logger.info("Port $host:$port is ready.")
                return true
            }
            try {
                Thread.sleep(200)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        logger.warn("Timed out waiting for port $host:$port after ${timeoutMs}ms")
        return false
    }

    private fun isPortOpen(host: String, port: Int, timeoutMs: Int = 1000): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        } catch (e: Exception) {
            false
        }
    }
}
