package com.nova.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovaSettingsTest {

    @Test
    fun `auto night covers 22 to 7`() {
        val s = NovaSettings(nightMode = NightMode.AUTO)
        assertTrue(s.isNight(22)); assertTrue(s.isNight(3)); assertTrue(s.isNight(6))
        assertFalse(s.isNight(7)); assertFalse(s.isNight(12)); assertFalse(s.isNight(21))
    }

    @Test
    fun `off and always`() {
        assertFalse(NovaSettings(nightMode = NightMode.OFF).isNight(2))
        assertTrue(NovaSettings(nightMode = NightMode.ALWAYS).isNight(12))
    }
}
