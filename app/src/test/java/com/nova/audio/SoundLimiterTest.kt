package com.nova.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SoundLimiterTest {

    @Test
    fun `enforces a minimum gap`() {
        val l = SoundLimiter(minGapMs = 200)
        assertTrue(l.allow(0))
        assertFalse(l.allow(150))
        assertTrue(l.allow(200))
    }

    @Test
    fun `caps sounds per rolling window`() {
        val l = SoundLimiter(minGapMs = 0, maxPerWindow = 10, windowMs = 60_000)
        val allowed = (0 until 30).count { l.allow(it * 1_000L) }
        assertEquals(10, allowed)
        assertTrue(l.allow(60_000))   // first sound has left the window
    }
}
