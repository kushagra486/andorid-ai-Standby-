package com.nova.pet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.random.Random

class PetBrainTest {

    private val cues = mutableListOf<Expression>()
    private lateinit var brain: PetBrain

    @Before
    fun setUp() {
        cues.clear()
        brain = PetBrain(Random(1)) { cues += it }
        brain.spontaneous = false
        brain.start(0)
    }

    @Test
    fun `starts with a happy greeting then settles to idle`() {
        assertEquals(Expression.HAPPY, brain.current)
        assertEquals(listOf(Expression.HAPPY), cues)
        brain.tick(Expression.HAPPY.holdMs + 1)
        assertEquals(Expression.IDLE, brain.current)
    }

    @Test
    fun `gets sleepy then falls asleep when left alone`() {
        brain.tick(brain.sleepyAfterMs)
        assertEquals(Expression.SLEEPY, brain.current)
        brain.tick(brain.sleepAfterMs)
        assertEquals(Expression.SLEEPING, brain.current)
        assertTrue(Expression.SLEEPING in cues)
    }

    @Test
    fun `night mode falls asleep sooner`() {
        val day = brain.sleepAfterMs
        brain.nightMode = true
        assertTrue(brain.sleepAfterMs < day)
        brain.tick(brain.sleepAfterMs)
        assertEquals(Expression.SLEEPING, brain.current)
    }

    @Test
    fun `tapping a sleeping pet wakes it up surprised`() {
        brain.tick(brain.sleepAfterMs)
        cues.clear()
        val shown = brain.tap(brain.sleepAfterMs + 10)
        assertEquals(Expression.SURPRISED, shown)
        assertEquals(Expression.IDLE, brain.base)
        assertEquals(listOf(Expression.SURPRISED), cues)
    }

    @Test
    fun `tapping a sleepy pet wakes it quietly`() {
        brain.tick(brain.sleepyAfterMs)
        cues.clear()
        brain.tap(brain.sleepyAfterMs + 10)
        assertEquals(Expression.IDLE, brain.base)
        assertTrue(cues.isEmpty())
    }

    @Test
    fun `seven rapid taps make it annoyed`() {
        var t = 10_000L
        var last: Expression? = null
        repeat(7) { last = brain.tap(t); t += 200 }
        assertEquals(Expression.ANNOYED, last)
        assertEquals(Expression.ANNOYED, brain.current)
    }

    @Test
    fun `lower priority reaction cannot interrupt a higher one`() {
        brain.error("Network unavailable", 5_000)
        assertFalse(brain.react(Expression.HAPPY, 5_100))
        assertEquals(Expression.ERROR, brain.current)
        assertEquals("Network unavailable", brain.status)
        brain.tick(5_000 + Expression.ERROR.holdMs + 1)
        assertTrue(brain.react(Expression.HAPPY, 5_000 + Expression.ERROR.holdMs + 2))
    }

    @Test
    fun `error status text expires`() {
        brain.error("Oops", 1_000)
        brain.tick(6_000)
        assertNull(brain.status)
    }

    @Test
    fun `activity persists and prevents sleeping`() {
        brain.tick(2_000)
        brain.setActivity(Expression.DANCING)
        brain.tick(brain.sleepAfterMs * 2)
        assertEquals(Expression.DANCING, brain.current)
        brain.setActivity(null)
        brain.tick(brain.sleepAfterMs * 2 + 1)
        assertEquals(Expression.SLEEPING, brain.current)
    }

    @Test
    fun `reaction shows over an activity then returns to it`() {
        brain.tick(2_000)
        brain.setActivity(Expression.THINKING)
        brain.react(Expression.HAPPY, 3_000)
        assertEquals(Expression.HAPPY, brain.current)
        brain.tick(3_000 + Expression.HAPPY.holdMs + 1)
        assertEquals(Expression.THINKING, brain.current)
    }

    @Test
    fun `forced expression overrides everything`() {
        brain.forced = Expression.CONFUSED
        brain.tick(brain.sleepAfterMs)
        assertEquals(Expression.CONFUSED, brain.current)
    }

    @Test
    fun `idle behaviours happen on their own and are silent`() {
        brain.spontaneous = true
        brain.tick(2_000)          // greeting over
        cues.clear()
        val seen = mutableSetOf<Expression>()
        var t = 2_000L
        while (t < 4 * 60_000L) { brain.tick(t); seen += brain.current; t += 500 }
        assertTrue("expected a spontaneous behaviour, saw $seen", seen.any { it in Expression.IDLE_BEHAVIOURS })
        assertTrue(cues.isEmpty())
    }

    @Test
    fun `every expression has a hold or is a base state`() {
        Expression.entries.forEach { e ->
            assertTrue(e.name, e.holdMs > 0 || e.priority == 0)
        }
    }
}
