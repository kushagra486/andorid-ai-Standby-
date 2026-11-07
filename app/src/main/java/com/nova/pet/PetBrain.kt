package com.nova.pet

import kotlin.random.Random

/**
 * NØVA's personality state machine — pure Kotlin with no Android dependencies, so it is
 * unit-tested on the JVM. Time is always passed in as milliseconds.
 *
 * What is shown is resolved in this order:
 *   forced (expression test) > reaction (timed, priority-based) > activity (persistent,
 *   e.g. LISTENING/THINKING/DANCING from later stages) > base (IDLE / SLEEPY / SLEEPING).
 *
 * @param cue called whenever an expression starts that should make a sound.
 */
class PetBrain(
    private val random: Random = Random.Default,
    private val cue: (Expression) -> Unit = {},
) {
    /** Night: falls asleep faster (and the view dims and mutes). */
    var nightMode = false

    /** Spontaneous idle micro-behaviours (disabled at Low animation intensity). */
    var spontaneous = true

    /** Overrides everything; used by the "test all expressions" mode. */
    var forced: Expression? = null

    var base: Expression = Expression.IDLE
        private set
    var activity: Expression? = null
        private set
    var status: String? = null
        private set

    /** When the latest reaction started (drives the little hop animation). */
    var reactionStartedAt: Long = -1
        private set

    private var reaction: Expression? = null
    private var reactionUntil = 0L
    private var statusUntil = 0L
    private var lastInteraction = 0L
    private var nextIdleBehaviourAt = Long.MAX_VALUE
    private val recentTaps = ArrayDeque<Long>()

    val current: Expression get() = forced ?: reaction ?: activity ?: base

    val sleepyAfterMs: Long get() = if (nightMode) 60_000L else 5 * 60_000L
    val sleepAfterMs: Long get() = if (nightMode) 5 * 60_000L else 15 * 60_000L

    /** Call once when NØVA appears: a happy little greeting. */
    fun start(now: Long) {
        lastInteraction = now
        scheduleIdleBehaviour(now)
        react(Expression.HAPPY, now)
    }

    /** Advance the state machine. Call every frame. */
    fun tick(now: Long) {
        if (reaction != null && now >= reactionUntil) reaction = null
        if (status != null && now >= statusUntil) status = null

        val idleFor = now - lastInteraction
        val newBase = when {
            activity != null -> Expression.IDLE        // never doze off mid-activity
            idleFor >= sleepAfterMs -> Expression.SLEEPING
            idleFor >= sleepyAfterMs -> Expression.SLEEPY
            else -> Expression.IDLE
        }
        if (newBase == Expression.SLEEPING && base != Expression.SLEEPING) cue(Expression.SLEEPING)
        base = newBase

        if (spontaneous && forced == null && reaction == null && activity == null &&
            base == Expression.IDLE && now >= nextIdleBehaviourAt
        ) {
            val e = Expression.IDLE_BEHAVIOURS[random.nextInt(Expression.IDLE_BEHAVIOURS.size)]
            react(e, now, holdMs = 1_100, audible = false)
            scheduleIdleBehaviour(now)
        }
    }

    /**
     * Show [e] for [holdMs]. Refused (returns false) while a higher-priority reaction is
     * still running, so e.g. a tap can't cut an error short.
     */
    fun react(e: Expression, now: Long, holdMs: Long = e.holdMs, audible: Boolean = true): Boolean {
        val running = reaction?.takeIf { now < reactionUntil }
        if (running != null && e.priority < running.priority) return false
        reaction = e
        reactionUntil = now + holdMs
        reactionStartedAt = now
        if (audible) cue(e)
        return true
    }

    /** Any user interaction: resets the idle timer and wakes NØVA up. */
    fun touch(now: Long) {
        lastInteraction = now
        scheduleIdleBehaviour(now)
        val previous = base
        base = Expression.IDLE
        when (previous) {
            Expression.SLEEPING -> react(Expression.SURPRISED, now)
            Expression.SLEEPY -> react(Expression.HAPPY, now, audible = false)
            else -> Unit
        }
    }

    /** A tap on the face. Returns the reaction shown, or null if it was ignored. */
    fun tap(now: Long): Expression? {
        val previous = base
        touch(now)
        if (previous != Expression.IDLE) return current   // the tap only woke NØVA up

        recentTaps.addLast(now)
        while (recentTaps.isNotEmpty() && now - recentTaps.first() > 3_000) recentTaps.removeFirst()
        if (recentTaps.size >= 7) {
            recentTaps.clear()
            return if (react(Expression.ANNOYED, now)) Expression.ANNOYED else null
        }
        val e = Expression.TAP_REACTIONS[random.nextInt(Expression.TAP_REACTIONS.size)]
        return if (react(e, now)) e else null
    }

    /** Persistent state owned by a feature (voice, AI, music). Pass null to clear. */
    fun setActivity(e: Expression?) {
        activity = e
    }

    /** Integration failure: brief error face plus a short, useful status line. */
    fun error(message: String, now: Long) {
        react(Expression.ERROR, now)
        status = message
        statusUntil = now + 4_000
    }

    private fun scheduleIdleBehaviour(now: Long) {
        nextIdleBehaviourAt = now + random.nextLong(25_000, 70_000)
    }
}
