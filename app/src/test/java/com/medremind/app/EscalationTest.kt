package com.medremind.app

import com.medremind.app.data.AlarmSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The escalation cycle for a dose due at T, with the shipped defaults
 * (ring 2 min, retry every 5 min, give up after 30 min):
 * rings at T, T+5, T+10, T+15, T+20, T+25, then missed at T+30.
 */
class EscalationTest {

    private val settings = AlarmSettings()
    private val scheduled = 1_800_000_000_000L

    private fun min(m: Long) = m * 60_000L

    @Test
    fun `first retry is one interval after the scheduled time`() {
        assertEquals(scheduled + min(5), settings.nextRetryAt(scheduled, scheduled))
    }

    @Test
    fun `retry does not move while the alarm is still ringing`() {
        assertEquals(scheduled + min(5), settings.nextRetryAt(scheduled, scheduled + min(2)))
    }

    @Test
    fun `each attempt schedules the next interval`() {
        assertEquals(scheduled + min(10), settings.nextRetryAt(scheduled, scheduled + min(5)))
        assertEquals(scheduled + min(15), settings.nextRetryAt(scheduled, scheduled + min(10)))
        assertEquals(scheduled + min(30), settings.nextRetryAt(scheduled, scheduled + min(25)))
    }

    @Test
    fun `final attempt is clamped to the give-up instant, never past it`() {
        // A late wake-up at T+26 must land exactly on T+30, not T+35.
        assertEquals(scheduled + min(30), settings.nextRetryAt(scheduled, scheduled + min(26)))
    }

    @Test
    fun `no further attempts once the give-up window has closed`() {
        assertNull(settings.nextRetryAt(scheduled, scheduled + min(30)))
        assertNull(settings.nextRetryAt(scheduled, scheduled + min(45)))
    }

    @Test
    fun `give-up boundary is inclusive`() {
        assertFalse(settings.isPastGiveUp(scheduled, scheduled + min(29)))
        assertTrue(settings.isPastGiveUp(scheduled, scheduled + min(30)))
    }

    @Test
    fun `every attempt lands inside the give-up window`() {
        var now = scheduled
        var attempts = 0
        while (true) {
            val next = settings.nextRetryAt(scheduled, now) ?: break
            assertTrue("attempt overshot the window", next <= scheduled + settings.giveUpMillis)
            assertTrue("cycle failed to advance", next > now)
            now = next
            if (++attempts > 50) error("escalation did not terminate")
        }
        // T+5 … T+30 inclusive.
        assertEquals(6, attempts)
    }

    @Test
    fun `custom durations are honoured`() {
        val fast = AlarmSettings(ringSeconds = 60, retryIntervalSeconds = 120, giveUpSeconds = 600)
        assertEquals(scheduled + min(2), fast.nextRetryAt(scheduled, scheduled))
        assertEquals(scheduled + min(10), fast.nextRetryAt(scheduled, scheduled + min(9)))
        assertNull(fast.nextRetryAt(scheduled, scheduled + min(10)))
    }

    @Test
    fun `unscheduled doses never escalate`() {
        assertNull(settings.nextRetryAt(scheduledAt = 0L, now = System.currentTimeMillis()))
    }
}
