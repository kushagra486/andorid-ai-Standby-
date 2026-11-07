package com.nova.actions

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionsTest {
    private fun v(json: String) = ActionValidator.validate(JSONObject(json))

    @Test fun `valid actions pass`() {
        assertTrue(v("""{"type":"SET_TIMER","args":{"minutes":5}}""").isSuccess)
        assertTrue(v("""{"type":"show_screen","args":{"screen":"Dashboard"}}""").isSuccess)
        assertEquals("dashboard", v("""{"type":"SHOW_SCREEN","args":{"screen":"Dashboard"}}""").getOrThrow().arg("screen"))
    }

    @Test fun `unknown action types are rejected`() {
        assertTrue(v("""{"type":"RUN_SHELL","args":{"cmd":"rm -rf /"}}""").isFailure)
        assertTrue(v("""{"type":"OPEN_URL","args":{"url":"https://evil.example"}}""").isFailure)
    }

    @Test fun `unexpected arguments are rejected`() {
        assertTrue(v("""{"type":"SET_TIMER","args":{"minutes":5,"script":"x"}}""").isFailure)
    }

    @Test fun `links and code inside arguments are rejected`() {
        assertTrue(v("""{"type":"SPOTIFY_PLAY","args":{"query":"https://x.y/z"}}""").isFailure)
        assertTrue(v("""{"type":"ADD_REMINDER","args":{"text":"visit www.bad.com","in_minutes":5}}""").isFailure)
        assertTrue(v("""{"type":"SMART_HOME","args":{"device":"lamp; rm","command":"on"}}""").isFailure)
    }

    @Test fun `ranges are enforced`() {
        assertTrue(v("""{"type":"SET_VOLUME","args":{"level":150}}""").isFailure)
        assertTrue(v("""{"type":"SET_TIMER","args":{"minutes":-1}}""").isFailure)
        assertTrue(v("""{"type":"ADD_REMINDER","args":{"text":"x","at":"25:00"}}""").isFailure)
        assertTrue(v("""{"type":"NIGHT_MODE","args":{"mode":"purple"}}""").isFailure)
        assertTrue(v("""{"type":"SET_TIMER","args":{"minutes":{"a":1}}}""").isFailure)
    }

    @Test fun `consequential actions need confirmation`() {
        assertTrue(v("""{"type":"SMART_HOME","args":{"device":"lamp","command":"on"}}""").getOrThrow().needsConfirmation)
        assertTrue(v("""{"type":"SPOTIFY_TRANSFER","args":{"device":"Fire TV"}}""").getOrThrow().needsConfirmation)
        assertTrue(!v("""{"type":"SET_TIMER","args":{"minutes":1}}""").getOrThrow().needsConfirmation)
    }

    private fun route(s: String) = IntentRouter.route(s) { "fact:$it" }

    @Test fun `spec voice commands are understood offline`() {
        assertEquals("dashboard", route("Show dashboard")!!.action!!.arg("screen"))
        assertEquals("pet", route("go to pet mode")!!.action!!.arg("screen"))
        assertEquals("assistant", route("let's talk")!!.action!!.arg("screen"))
        assertEquals(ActionType.PET_SLEEP, route("go to sleep")!!.action!!.type)
    }

    @Test fun `timers and reminders`() {
        assertEquals("5.0", route("set a timer for 5 minutes")!!.action!!.arg("minutes"))
        assertEquals("0.5", route("timer for 30 seconds")!!.action!!.arg("minutes"))
        assertEquals("120.0", route("set timer for two hours")!!.action!!.arg("minutes"))
        assertEquals("10.0", route("10 minute timer")!!.action!!.arg("minutes"))
        val r = route("remind me to drink water in 30 minutes")!!.action!!
        assertEquals("drink water", r.arg("text")); assertEquals("30.0", r.arg("in_minutes"))
        assertEquals("17:30", route("remind me to call mom at 5:30 pm")!!.action!!.arg("at"))
        assertEquals(ActionType.CANCEL_TIMERS, route("cancel the timer")!!.action!!.type)
    }

    @Test fun `music and devices`() {
        assertEquals("next", route("next song")!!.action!!.arg("command"))
        assertEquals("pause", route("Pause.")!!.action!!.arg("command"))
        assertEquals("lofi", route("play lofi on spotify")!!.action!!.arg("query"))
        assertEquals("fire tv", route("move the music to the fire tv")!!.action!!.arg("device"))
        val h = route("turn on the desk lamp")!!.action!!
        assertEquals(ActionType.SMART_HOME, h.type); assertEquals("desk lamp", h.arg("device")); assertEquals("on", h.arg("command"))
        assertEquals(ActionType.NIGHT_MODE, route("turn night mode off")!!.action!!.type)
        assertEquals("40", route("volume 40")!!.action!!.arg("level"))
    }

    @Test fun `facts and unknowns`() {
        assertEquals("fact:time", route("what time is it?")!!.text)
        assertEquals("fact:weather", route("how's the weather")!!.text)
        assertNull(route("explain quantum entanglement"))
    }

    @Test fun `every routed action passes validation`() {
        listOf("show dashboard", "set a timer for 5 minutes", "remind me to stretch in 20 minutes", "next", "play jazz on spotify",
            "move the music to living room", "turn off desk lamp", "volume 30", "brightness 80", "night mode auto", "go to sleep")
            .forEach { s -> val a = route(s)!!.action!!; assertTrue("$s → ${ActionValidator.validate(a)}", ActionValidator.validate(a).isSuccess) }
    }
}
