package com.zhucanle.opencodev2.ide.integration

import com.zhucanle.opencodev2.ide.OpenCodeService
import com.zhucanle.opencodev2.ide.api.OpenCodeApiClient
import com.zhucanle.opencodev2.ide.diff.DiffViewerService
import com.zhucanle.opencodev2.ide.session.SessionManager
import com.zhucanle.opencodev2.ide.util.PortFinder
import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Integration test against a real `opencode serve` process.
 *
 * Environment-dependent: it needs the opencode CLI and a live server, so the Gradle `test` task
 * excludes it unless the build is run with `-PwithRealServer`. When it does run, a missing CLI is a
 * failure with a clear message (never a silent pass).
 */
class RealServerIntegrationTest : BasePlatformTestCase() {

    private var process: Process? = null
    private var port: Int = 0

    @Volatile
    private var password: String? = null

    override fun tearDown() {
        try {
            stopServer()
        } finally {
            super.tearDown()
        }
    }

    // ==================== Real process management ====================

    /**
     * Starts `opencode serve` on a free port, parses the password it prints on stdout, and waits
     * until `/api/info` answers.
     *
     * @param binary absolute path to the opencode executable (found by [findCliBinary]).
     * @return true when a v2 server is listening; false when the server never became healthy.
     */
    private fun startServer(binary: String): Boolean {
        port = PortFinder.findAvailablePort()
        println("[RealServer] Launching: $binary serve --port $port")

        val pb = ProcessBuilder(binary, "serve", "--port", "$port")
        pb.redirectErrorStream(true)
        val proc = pb.start()
        process = proc

        // v2 prints the per-process Basic-auth password on stdout; drain the stream to keep the
        // process from blocking and capture it.
        val passwordSeen = CountDownLatch(1)
        Thread {
            try {
                proc.inputStream.bufferedReader().use { reader ->
                    reader.forEachLine { line ->
                        println("[RealServer] $line")
                        PASSWORD_REGEX.find(line)?.let {
                            password = it.groupValues[1]
                            passwordSeen.countDown()
                        }
                    }
                }
            } catch (e: Exception) {
                // Stream closed on shutdown.
            }
        }.apply { isDaemon = true }.start()

        val healthy = PortFinder.waitForPort(port, "127.0.0.1", 20000, true)
        if (!healthy) {
            println("[RealServer] opencode server did not become healthy on port $port")
            stopServer()
            return false
        }
        passwordSeen.await(5, TimeUnit.SECONDS)
        println("[RealServer] Server healthy on port $port (password captured=${password != null})")
        return true
    }

    private fun stopServer() {
        val proc = process ?: return
        try {
            proc.destroy()
            proc.waitFor(2, TimeUnit.SECONDS)
            if (proc.isAlive) proc.destroyForcibly()
        } finally {
            process = null
        }
    }

    private fun findCliBinary(): String? {
        val candidates = mutableListOf<File>()
        System.getenv("PATH")?.split(File.pathSeparator)?.forEach { dir ->
            if (dir.isNotBlank()) candidates.add(File(dir, "opencode"))
        }
        val home = System.getProperty("user.home")
        candidates.add(File(home, ".opencode/bin/opencode"))
        candidates.add(File("/opt/homebrew/bin/opencode"))
        candidates.add(File("/usr/local/bin/opencode"))
        return candidates.firstOrNull { it.isFile && it.canExecute() }?.absolutePath
    }

    // ==================== Tests ====================

    fun testRealConnectionAndApi() {
        val binary = findCliBinary()
        assertNotNull(
            "opencode CLI not found on PATH or in common install locations; " +
                "run this test with -PwithRealServer on a machine that has the CLI",
            binary
        )
        assertTrue("opencode server must become healthy", startServer(checkNotNull(binary)))

        val client = OpenCodeApiClient("127.0.0.1", port, username = null, password = password)

        // The server must identify itself as opencode v2; the plugin targets that major version.
        val version = client.getServerVersion()
        println("[RealServer] /api/info version=$version")
        assertNotNull("real server must report a version on /api/info", version)
        assertEquals(
            "plugin targets opencode v2",
            2,
            OpenCodeApiClient.parseMajorVersion(version)
        )

        val service = project.service<OpenCodeService>()
        val sm = project.service<SessionManager>()
        val dvs = project.service<DiffViewerService>()
        service.setTestDeps(sm, dvs, client)

        // Exercise the plugin's own credential wiring rather than a hand-built client: let the
        // service construct its API client through the real connect path (initializeApiClient), so
        // a regression that leaves `username` null while a password is present is caught here.
        val initializeApiClient = OpenCodeService::class.java.getDeclaredMethod(
            "initializeApiClient",
            String::class.java,
            Int::class.javaPrimitiveType,
            String::class.java
        )
        initializeApiClient.isAccessible = true
        initializeApiClient.invoke(service, "127.0.0.1", port, password)

        val usernameField = OpenCodeService::class.java.getDeclaredField("username")
        usernameField.isAccessible = true
        assertEquals(
            "the plugin must authenticate as the opencode Basic-auth user once a password is known",
            "opencode",
            usernameField.get(service)
        )

        println("Connecting plugin to real server on port $port...")
        val connectMethod = OpenCodeService::class.java.getDeclaredMethod("connectToSse")
        connectMethod.isAccessible = true
        connectMethod.invoke(service)

        val connectedField = OpenCodeService::class.java.getDeclaredField("isConnected")
        connectedField.isAccessible = true

        var connected = false
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 5000) {
            if ((connectedField.get(service) as AtomicBoolean).get()) {
                connected = true
                break
            }
            Thread.sleep(100)
        }
        assertTrue("Failed to establish SSE connection to real server", connected)
        println("SSE connection established.")

        // A session lookup for an unknown id must complete without throwing (2xx null or 404).
        val session = client.getSession("ses-test-session", project.basePath ?: ".")
        println("API getSession returned: $session")
    }

    companion object {
        /** Matches `server password <pw>` printed by `opencode serve` at startup. */
        private val PASSWORD_REGEX = Regex("""server password\s+(\S+)""")
    }
}
