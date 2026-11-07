package com.nova.voice

import android.content.Context
import android.os.SystemClock
import com.nova.actions.Action
import com.nova.actions.IntentRouter
import com.nova.actions.Reply
import com.nova.ai.AiClient
import com.nova.pet.Expression
import com.nova.pet.PetBrain

/**
 * The assistant flow (spec §1.1): wake → listen → interpret → (confirm) → act → react → idle.
 * Routine commands are understood locally; anything else goes to the cloud AI if configured.
 * Pet expressions mirror every step (Listening, Thinking, Happy, Confused, Error).
 */
class Assistant(
    private val context: Context,
    private val voice: VoiceInput,
    private val speaker: Speaker,
    private val brain: () -> PetBrain,
    private val facts: (String) -> String,
    private val execute: (Action) -> Result<String>,
) {
    enum class State { IDLE, LISTENING, THINKING, CONFIRM }

    data class Turn(val you: String, val nova: String)

    var state = State.IDLE
        private set
    var partial = ""
        private set
    var level = 0f
        private set
    var status = "Tap the microphone and speak"
        private set
    var pending: Action? = null
        private set
    var pendingText = ""
        private set
    val history = ArrayList<Turn>()
    var onChange: (() -> Unit)? = null
    var onListeningChanged: ((Boolean) -> Unit)? = null
    var onNeedPermission: (() -> Unit)? = null

    private fun now() = SystemClock.uptimeMillis()
    private fun changed() = onChange?.invoke()

    fun listen() {
        if (state == State.LISTENING) { cancel(); return }
        if (!voice.hasPermission()) { onNeedPermission?.invoke(); return }
        speaker.stop()
        state = State.LISTENING; partial = ""; status = "Listening…"
        brain().touch(now()); brain().setActivity(Expression.LISTENING); brain().react(Expression.SURPRISED, now(), holdMs = 500)
        onListeningChanged?.invoke(true)
        changed()
        voice.start(object : VoiceInput.Callback {
            override fun onReady() { status = "Listening…"; changed() }
            override fun onLevel(rms: Float) { level = rms; changed() }
            override fun onPartial(text: String) { partial = text; changed() }
            override fun onFinal(text: String) { stopListening(); handle(text) }
            override fun onError(message: String, code: Int) {
                stopListening()
                state = State.IDLE; status = message
                if (code == -2) onNeedPermission?.invoke()
                brain().react(if (code == 7 || code == 6) Expression.CONFUSED else Expression.ERROR, now(), audible = code != 7 && code != 6)
                changed()
            }
        })
    }

    private fun stopListening() {
        brain().setActivity(null)
        onListeningChanged?.invoke(false)
    }

    fun cancel() {
        voice.cancel(); speaker.stop()
        stopListening()
        state = State.IDLE; pending = null; status = "Cancelled"; partial = ""
        changed()
    }

    /** Handle typed or recognised text. */
    fun handle(text: String) {
        partial = text
        val local = IntentRouter.route(text, facts)
        if (local != null) { respond(text, local); return }
        if (AiClient.configured) {
            state = State.THINKING; status = "Thinking…"
            brain().setActivity(Expression.THINKING)
            changed()
            AiClient.chat(text, mapOf("time" to facts("time"), "screen" to "assistant")) { r ->
                brain().setActivity(null)
                r.onSuccess { (reply, rejected) ->
                    respond(text, reply)
                    if (rejected != null) { status = rejected; changed() }
                }.onFailure { e ->
                    state = State.IDLE; status = e.message ?: "Cloud AI error"
                    history.add(0, Turn(text, status)); trim()
                    brain().error(status, now()); changed()
                }
            }
        } else {
            state = State.IDLE
            status = "I don't know that one yet. Turn on Cloud AI in Settings for general questions."
            history.add(0, Turn(text, "Hmm?")); trim()
            brain().react(Expression.CONFUSED, now())
            changed()
        }
    }

    private fun respond(you: String, reply: Reply) {
        val action = reply.action
        if (action != null && action.needsConfirmation) {
            state = State.CONFIRM; pending = action
            pendingText = reply.text.ifBlank { "Do it?" }
            status = "Waiting for your confirmation"
            brain().react(Expression.CURIOUS, now())
            speaker.speak(pendingText)
            changed()
            return
        }
        finish(you, reply.text, action)
    }

    fun confirm(yes: Boolean) {
        val a = pending ?: return
        pending = null
        if (!yes) { state = State.IDLE; status = "Okay, I won't."; history.add(0, Turn(partial, "Cancelled")); trim(); changed(); return }
        finish(partial, "", a)
    }

    private fun finish(you: String, say: String, action: Action?) {
        var answer = say
        if (action != null) {
            execute(action).onSuccess { msg ->
                if (answer.isBlank()) answer = msg
                brain().react(Expression.HAPPY, now())
            }.onFailure { e ->
                answer = e.message ?: "That didn't work"
                brain().error(answer, now())
            }
        } else {
            brain().react(Expression.HAPPY, now(), audible = false)
        }
        state = State.IDLE
        status = answer.ifBlank { "Done" }
        history.add(0, Turn(you, answer.ifBlank { "Done." })); trim()
        speaker.speak(answer)
        changed()
    }

    private fun trim() { while (history.size > 6) history.removeAt(history.lastIndex) }
}
