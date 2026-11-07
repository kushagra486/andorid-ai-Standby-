package com.nova.ai

import android.os.Handler
import android.os.Looper
import com.nova.actions.ActionValidator
import com.nova.actions.Reply
import com.nova.integrations.HttpException
import com.nova.integrations.Net
import com.nova.storage.Prefs
import org.json.JSONObject
import java.util.UUID

/**
 * Client for the NØVA Cloudflare Worker (cloudflare-worker/). The provider key never touches
 * the phone: the app only holds the Worker URL and a per-owner access token it was given.
 * Requests carry minimal text; responses are untrusted and every proposed action goes
 * through [ActionValidator] before anything happens.
 */
object AiClient {
    private val main = Handler(Looper.getMainLooper())

    val configured: Boolean get() = Prefs.aiEnabled && Prefs.aiEndpoint.startsWith("https://") && Prefs.aiToken.isNotBlank()

    val installId: String
        get() = Prefs.raw.getString("installId", null) ?: UUID.randomUUID().toString().also { Prefs.raw.edit().putString("installId", it).apply() }

    /** Parse + validate a Worker response (pure, unit-tested). Invalid actions are dropped, never executed. */
    fun parse(body: String): Pair<Reply, String?> {
        val o = JSONObject(body)
        val text = o.optString("reply", "").take(600)
        val actionJson = o.optJSONObject("action")
        if (actionJson == null) return Reply(text, null, handledLocally = false) to null
        val v = ActionValidator.validate(actionJson)
        return if (v.isSuccess) Reply(text, v.getOrNull(), handledLocally = false) to null
        else Reply(text.ifBlank { "I can't do that." }, null, handledLocally = false) to ("Rejected action: " + v.exceptionOrNull()?.message)
    }

    fun chat(text: String, context: Map<String, String>, done: (Result<Pair<Reply, String?>>) -> Unit) {
        val url = Prefs.aiEndpoint.trimEnd('/') + "/v1/chat"
        val body = JSONObject().apply {
            put("text", text.take(500))
            put("context", JSONObject(context))
        }.toString()
        Net.bg.execute {
            val r = runCatching {
                parse(Net.request("POST", url, body, mapOf(
                    "Authorization" to "Bearer ${Prefs.aiToken}",
                    "X-Nova-Install" to installId,
                    "Content-Type" to "application/json",
                ), timeoutMs = 20_000))
            }.recoverCatching { e -> throw Exception(describe(e)) }
            main.post { done(r) }
        }
    }

    fun health(done: (String) -> Unit) {
        Net.bg.execute {
            val msg = runCatching {
                val o = JSONObject(Net.get(Prefs.aiEndpoint.trimEnd('/') + "/v1/health"))
                "OK · ${o.optString("provider")} · ${o.optString("version")}"
            }.getOrElse { describe(it) }
            main.post { done(msg) }
        }
    }

    fun describe(e: Throwable): String = when (e) {
        is HttpException -> when (e.code) {
            401, 403 -> "Cloud AI rejected the access token"
            429 -> "Cloud AI limit reached — local commands still work"
            in 500..599 -> "Cloud AI is having trouble (${e.code})"
            else -> "Cloud AI error ${e.code}"
        }
        is java.net.UnknownHostException, is java.net.ConnectException -> "No internet — local commands still work"
        is java.net.SocketTimeoutException -> "Cloud AI timed out"
        is javax.net.ssl.SSLException, is java.security.cert.CertificateException ->
            "Secure connection failed — check the phone's date & time are correct"
        is org.json.JSONException -> "Cloud AI sent a malformed reply"
        else -> e.message ?: "Cloud AI error"
    }
}
