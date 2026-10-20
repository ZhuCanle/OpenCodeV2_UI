package com.zhucanle.opencodev2.ide.util

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure unit tests for [OpenCodeServiceDiscovery].
 * These tests never spawn a process and never touch the real opencode installation.
 */
class OpenCodeServiceDiscoveryTest {

    private fun tempDir(): File = Files.createTempDirectory("opencode-discovery-test").toFile()

    @Test
    fun parseServiceUrlExtractsHostAndPort() {
        val out = "noise line\nhttp://127.0.0.1:49374\nmore\n"
        assertEquals("127.0.0.1" to 49374, OpenCodeServiceDiscovery.parseServiceUrl(out))
    }

    @Test
    fun parseServiceUrlReturnsNullWhenAbsent() {
        assertNull(OpenCodeServiceDiscovery.parseServiceUrl("no server running\n"))
    }

    @Test
    fun readPasswordParsesServiceJson() {
        val dir = tempDir()
        File(dir, "service.json").writeText("""{"password":"abc123"}""")
        assertEquals("abc123", OpenCodeServiceDiscovery.readPassword(dir))
    }

    @Test
    fun readPasswordReturnsNullWhenMissing() {
        assertNull(OpenCodeServiceDiscovery.readPassword(tempDir()))
    }

    @Test
    fun parseServiceUrlExtractsPortFromHttpsAndIgnoresTrailingPath() {
        val out = "Service is running at https://127.0.0.1:8080/status"
        assertEquals("127.0.0.1" to 8080, OpenCodeServiceDiscovery.parseServiceUrl(out))
    }

    @Test
    fun readPasswordReturnsNullWhenJsonIsMalformed() {
        val dir = tempDir()
        File(dir, "service.json").writeText("not json at all")
        assertNull(OpenCodeServiceDiscovery.readPassword(dir))
    }

    @Test
    fun readPasswordWithRetryReturnsImmediatelyWhenPresent() {
        val dir = tempDir()
        File(dir, "service.json").writeText("""{"password":"abc123"}""")
        assertEquals("abc123", OpenCodeServiceDiscovery.readPasswordWithRetry(dir, attempts = 5, delayMs = 10))
    }

    @Test
    fun readPasswordWithRetryReturnsNullAfterExhaustingAttempts() {
        assertNull(OpenCodeServiceDiscovery.readPasswordWithRetry(tempDir(), attempts = 3, delayMs = 10))
    }

    @Test
    fun readPasswordWithRetryFindsPasswordInAnyCandidateDir() {
        val empty = tempDir()
        val legacy = tempDir()
        File(legacy, "service.json").writeText("""{"password":"from-legacy"}""")
        assertEquals(
            "from-legacy",
            OpenCodeServiceDiscovery.readPasswordWithRetry(listOf(empty, legacy), attempts = 1, delayMs = 1)
        )
    }

    @Test
    fun readPasswordWithRetryWaitsForTheFileToAppear() {
        val dir = tempDir()
        val writer = Thread {
            Thread.sleep(120)
            File(dir, "service.json").writeText("""{"password":"late"}""")
        }
        writer.start()
        try {
            assertEquals("late", OpenCodeServiceDiscovery.readPasswordWithRetry(dir, attempts = 10, delayMs = 50))
        } finally {
            writer.join()
        }
    }
}
