package com.nova

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import com.nova.audio.SoundFx
import com.nova.dashboard.DashboardView
import com.nova.pet.PetView
import com.nova.settings.NovaSettings
import com.nova.settings.SettingsView

/**
 * NØVA host activity. Landscape, immersive, screen kept awake.
 * Screens: Pixel Pet (default) ⇄ StandBy-style clock (swipe or long-press) → Settings.
 * Clock and Settings return to the pet automatically after inactivity.
 */
class MainActivity : Activity() {

    private enum class Screen { PET, CLOCK, SETTINGS }

    private lateinit var sound: SoundFx
    private lateinit var pet: PetView
    private lateinit var clock: DashboardView
    private lateinit var settingsView: SettingsView
    private val prefs by lazy { getSharedPreferences("nova", MODE_PRIVATE) }
    private var settings = NovaSettings()
    private var screen = Screen.PET

    private val returnToPet = Runnable { show(Screen.PET) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        settings = NovaSettings.load(prefs)
        sound = SoundFx()
        pet = PetView(this, sound)
        clock = DashboardView(this)
        settingsView = SettingsView(this, BuildConfig.VERSION_NAME)
        applySettings()

        pet.listener = object : PetView.Listener {
            override fun onLongPress() = show(Screen.CLOCK)
            override fun onSwipe() = show(Screen.CLOCK)
            override fun onExpressionTestFinished() = show(Screen.SETTINGS)
        }
        clock.listener = object : DashboardView.Listener {
            override fun onDismiss() = show(Screen.PET)
            override fun onOpenSettings() = show(Screen.SETTINGS)
        }
        settingsView.listener = object : SettingsView.Listener {
            override fun onChanged(settings: NovaSettings) {
                this@MainActivity.settings = settings
                settings.save(prefs)
                applySettings()
                scheduleReturn()
            }
            override fun onTestExpressions() {
                show(Screen.PET)
                pet.startExpressionTest()
            }
            override fun onDone() = show(Screen.PET)
        }

        setContentView(FrameLayout(this).apply {
            addView(pet)
            addView(clock)
            addView(settingsView)
        })
        clock.visibility = View.GONE
        settingsView.visibility = View.GONE
    }

    private fun applySettings() {
        sound.enabled = settings.soundOn
        sound.volume = settings.volume.gain
        pet.apply(settings)
        clock.apply(settings)
        settingsView.settings = settings
    }

    private fun viewFor(s: Screen): View = when (s) {
        Screen.PET -> pet
        Screen.CLOCK -> clock
        Screen.SETTINGS -> settingsView
    }

    /** Cross-fade to another screen; only the visible screen does any animation work. */
    private fun show(target: Screen) {
        window.decorView.removeCallbacks(returnToPet)
        if (target == screen) { if (target != Screen.PET) scheduleReturn(); return }
        val from = viewFor(screen)
        val to = viewFor(target)
        pet.touched()
        when (screen) {
            Screen.PET -> pet.pause()
            Screen.CLOCK -> clock.pause()
            Screen.SETTINGS -> Unit
        }
        screen = target
        when (target) {
            Screen.PET -> pet.resume()
            Screen.CLOCK -> clock.resume()
            Screen.SETTINGS -> settingsView.scrollTo(0, 0)
        }
        to.alpha = 0f
        to.visibility = View.VISIBLE
        to.bringToFront()
        to.animate().alpha(1f).setDuration(220).withEndAction {
            from.visibility = View.GONE
        }.start()
        if (target != Screen.PET) scheduleReturn()
    }

    private fun scheduleReturn() {
        window.decorView.removeCallbacks(returnToPet)
        val delay = if (screen == Screen.SETTINGS) 60_000L else 30_000L
        window.decorView.postDelayed(returnToPet, delay)
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        if (screen != Screen.PET) scheduleReturn()
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        when (screen) {
            Screen.PET -> pet.resume()
            Screen.CLOCK -> clock.resume()
            Screen.SETTINGS -> Unit
        }
    }

    override fun onPause() {
        // Stop all animation work while in the background (battery + heat on old hardware).
        pet.pause()
        clock.pause()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    override fun onDestroy() {
        window.decorView.removeCallbacks(returnToPet)
        sound.release()
        super.onDestroy()
    }

    @Suppress("DEPRECATION") // WindowInsetsController is API 30+; the ZenFone runs API 24.
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }
}
