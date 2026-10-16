package com.zhucanle.opencodev2.ide.util

import com.google.gson.JsonParser
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.nio.charset.Charset

/**
 * Endpoint of a running OpenCode v2 background service.
 *
 * @param host host the service listens on, e.g. `127.0.0.1`
 * @param port port the service listens on
 * @param password password from the service config file, or null when the service is unauthenticated
 */
data class ServiceEndpoint(
    val host: String,
    val port: Int,
    val password: String?,
)

/**
 * Discovers the OpenCode v2 background service.
 *
 * The discovery is based on the CLI itself:
 * - `<binary> service status` prints the service URL when it is already running.
 * - `<binary> service start` starts the service when it is not running.
 * - The service password lives in `service.json` inside the OpenCode config directory.
 *
 * Only [parseServiceUrl] and [readPassword] touch nothing but their inputs; `discover` and
 * `ensureRunning` shell out to the CLI.
 */
object OpenCodeServiceDiscovery {
    private val logger = Logger.getInstance(OpenCodeServiceDiscovery::class.java)

    private const val SERVICE_STATUS_TIMEOUT_MS = 5_000
    private const val SERVICE_START_TIMEOUT_MS = 30_000

    /** Bounded retry for reading the password just after the service is started. */
    private const val PASSWORD_READ_ATTEMPTS = 5
    private const val PASSWORD_READ_DELAY_MS = 200L

    private val SERVICE_URL_REGEX = Regex("""https?://([^:\s/]+):(\d+)""")

    private val SERVICE_JSON_NAME = "service.json"

    /**
     * Extracts the first `host:port` pair of an HTTP(S) URL found in [output].
     * Returns null when no URL is present (e.g. the service is not running).
     */
    fun parseServiceUrl(output: String): Pair<String, Int>? {
        val match = SERVICE_URL_REGEX.find(output) ?: return null
        val host = match.groupValues[1]
        val port = match.groupValues[2].toIntOrNull() ?: return null
        return host to port
    }

    /**
     * The OpenCode configuration directory:
     * - Windows: `%APPDATA%\opencode`
     * - otherwise: `$XDG_CONFIG_HOME/opencode`, falling back to `~/.config/opencode`
     */
    fun defaultConfigDir(): File {
        val osName = System.getProperty("os.name").orEmpty().lowercase()
        if (osName.contains("win")) {
            val appData = System.getenv("APPDATA")
            if (!appData.isNullOrBlank()) {
                return File(appData, "opencode")
            }
            return File(System.getProperty("user.home"), "opencode")
        }

        val xdgConfigHome = System.getenv("XDG_CONFIG_HOME")
        val configRoot = if (!xdgConfigHome.isNullOrBlank()) {
            File(xdgConfigHome)
        } else {
            File(System.getProperty("user.home"), ".config")
        }
        return File(configRoot, "opencode")
    }

    /**
     * Reads the service password from `service.json` in [configDir].
     * Returns null when the file is missing, unreadable, malformed, or has no password.
     */
    fun readPassword(configDir: File = defaultConfigDir()): String? {
        val serviceFile = File(configDir, SERVICE_JSON_NAME)
        if (!serviceFile.isFile) {
            return null
        }
        return try {
            val root = JsonParser.parseString(serviceFile.readText())
            if (!root.isJsonObject) {
                return null
            }
            val password = root.asJsonObject.get("password")
            if (password == null || password.isJsonNull) {
                return null
            }
            password.asString?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            logger.debug("Failed to read service password from ${serviceFile.path}: ${e.message}")
            null
        }
    }

    /**
     * Ensures the background service is running by invoking `<binary> service start`.
     * Returns true when the service is running (including when it was already running).
     */
    fun ensureRunning(binary: String): Boolean {
        val output = runServiceCommand(binary, "start", SERVICE_START_TIMEOUT_MS) ?: return false
        val combined = output.stdout + "\n" + output.stderr
        val alreadyRunning = combined.contains("already running", ignoreCase = true)
        if (output.exitCode == 0 || alreadyRunning) {
            logger.info("OpenCode service start exit code=${output.exitCode}, alreadyRunning=$alreadyRunning")
            return true
        }
        logger.debug("OpenCode service start failed with exit code ${output.exitCode}")
        return false
    }

    /**
     * Discovers a running service: asks `<binary> service status` for its URL, starts it when
     * needed, then attaches the password from the config directory.
     * Returns null when no endpoint can be determined.
     */
    fun discover(binary: String): ServiceEndpoint? {
        var parsed = parseServiceUrl(runServiceStatus(binary))

        if (parsed == null) {
            logger.info("OpenCode service is not running, starting it")
            if (!ensureRunning(binary)) {
                return null
            }
            parsed = parseServiceUrl(runServiceStatus(binary))
        }

        val endpoint = parsed ?: return null
        return ServiceEndpoint(endpoint.first, endpoint.second, readPasswordWithRetry())
    }

    /**
     * Reads the service password, retrying a bounded number of times because the freshly started
     * service may not have flushed `service.json` yet when `discover` runs right after
     * [ensureRunning].
     *
     * @param configDir directory holding `service.json`
     * @param attempts maximum number of read attempts
     * @param delayMs delay between attempts
     * @return the password as soon as it is readable, or null once the attempts are exhausted
     */
    internal fun readPasswordWithRetry(
        configDir: File = defaultConfigDir(),
        attempts: Int = PASSWORD_READ_ATTEMPTS,
        delayMs: Long = PASSWORD_READ_DELAY_MS
    ): String? {
        repeat(attempts) { attempt ->
            readPassword(configDir)?.let { return it }
            if (attempt < attempts - 1) {
                try {
                    Thread.sleep(delayMs)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }
        }
        return null
    }

    private fun runServiceStatus(binary: String): String {
        val output = runServiceCommand(binary, "status", SERVICE_STATUS_TIMEOUT_MS) ?: return ""
        return output.stdout + "\n" + output.stderr
    }

    private fun runServiceCommand(binary: String, subCommand: String, timeoutMs: Int): ProcessResult? {
        return try {
            val cmd = GeneralCommandLine(binary, "service", subCommand)
                .withCharset(Charset.defaultCharset())

            val handler = CapturingProcessHandler(cmd)
            val output = handler.runProcess(timeoutMs)

            if (output.isTimeout) {
                logger.debug("Command timed out: $binary service $subCommand")
                return null
            }
            ProcessResult(output.stdout, output.stderr, output.exitCode)
        } catch (e: Exception) {
            logger.debug("Command execution failed: $binary service $subCommand", e)
            null
        }
    }

    private data class ProcessResult(val stdout: String, val stderr: String, val exitCode: Int)
}
