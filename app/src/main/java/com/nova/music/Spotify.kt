package com.nova.music

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import com.nova.integrations.HttpException
import com.nova.integrations.Net
import com.nova.storage.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

data class ConnectDevice(val id: String, val name: String, val type: String, val active: Boolean)

/**
 * Spotify Web API with Authorization Code + PKCE (no client secret on the phone). Used for the
 * things MediaSession control can't do: listing Spotify Connect devices, transferring playback
 * (e.g. to a Fire TV) and starting a playlist/artist by name. The owner registers a free
 * Spotify developer app and enters its Client ID in Settings; redirect URI below.
 * Note: playback control through the Web API requires Spotify Premium.
 */
object Spotify {
    const val REDIRECT = "nova-zenfone://spotify-callback"
    private const val SCOPES = "user-read-playback-state user-modify-playback-state user-read-currently-playing"
    private val main = Handler(Looper.getMainLooper())
    private var accessToken = ""
    private var expiresAt = 0L

    val configured: Boolean get() = Prefs.spotifyClientId.isNotBlank()
    val signedIn: Boolean get() = Prefs.spotifyRefreshToken.isNotBlank()

    // ---- PKCE (pure, unit-tested) -------------------------------------------------------
    fun newVerifier(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(48); random.nextBytes(bytes)
        return b64url(bytes)
    }

    fun challenge(verifier: String): String = b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    private fun b64url(b: ByteArray) = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    fun authUrl(): Uri {
        val verifier = newVerifier()
        Prefs.raw.edit().putString("spotifyVerifier", verifier).apply()
        return Uri.parse("https://accounts.spotify.com/authorize").buildUpon()
            .appendQueryParameter("client_id", Prefs.spotifyClientId)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challenge(verifier))
            .appendQueryParameter("scope", SCOPES)
            .build()
    }

    /** Called with the redirect URI after the user approves in the browser. */
    fun handleRedirect(uri: Uri, done: (Result<Unit>) -> Unit) {
        val code = uri.getQueryParameter("code")
        if (code == null) { done(Result.failure(Exception(uri.getQueryParameter("error") ?: "Sign-in cancelled"))); return }
        val verifier = Prefs.raw.getString("spotifyVerifier", "") ?: ""
        tokenRequest("grant_type=authorization_code&code=${Net.enc(code)}&redirect_uri=${Net.enc(REDIRECT)}" +
            "&client_id=${Net.enc(Prefs.spotifyClientId)}&code_verifier=${Net.enc(verifier)}", done)
    }

    private fun tokenRequest(form: String, done: (Result<Unit>) -> Unit) {
        Net.bg.execute {
            val r = runCatching {
                val o = JSONObject(Net.request("POST", "https://accounts.spotify.com/api/token", form,
                    mapOf("Content-Type" to "application/x-www-form-urlencoded")))
                accessToken = o.getString("access_token")
                expiresAt = System.currentTimeMillis() + o.optLong("expires_in", 3600) * 1000 - 60_000
                o.optString("refresh_token").takeIf { it.isNotBlank() }?.let { Prefs.raw.edit().putString("spotifyRefresh", it).apply() }
                Unit
            }
            main.post { done(r) }
        }
    }

    fun signOut() {
        accessToken = ""; expiresAt = 0
        Prefs.spotifyRefreshToken = ""
    }

    /** Runs on the background thread: returns a valid access token or throws. */
    private fun token(): String {
        if (accessToken.isNotBlank() && System.currentTimeMillis() < expiresAt) return accessToken
        val refresh = Prefs.spotifyRefreshToken
        if (refresh.isBlank()) throw Exception("Sign in to Spotify in Settings → Music")
        val o = JSONObject(Net.request("POST", "https://accounts.spotify.com/api/token",
            "grant_type=refresh_token&refresh_token=${Net.enc(refresh)}&client_id=${Net.enc(Prefs.spotifyClientId)}",
            mapOf("Content-Type" to "application/x-www-form-urlencoded")))
        accessToken = o.getString("access_token")
        expiresAt = System.currentTimeMillis() + o.optLong("expires_in", 3600) * 1000 - 60_000
        o.optString("refresh_token").takeIf { it.isNotBlank() }?.let { Prefs.raw.edit().putString("spotifyRefresh", it).apply() }
        return accessToken
    }

    private fun api(method: String, path: String, body: String? = null): String =
        Net.request(method, "https://api.spotify.com/v1$path", body, mapOf("Authorization" to "Bearer ${token()}"))

    private fun <T> async(work: () -> T, done: (Result<T>) -> Unit) {
        Net.bg.execute {
            val r = runCatching(work).recoverCatching { throw Exception(describe(it)) }
            main.post { done(r) }
        }
    }

    fun describe(e: Throwable): String = when {
        e is HttpException && e.code == 403 && e.body.contains("PREMIUM", true) -> "Spotify Premium is required for remote playback control"
        e is HttpException && e.code == 404 -> "No active Spotify device — open Spotify on the phone or TV first"
        e is HttpException && e.code == 401 -> "Spotify sign-in expired — sign in again in Settings"
        e is HttpException && e.code == 429 -> "Spotify rate limit — try again shortly"
        e is HttpException -> "Spotify error ${e.code}"
        e is java.net.UnknownHostException -> "No internet"
        else -> e.message ?: "Spotify error"
    }

    fun parseDevices(json: String): List<ConnectDevice> {
        val arr = JSONObject(json).optJSONArray("devices") ?: JSONArray()
        return (0 until arr.length()).map { i ->
            val d = arr.getJSONObject(i)
            ConnectDevice(d.optString("id"), d.optString("name"), d.optString("type"), d.optBoolean("is_active"))
        }.filter { it.id.isNotBlank() }
    }

    /** Best device match for a spoken name ("fire tv", "living room"). */
    fun match(devices: List<ConnectDevice>, wanted: String): ConnectDevice? {
        val w = wanted.lowercase().replace("the ", "").trim()
        return devices.firstOrNull { it.name.lowercase() == w }
            ?: devices.firstOrNull { it.name.lowercase().contains(w) || w.contains(it.name.lowercase()) }
            ?: if (w.contains("tv")) devices.firstOrNull { it.type.equals("TV", true) || it.name.contains("TV", true) || it.name.contains("Fire", true) } else null
    }

    fun devices(done: (Result<List<ConnectDevice>>) -> Unit) = async({ parseDevices(api("GET", "/me/player/devices")) }, done)

    fun transfer(device: ConnectDevice, done: (Result<Unit>) -> Unit) = async({
        api("PUT", "/me/player", JSONObject().put("device_ids", JSONArray().put(device.id)).put("play", true).toString()); Unit
    }, done)

    fun transferByName(name: String, done: (Result<String>) -> Unit) = async({
        val list = parseDevices(api("GET", "/me/player/devices"))
        val d = match(list, name) ?: throw Exception(if (list.isEmpty()) "No Spotify Connect devices found — open Spotify on the TV" else
            "No device called \"$name\" (found: ${list.joinToString { it.name }})")
        api("PUT", "/me/player", JSONObject().put("device_ids", JSONArray().put(d.id)).put("play", true).toString())
        "Playing on ${d.name}"
    }, done)

    fun playSearch(query: String, kind: String, done: (Result<String>) -> Unit) = async({
        val types = if (kind.isBlank() || kind == "any") "playlist,artist,album,track" else kind
        val o = JSONObject(api("GET", "/search?q=${Net.enc(query)}&type=$types&limit=1"))
        fun first(key: String) = o.optJSONObject(key)?.optJSONArray("items")?.optJSONObject(0)
        val track = first("tracks")
        val ctx = first("playlists") ?: first("artists") ?: first("albums")
        val body = when {
            kind == "track" && track != null -> JSONObject().put("uris", JSONArray().put(track.getString("uri")))
            ctx != null -> JSONObject().put("context_uri", ctx.getString("uri"))
            track != null -> JSONObject().put("uris", JSONArray().put(track.getString("uri")))
            else -> throw Exception("Nothing found for \"$query\"")
        }
        api("PUT", "/me/player/play", body.toString())
        "Playing " + (ctx?.optString("name") ?: track?.optString("name") ?: query)
    }, done)
}
