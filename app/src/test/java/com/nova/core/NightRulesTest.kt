package com.nova.core

import com.nova.storage.NightMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NightRulesTest {
    @Test fun `schedule wraps midnight`() {
        assertTrue(NightRules.inSchedule(23, 22, 7)); assertTrue(NightRules.inSchedule(3, 22, 7))
        assertFalse(NightRules.inSchedule(7, 22, 7)); assertFalse(NightRules.inSchedule(12, 22, 7))
        assertTrue(NightRules.inSchedule(2, 1, 6)); assertFalse(NightRules.inSchedule(6, 1, 6))
        assertFalse(NightRules.inSchedule(5, 5, 5))
    }

    @Test fun `manual override wins`() {
        assertTrue(NightRules.decide(NightMode.ON, 12, 22, 7, null))
        assertFalse(NightRules.decide(NightMode.OFF, 23, 22, 7, 999_999))
    }

    @Test fun `ambient light adapts only after two dark minutes`() {
        assertFalse(NightRules.decide(NightMode.AUTO, 15, 22, 7, 60_000))
        assertTrue(NightRules.decide(NightMode.AUTO, 15, 22, 7, 120_000))
        assertFalse(NightRules.decide(NightMode.AUTO, 15, 22, 7, null))
    }

    @Test fun `light hysteresis`() {
        assertTrue(NightRules.isDark(3f, false)); assertFalse(NightRules.isDark(8f, false))
        assertTrue(NightRules.isDark(8f, true)); assertFalse(NightRules.isDark(20f, true))
    }
}
