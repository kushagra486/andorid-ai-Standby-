package com.nova.actions

import org.json.JSONObject

/**
 * The complete allowlist of things NØVA may do (spec §4.4). Anything else — from voice, the
 * cloud AI or anywhere — is rejected. Consequential actions need an explicit confirmation.
 */
enum class ActionType(val consequential: Boolean, val allowedArgs: Set<String>) {
    SHOW_SCREEN(false, setOf("screen")),
    PET_SLEEP(false, emptySet()),
    PET_WAKE(false, emptySet()),
    NIGHT_MODE(false, setOf("mode")),
    SET_TIMER(false, setOf("minutes", "label")),
    CANCEL_TIMERS(false, emptySet()),
    ADD_REMINDER(false, setOf("text", "in_minutes", "at")),
    MEDIA(false, setOf("command")),
    SPOTIFY_PLAY(false, setOf("query", "kind")),
    SPOTIFY_TRANSFER(true, setOf("device")),
    SET_VOLUME(false, setOf("level")),
    SET_BRIGHTNESS(false, setOf("level")),
    SMART_HOME(true, setOf("device", "command")),
    OPEN_SETTINGS(false, emptySet());

    companion object { fun byName(n: String) = entries.firstOrNull { it.name.equals(n, ignoreCase = true) } }
}

data class Action(val type: ActionType, val args: Map<String, String> = emptyMap()) {
    fun arg(k: String) = args[k] ?: ""
    val needsConfirmation: Boolean get() = type.consequential
}

/** Strict validation of untrusted action proposals (AI output, intents). Pure, unit-tested. */
object ActionValidator {
    private val screens = setOf("pet", "clock", "dual", "dashboard", "photos", "music", "assistant")
    private val nightModes = setOf("on", "off", "auto")
    private val mediaCommands = setOf("play", "pause", "toggle", "next", "previous")
    private val homeCommands = setOf("on", "off", "toggle")
    private val kinds = setOf("playlist", "artist", "album", "track", "any")
    private val urlLike = Regex("""(?i)(://|www\.|\.(com|net|org|io|in)\b|<|>|\{|\}|`|;|\$\()""")

    fun validate(json: JSONObject?): Result<Action> {
        if (json == null) return Result.failure(IllegalArgumentException("no action"))
        val type = ActionType.byName(json.optString("type", ""))
            ?: return Result.failure(IllegalArgumentException("action not allowed: ${json.optString("type").take(40)}"))
        val argsJson = json.optJSONObject("args") ?: JSONObject()
        val args = HashMap<String, String>()
        val keys = argsJson.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (k !in type.allowedArgs) return Result.failure(IllegalArgumentException("unexpected argument '$k'"))
            val v = argsJson.opt(k)
            if (v !is String && v !is Number) return Result.failure(IllegalArgumentException("argument '$k' must be text or number"))
            args[k] = v.toString().trim()
        }
        return validate(Action(type, args))
    }

    fun validate(a: Action): Result<Action> {
        fun fail(msg: String) = Result.failure<Action>(IllegalArgumentException(msg))
        a.args.values.forEach { v ->
            if (v.length > 80) return fail("argument too long")
            if (urlLike.containsMatchIn(v)) return fail("links and code are not allowed")
        }
        when (a.type) {
            ActionType.SHOW_SCREEN -> if (a.arg("screen").lowercase() !in screens) return fail("unknown screen")
            ActionType.NIGHT_MODE -> if (a.arg("mode").lowercase() !in nightModes) return fail("unknown night mode")
            ActionType.SET_TIMER -> {
                val m = a.arg("minutes").toDoubleOrNull() ?: return fail("timer needs minutes")
                if (m <= 0 || m > 24 * 60) return fail("timer must be between 1 second and 24 hours")
            }
            ActionType.ADD_REMINDER -> {
                if (a.arg("text").isBlank()) return fail("reminder needs text")
                val inMin = a.args["in_minutes"]?.toDoubleOrNull()
                val at = a.args["at"]
                if (inMin == null && at == null) return fail("reminder needs a time")
                if (inMin != null && (inMin <= 0 || inMin > 60 * 24 * 30)) return fail("reminder time out of range")
                if (at != null && !Regex("""^([01]?\d|2[0-3]):[0-5]\d$""").matches(at)) return fail("time must be HH:mm")
            }
            ActionType.MEDIA -> if (a.arg("command").lowercase() !in mediaCommands) return fail("unknown media command")
            ActionType.SPOTIFY_PLAY -> {
                if (a.arg("query").isBlank()) return fail("nothing to play")
                if (a.args["kind"] != null && a.arg("kind").lowercase() !in kinds) return fail("unknown kind")
            }
            ActionType.SPOTIFY_TRANSFER -> if (a.arg("device").isBlank()) return fail("which device?")
            ActionType.SET_VOLUME, ActionType.SET_BRIGHTNESS -> {
                val l = a.arg("level").toIntOrNull() ?: return fail("level must be 0–100")
                if (l !in 0..100) return fail("level must be 0–100")
            }
            ActionType.SMART_HOME -> {
                if (a.arg("device").isBlank()) return fail("which device?")
                if (a.arg("command").lowercase() !in homeCommands) return fail("unknown device command")
            }
            else -> Unit
        }
        return Result.success(a.copy(args = a.args.mapValues { (k, v) ->
            if (k in setOf("screen", "mode", "command", "kind")) v.lowercase() else v
        }))
    }
}

/** What the assistant decided: say something, do something, or both. */
data class Reply(val text: String, val action: Action? = null, val handledLocally: Boolean = true)

/**
 * Offline command understanding for routine requests (spec: keep simple operations local).
 * Returns null when the request should go to the cloud AI (or be answered with "Confused").
 */
object IntentRouter {
    private fun num(s: String): Double? = s.toDoubleOrNull() ?: words[s]?.toDouble()
    private val words = mapOf("a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
        "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "fifteen" to 15, "twenty" to 20, "thirty" to 30, "forty" to 40,
        "forty-five" to 45, "sixty" to 60, "half" to 0)

    private fun unitMinutes(n: Double, unit: String): Double = when {
        unit.startsWith("sec") -> n / 60
        unit.startsWith("h") -> n * 60
        else -> n
    }

    fun route(raw: String, facts: (String) -> String = { "" }): Reply? {
        val t = raw.lowercase().trim().replace(Regex("[?!.,]"), "").replace(Regex("\\s+"), " ")
        if (t.isBlank()) return null
        fun show(screen: String, say: String) = Reply(say, Action(ActionType.SHOW_SCREEN, mapOf("screen" to screen)))

        Regex("""(?:set |start )?(?:a )?timer (?:for )?([\w-]+(?:\.\d+)?) (sec\w*|min\w*|hour\w*|hr\w*)""").find(t)?.let { m ->
            val n = num(m.groupValues[1]) ?: return@let
            val minutes = unitMinutes(n, m.groupValues[2])
            return Reply("Timer set.", Action(ActionType.SET_TIMER, mapOf("minutes" to minutes.toString())))
        }
        Regex("""([\w-]+) (sec\w*|min\w*|hour\w*) timer""").find(t)?.let { m ->
            val n = num(m.groupValues[1]) ?: return@let
            return Reply("Timer set.", Action(ActionType.SET_TIMER, mapOf("minutes" to unitMinutes(n, m.groupValues[2]).toString())))
        }
        if (Regex("""(cancel|stop|clear) (the |all )?timers?""").containsMatchIn(t)) return Reply("Timers cleared.", Action(ActionType.CANCEL_TIMERS))

        Regex("""remind me (?:to )?(.+?) in ([\w-]+) (min\w*|hour\w*|sec\w*)""").find(t)?.let { m ->
            val n = num(m.groupValues[2]) ?: return@let
            return Reply("I'll remind you.", Action(ActionType.ADD_REMINDER,
                mapOf("text" to m.groupValues[1], "in_minutes" to unitMinutes(n, m.groupValues[3]).toString())))
        }
        Regex("""remind me (?:to )?(.+?) at (\d{1,2})(?::(\d{2}))? ?(am|pm|a m|p m)?$""").find(t)?.let { m ->
            var h = m.groupValues[2].toInt(); val min = m.groupValues[3].ifEmpty { "0" }.toInt()
            val ap = m.groupValues[4].replace(" ", "")
            if (ap == "pm" && h < 12) h += 12
            if (ap == "am" && h == 12) h = 0
            if (h > 23 || min > 59) return@let
            return Reply("I'll remind you.", Action(ActionType.ADD_REMINDER, mapOf("text" to m.groupValues[1], "at" to String.format("%02d:%02d", h, min))))
        }

        Regex("""play (.+?) on spotify""").find(t)?.let { return Reply("Playing ${it.groupValues[1]}.", Action(ActionType.SPOTIFY_PLAY, mapOf("query" to it.groupValues[1]))) }
        Regex("""(?:move|transfer|switch|play) (?:the )?(?:music|playback|it|song) (?:to|on) (?:the )?(.+)""").find(t)?.let {
            return Reply("Move playback to ${it.groupValues[1]}?", Action(ActionType.SPOTIFY_TRANSFER, mapOf("device" to it.groupValues[1])))
        }
        when (t) {
            "play", "resume", "play music", "resume music" -> return Reply("", Action(ActionType.MEDIA, mapOf("command" to "play")))
            "pause", "stop", "pause music", "stop music" -> return Reply("", Action(ActionType.MEDIA, mapOf("command" to "pause")))
            "next", "skip", "next song", "next track", "skip song" -> return Reply("", Action(ActionType.MEDIA, mapOf("command" to "next")))
            "previous", "previous song", "go back", "last song" -> return Reply("", Action(ActionType.MEDIA, mapOf("command" to "previous")))
        }

        Regex("""(?:set )?volume (?:to )?(\d{1,3})""").find(t)?.let { return Reply("", Action(ActionType.SET_VOLUME, mapOf("level" to it.groupValues[1].toInt().coerceIn(0, 100).toString()))) }
        Regex("""(?:set )?brightness (?:to )?(\d{1,3})""").find(t)?.let { return Reply("", Action(ActionType.SET_BRIGHTNESS, mapOf("level" to it.groupValues[1].toInt().coerceIn(0, 100).toString()))) }
        Regex("""turn (on|off) (?:the )?(.+)""").find(t)?.let { m ->
            if (m.groupValues[2] in setOf("night mode", "dark mode")) return Reply("Night mode ${m.groupValues[1]}.", Action(ActionType.NIGHT_MODE, mapOf("mode" to m.groupValues[1])))
            return Reply("Turn ${m.groupValues[1]} ${m.groupValues[2]}?", Action(ActionType.SMART_HOME, mapOf("device" to m.groupValues[2], "command" to m.groupValues[1])))
        }
        Regex("""turn (?:the )?(.+) (on|off)$""").find(t)?.let { m ->
            if (m.groupValues[1] in setOf("night mode", "dark mode")) return Reply("Night mode ${m.groupValues[2]}.", Action(ActionType.NIGHT_MODE, mapOf("mode" to m.groupValues[2])))
            return Reply("Turn ${m.groupValues[2]} ${m.groupValues[1]}?", Action(ActionType.SMART_HOME, mapOf("device" to m.groupValues[1], "command" to m.groupValues[2])))
        }
        Regex("""night mode (on|off|auto\w*)""").find(t)?.let { return Reply("Okay.", Action(ActionType.NIGHT_MODE, mapOf("mode" to it.groupValues[1].take(4).let { m -> if (m.startsWith("auto")) "auto" else m }))) }

        return when {
            t.contains("dashboard") -> show("dashboard", "")
            t.contains("pet mode") || t == "go home" || t.contains("show your face") -> show("pet", "")
            t.contains("photo") -> show("photos", "")
            t.contains("clock") && !t.contains("what") -> show("clock", "")
            t.contains("music") && (t.startsWith("show") || t.startsWith("open")) -> show("music", "")
            t.contains("let's talk") || t.contains("lets talk") -> show("assistant", "I'm listening.")
            t.contains("go to sleep") || t == "sleep" || t.contains("good night") -> Reply("Good night.", Action(ActionType.PET_SLEEP))
            t.contains("wake up") -> Reply("I'm awake!", Action(ActionType.PET_WAKE))
            t.contains("settings") -> Reply("", Action(ActionType.OPEN_SETTINGS))
            t.contains("what time") || t == "time" -> Reply(facts("time"))
            t.contains("what day") || t.contains("date today") || t.contains("what's the date") || t.contains("whats the date") -> Reply(facts("date"))
            t.contains("weather") || t.contains("temperature") || t.contains("raining") -> Reply(facts("weather"))
            t.contains("battery") -> Reply(facts("battery"))
            t.contains("next event") || t.contains("my calendar") || t.contains("next meeting") -> Reply(facts("calendar"))
            t == "hello" || t == "hi" || t.startsWith("hey nova") || t == "nova" -> Reply("Hi! What can I do?")
            else -> null
        }
    }
}
