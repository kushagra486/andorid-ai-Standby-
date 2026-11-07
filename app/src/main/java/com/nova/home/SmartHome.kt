package com.nova.home

import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.nova.integrations.Net
import com.nova.storage.Prefs
import org.json.JSONArray
import org.json.JSONObject

data class SmartDevice(val name: String, val url: String, val token: String)

/**
 * Optional smart-home adapter (spec Stage 9/14): each device is an HTTP endpoint the owner
 * runs (ESP32 / Raspberry Pi — see extras/). NØVA sends `POST <url>` with
 * `{"command":"on|off|toggle"}` and `Authorization: Bearer <token>`. HTTPS is required,
 * except plain HTTP to private LAN addresses (typical for ESP32), always with a token.
 */
object SmartHome {
    private val main = Handler(Looper.getMainLooper())

    fun devices(): List<SmartDevice> = runCatching {
        val a = JSONArray(Prefs.smartDevices)
        (0 until a.length()).map { a.getJSONObject(it).let { o -> SmartDevice(o.getString("name"), o.getString("url"), o.optString("token")) } }
    }.getOrDefault(emptyList())

    fun save(list: List<SmartDevice>) {
        val a = JSONArray(); list.forEach { a.put(JSONObject().put("name", it.name).put("url", it.url).put("token", it.token)) }
        Prefs.smartDevices = a.toString()
    }

    /** Validation for device URLs (pure, unit-tested). Returns an error message or null. */
    fun checkUrl(url: String): String? {
        val u = runCatching { Uri.parse(url) }.getOrNull() ?: return "Invalid address"
        val host = u.host ?: return "Address needs a host"
        return when (u.scheme) {
            "https" -> null
            "http" -> if (isPrivateHost(host)) null else "Use https:// (plain http is only allowed on your home network)"
            else -> "Address must start with https://"
        }
    }

    fun isPrivateHost(host: String): Boolean {
        if (host.endsWith(".local")) return true
        val p = host.split('.').mapNotNull { it.toIntOrNull() }
        if (p.size != 4) return false
        return p[0] == 10 || (p[0] == 192 && p[1] == 168) || (p[0] == 172 && p[1] in 16..31)
    }

    fun find(name: String): SmartDevice? {
        val n = name.lowercase().trim()
        return devices().firstOrNull { it.name.lowercase() == n } ?: devices().firstOrNull { n.contains(it.name.lowercase()) || it.name.lowercase().contains(n) }
    }

    fun command(device: SmartDevice, command: String, done: (Result<String>) -> Unit) {
        val err = checkUrl(device.url)
        if (err != null) { done(Result.failure(Exception(err))); return }
        if (device.token.isBlank()) { done(Result.failure(Exception("Device has no access token"))); return }
        Net.bg.execute {
            val r = runCatching {
                Net.request("POST", device.url, JSONObject().put("command", command).toString(),
                    mapOf("Authorization" to "Bearer ${device.token}"), timeoutMs = 6_000)
                "${device.name} turned $command"
            }.recoverCatching { throw Exception("${device.name} didn't respond (${it.message})") }
            main.post { done(r) }
        }
    }
}
