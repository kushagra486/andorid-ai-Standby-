package com.nova

import android.net.Uri
import com.nova.ai.AiClient
import com.nova.home.SmartHome
import com.nova.integrations.Weather
import com.nova.music.ConnectDevice
import com.nova.music.Spotify
import com.nova.ui.ColorRole
import com.nova.ui.Palette
import com.nova.ui.ThemePreset
import com.nova.voice.WakeWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Unit tests that need real Android classes (Uri, Base64, Color). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RobolectricUnitTests {

    @Test fun `palette json round trip and custom role edit`() {
        val p = ThemePreset.NOVA.with(ColorRole.EYES, 0xFFFF00AA.toInt())
        val back = Palette.fromJson(p.toJson(), ThemePreset.NOVA)
        ColorRole.entries.forEach { assertEquals(it.name, p[it], back[it]) }
        assertEquals(0xFFFF00AA.toInt(), back[ColorRole.EYES])
        assertEquals(0xFF36DFFF.toInt(), ThemePreset.NOVA[ColorRole.ACCENT])         // spec v1.1 electric cyan
        assertEquals(0xFF05090D.toInt(), ThemePreset.NOVA[ColorRole.BACKGROUND])
        assertEquals(ThemePreset.NOVA[ColorRole.TEXT], Palette.fromJson("garbage", ThemePreset.NOVA)[ColorRole.TEXT])
    }

    @Test fun `every preset defines every role`() {
        ThemePreset.entries.forEach { t -> ColorRole.entries.forEach { r -> assertTrue("$t $r", t.palette()[r] != android.graphics.Color.MAGENTA) } }
    }

    @Test fun `weather parsing`() {
        val json = """{"current":{"temperature_2m":24.3,"weather_code":3,"is_day":1,"wind_speed_10m":7.2},
            "daily":{"temperature_2m_max":[29.1],"temperature_2m_min":[18.4]},
            "hourly":{"time":["2026-11-07T21:00","2026-11-07T22:00","2026-11-07T23:00"],"temperature_2m":[24,23,22]}}"""
        val w = Weather.parse(json, "Pune")
        assertEquals(24.3, w.tempC, 0.01); assertEquals(3, w.code); assertEquals(29.1, w.highC, 0.01)
        assertEquals(listOf(22 to 23.0, 23 to 22.0), w.hourly)
        assertEquals("Overcast", Weather.describe(3))
        val places = Weather.parsePlaces("""{"results":[{"name":"Pune","admin1":"Maharashtra","country":"India","latitude":18.5,"longitude":73.8}]}""")
        assertEquals("Maharashtra, India", places[0].detail)
    }

    @Test fun `spotify pkce and device matching`() {
        // RFC 7636 appendix B test vector.
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Spotify.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        assertTrue(Spotify.newVerifier().length >= 43)
        val devices = listOf(ConnectDevice("1", "Kushagra's Phone", "Smartphone", true), ConnectDevice("2", "Fire TV Stick", "TV", false))
        assertEquals("2", Spotify.match(devices, "fire tv")!!.id)
        assertEquals("2", Spotify.match(devices, "the tv")!!.id)
        assertNull(Spotify.match(devices, "kitchen"))
        assertEquals(2, Spotify.parseDevices("""{"devices":[{"id":"a","name":"A","type":"TV","is_active":true},{"id":"b","name":"B","type":"Speaker"},{"name":"no id"}]}""").size)
    }

    @Test fun `smart home address rules`() {
        assertNull(SmartHome.checkUrl("https://lamp.example/command"))
        assertNull(SmartHome.checkUrl("http://192.168.1.50/command"))
        assertNull(SmartHome.checkUrl("http://desk-lamp.local/cmd"))
        assertNotNull(SmartHome.checkUrl("http://example.com/command"))
        assertNotNull(SmartHome.checkUrl("ftp://192.168.1.2"))
        assertTrue(SmartHome.isPrivateHost("172.20.0.4")); assertTrue(!SmartHome.isPrivateHost("172.40.0.4"))
    }

    @Test fun `wake word sensitivity`() {
        assertTrue(WakeWord.isWake("""{"text":"hey nova"}""", false, 1))
        assertTrue(!WakeWord.isWake("""{"partial":"hey nova"}""", true, 1))
        assertTrue(WakeWord.isWake("""{"partial":"hey nova"}""", true, 2))
        assertTrue(!WakeWord.isWake("""{"partial":"nova"}""", true, 2))
        assertTrue(WakeWord.isWake("""{"partial":"nova"}""", true, 3))
        assertTrue(!WakeWord.isWake("""{"text":""}""", false, 3))
    }

    @Test fun `ai responses are validated before use`() {
        val (ok, err) = AiClient.parse("""{"reply":"Timer on","action":{"type":"SET_TIMER","args":{"minutes":3}}}""")
        assertEquals("Timer on", ok.text); assertNotNull(ok.action); assertNull(err)
        val (bad, why) = AiClient.parse("""{"reply":"Sure","action":{"type":"DELETE_ALL_FILES","args":{}}}""")
        assertNull(bad.action); assertNotNull(why)
        val (plain, _) = AiClient.parse("""{"reply":"Paris is the capital of France."}""")
        assertNull(plain.action)
    }

    @Test fun `spotify auth url uses pkce`() {
        com.nova.storage.Prefs.init(org.robolectric.RuntimeEnvironment.getApplication())
        com.nova.storage.Prefs.spotifyClientId = "abc"
        val u: Uri = Spotify.authUrl()
        assertEquals("S256", u.getQueryParameter("code_challenge_method"))
        assertEquals(Spotify.REDIRECT, u.getQueryParameter("redirect_uri"))
        assertNull(u.getQueryParameter("client_secret"))
    }
}
