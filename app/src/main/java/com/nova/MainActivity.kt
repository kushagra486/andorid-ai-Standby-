package com.nova

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import com.nova.audio.SoundFx
import com.nova.dashboard.DashboardView
import com.nova.pet.Expression
import com.nova.pet.PetView

/**
 * NØVA v0.1 — Stage 1 foundation.
 * Landscape, immersive, screen kept awake. Pixel Pet is the default mode;
 * long-press opens the clock dashboard, which auto-returns after inactivity.
 */
class MainActivity : Activity() {

    private lateinit var sound: SoundFx
    private lateinit var pet: PetView
    private lateinit var dashboard: DashboardView
    private val prefs by lazy { getSharedPreferences("nova", MODE_PRIVATE) }

    private val dashboardTimeoutMs = 30_000L
    private val returnToPet = Runnable { showPet() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        sound = SoundFx().apply { muted = prefs.getBoolean("muted", false) }
        pet = PetView(this, sound)
        dashboard = DashboardView(this).apply {
            muted = sound.muted
            visibility = View.GONE
        }

        pet.listener = object : PetView.Listener {
            override fun onLongPress() = showDashboard()
        }
        dashboard.listener = object : DashboardView.Listener {
            override fun onDismiss() = showPet()
            override fun onToggleSound(): Boolean {
                sound.muted = !sound.muted
                prefs.edit().putBoolean("muted", sound.muted).apply()
                scheduleReturn()
                return sound.muted
            }
        }

        setContentView(FrameLayout(this).apply {
            addView(pet)
            addView(dashboard)
        })
    }

    private fun showDashboard() {
        pet.pause()
        pet.visibility = View.GONE
        dashboard.visibility = View.VISIBLE
        dashboard.resume()
        scheduleReturn()
    }

    private fun showPet() {
        dashboard.removeCallbacks(returnToPet)
        dashboard.pause()
        dashboard.visibility = View.GONE
        pet.visibility = View.VISIBLE
        pet.touched()
        pet.resume()
        pet.react(Expression.HAPPY)
    }

    private fun scheduleReturn() {
        dashboard.removeCallbacks(returnToPet)
        dashboard.postDelayed(returnToPet, dashboardTimeoutMs)
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        if (dashboard.visibility == View.VISIBLE) dashboard.resume() else pet.resume()
    }

    override fun onPause() {
        // Stop all animation work while in the background (battery + heat on old hardware).
        pet.pause()
        dashboard.pause()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    override fun onDestroy() {
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
