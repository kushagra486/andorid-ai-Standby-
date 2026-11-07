package com.nova.integrations

import com.nova.core.DataHub
import com.nova.core.WeatherInfo
import com.nova.storage.Prefs
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/** Small shared HTTP + background helpers (no third-party HTTP library needed). */
object Net {
    val bg = Executors.newFixedThreadPool(2)

    fun get(url: String, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 12_000): String =
        request("GET", url, null, headers, timeoutMs)

    fun request(method: String, url: String, body: String?, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 15_000): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = timeoutMs; conn.readTimeout = timeoutMs
            conn.setRequestProperty("User-Agent", "NOVA-ZenFone/1.0")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.doOutput = true
                if (!headers.keys.any { it.equals("Content-Type", true) }) conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw HttpException(code, text)
            return text
        } finally { conn.disconnect() }
    }

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}

class HttpException(val code: Int, val body: String) : Exception("HTTP $code")

data class Place(val name: String, val detail: String, val lat: Double, val lon: Double)

/**
 * Weather from Open-Meteo (free, no key; attribution "Weather data by Open-Meteo.com").
 * Location is a place the user searches for — no location permission needed.
 * Last good result is cached so the widget still shows something offline.
 */
object Weather {
    private const val REFRESH_MS = 30 * 60_000L
    private var lastFetch = 0L

    fun describe(code: Int): String = when (code) {
        0 -> "Clear"; 1 -> "Mostly clear"; 2 -> "Partly cloudy"; 3 -> "Overcast"
        45, 48 -> "Fog"; 51, 53, 55 -> "Drizzle"; 56, 57 -> "Freezing drizzle"
        61, 63, 65 -> "Rain"; 66, 67 -> "Freezing rain"; 71, 73, 75, 77 -> "Snow"
        80, 81, 82 -> "Showers"; 85, 86 -> "Snow showers"; 95 -> "Thunderstorm"; 96, 99 -> "Storm & hail"
        else -> "—"
    }

    fun forecastUrl(lat: Double, lon: Double) =
        "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,weather_code,is_day,wind_speed_10m" +
            "&daily=temperature_2m_max,temperature_2m_min&hourly=temperature_2m&forecast_hours=12&timezone=auto"

    /** Pure parser (unit-tested). */
    fun parse(json: String, place: String, now: Long = System.currentTimeMillis()): WeatherInfo {
        val o = JSONObject(json)
        val cur = o.getJSONObject("current")
        val daily = o.getJSONObject("daily")
        val hourly = o.optJSONObject("hourly")
        val hours = ArrayList<Pair<Int, Double>>()
        if (hourly != null) {
            val times = hourly.getJSONArray("time"); val temps = hourly.getJSONArray("temperature_2m")
            for (i in 0 until minOf(times.length(), temps.length())) {
                val hour = times.getString(i).substringAfter('T').substringBefore(':').toIntOrNull() ?: continue
                hours += hour to temps.getDouble(i)
            }
        }
        return WeatherInfo(
            place = place,
            tempC = cur.getDouble("temperature_2m"),
            code = cur.getInt("weather_code"),
            isDay = cur.optInt("is_day", 1) == 1,
            highC = daily.getJSONArray("temperature_2m_max").getDouble(0),
            lowC = daily.getJSONArray("temperature_2m_min").getDouble(0),
            windKmh = cur.optDouble("wind_speed_10m", 0.0),
            updatedAt = now,
            hourly = hours.drop(1).take(8),
        )
    }

    fun parsePlaces(json: String): List<Place> {
        val arr = JSONObject(json).optJSONArray("results") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val r = arr.getJSONObject(i)
            Place(r.getString("name"), listOf(r.optString("admin1"), r.optString("country")).filter { it.isNotBlank() }.joinToString(", "),
                r.getDouble("latitude"), r.getDouble("longitude"))
        }
    }

    fun search(query: String, done: (Result<List<Place>>) -> Unit) {
        Net.bg.execute {
            val r = runCatching {
                parsePlaces(Net.get("https://geocoding-api.open-meteo.com/v1/search?name=${Net.enc(query)}&count=6&language=en&format=json"))
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post { done(r) }
        }
    }

    fun loadCache() {
        val cached = Prefs.raw.getString("weatherCache", "") ?: ""
        if (cached.isNotBlank() && DataHub.weather == null) {
            runCatching { DataHub.weather = parse(cached, Prefs.weatherPlace, Prefs.raw.getLong("weatherCacheAt", 0)) }
        }
    }

    fun refresh(force: Boolean = false) {
        val lat = Prefs.weatherLat; val lon = Prefs.weatherLon
        if (lat.isNaN() || lon.isNaN()) { DataHub.weatherStatus = "Set a location in Settings → Weather"; DataHub.changed(); return }
        val now = System.currentTimeMillis()
        if (!force && now - lastFetch < REFRESH_MS) return
        lastFetch = now
        Net.bg.execute {
            try {
                val json = Net.get(forecastUrl(lat.toDouble(), lon.toDouble()))
                val w = parse(json, Prefs.weatherPlace)
                Prefs.raw.edit().putString("weatherCache", json).putLong("weatherCacheAt", now).apply()
                DataHub.weather = w; DataHub.weatherStatus = ""
            } catch (e: Exception) {
                lastFetch = now - REFRESH_MS + 5 * 60_000L   // retry in 5 min
                DataHub.weatherStatus = if (DataHub.weather != null) "Offline — showing last update" else "Weather unavailable (offline?)"
            }
            DataHub.changed()
        }
    }
}
