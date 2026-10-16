package com.zhucanle.opencodev2.ide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure turn-outcome notification mapping used when a turn ends.
 *
 * `succeeded` keeps the informational "Task Completed" message; `failed`/`interrupted` produce a
 * warning that names the outcome and, when present, the provider error message.
 */
class OpenCodeServiceTurnOutcomeTest {

    @Test
    fun succeededKeepsTheInformationalTaskCompletedMessage() {
        val n = OpenCodeService.turnOutcomeNotification("succeeded", errorMessage = null)
        assertEquals("OpenCode Task Completed", n.title)
        assertFalse("success must not be a warning", n.warning)
    }

    @Test
    fun failedIsAWarningCarryingTheErrorMessage() {
        val n = OpenCodeService.turnOutcomeNotification("failed", errorMessage = "Insufficient Balance")
        assertTrue("failed must be a warning", n.warning)
        assertTrue("title must name the outcome", n.title.contains("failed"))
        assertTrue("content must include the provider error", n.content.contains("Insufficient Balance"))
    }

    @Test
    fun interruptedIsAWarningWithoutAnError() {
        val n = OpenCodeService.turnOutcomeNotification("interrupted", errorMessage = null)
        assertTrue(n.warning)
        assertTrue(n.title.contains("interrupted"))
    }

    @Test
    fun blankErrorMessageIsNotAppended() {
        val n = OpenCodeService.turnOutcomeNotification("failed", errorMessage = "   ")
        assertTrue(n.warning)
        assertFalse("a blank error must not appear in the content", n.content.contains("   "))
    }
}
