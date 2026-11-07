package com.nova.settings

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.nova.ui.Theme

/**
 * Settings in the style of an iOS grouped list: section captions, rounded inset cards,
 * hairline separators, grey values with a chevron, and pill switches.
 * Tapping a value row cycles through its options.
 */
class SettingsView(context: Context, private val versionName: String) : ScrollView(context) {

    interface Listener {
        fun onChanged(settings: NovaSettings)
        fun onTestExpressions()
        fun onDone()
    }

    var listener: Listener? = null
    var settings = NovaSettings()
        set(value) { field = value; if (!quiet) render() }
    private var quiet = false

    private val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    init {
        setBackgroundColor(Color.BLACK)
        isFillViewport = true
        isVerticalScrollBarEnabled = false
        val wrapper = FrameLayout(context)
        // Centre a readable column on the wide landscape screen.
        wrapper.addView(column, FrameLayout.LayoutParams(dp(520), FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER_HORIZONTAL))
        column.setPadding(0, dp(12), 0, dp(28))
        addView(wrapper)
        render()
    }

    private fun update(s: NovaSettings) {
        settings = s
        listener?.onChanged(s)
    }

    private fun render() {
        column.removeAllViews()

        // Header: large title + "Done", like an iOS navigation bar.
        val header = FrameLayout(context)
        header.addView(text("Settings", 30f, Theme.LABEL, Theme.medium),
            FrameLayout.LayoutParams(-2, -2, Gravity.START or Gravity.CENTER_VERTICAL))
        header.addView(text("Done", 17f, Theme.CYAN, Theme.medium).apply {
            setPadding(dp(16), dp(8), dp(4), dp(8))
            setOnClickListener { listener?.onDone() }
        }, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL))
        column.addView(header, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(16), 0, dp(4), dp(4)) })

        section("Sound") {
            // Don't rebuild the list for a switch, so its slide animation stays visible.
            toggleRow("Sounds", settings.soundOn) { quiet = true; update(settings.copy(soundOn = it)); quiet = false }
            valueRow("Volume", settings.volume.label) { update(settings.copy(volume = settings.volume.next())) }
        }
        section("Display", footer = "Night mode dims the screen, mutes sounds and lets NØVA fall asleep sooner.") {
            valueRow("Animation", settings.intensity.label) { update(settings.copy(intensity = settings.intensity.next())) }
            valueRow("Night mode", settings.nightMode.label) { update(settings.copy(nightMode = settings.nightMode.next())) }
            valueRow("Night look", settings.nightStyle.label) { update(settings.copy(nightStyle = settings.nightStyle.next())) }
        }
        section("Check", footer = "Plays every expression with its sound, one after another.") {
            actionRow("Test all expressions") { listener?.onTestExpressions() }
        }
        section("About") {
            valueRow("Version", versionName, chevron = false, onClick = null)
        }
    }

    // ---- Building blocks ----------------------------------------------------------------

    private class SectionBuilder(val card: LinearLayout, val context: Context)

    private fun section(title: String, footer: String? = null, build: SectionBuilder.() -> Unit) {
        column.addView(text(title.uppercase(), 13f, Theme.SECONDARY, Theme.regular),
            LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(32), dp(22), dp(16), dp(6)) })
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(Theme.CARD); cornerRadius = dp(12).toFloat() }
            clipToOutline = true
        }
        SectionBuilder(card, context).build()
        column.addView(card, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(16), 0, dp(16), 0) })
        if (footer != null) {
            column.addView(text(footer, 13f, Theme.SECONDARY, Theme.regular),
                LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(32), dp(6), dp(32), 0) })
        }
    }

    private fun SectionBuilder.addRow(row: View) {
        if (card.childCount > 0) {
            card.addView(View(context).apply { setBackgroundColor(Theme.SEPARATOR) },
                LinearLayout.LayoutParams(-1, 1).apply { leftMargin = dp(16) })
        }
        card.addView(row, LinearLayout.LayoutParams(-1, dp(46)))
    }

    private fun rowFrame(onClick: (() -> Unit)?): FrameLayout = FrameLayout(context).apply {
        setPadding(dp(16), 0, dp(14), 0)
        if (onClick != null) {
            isClickable = true
            setOnClickListener { onClick() }
            setOnTouchListener { v, e ->
                // iOS-style press highlight.
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> v.setBackgroundColor(Theme.CARD_HIGHLIGHT)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.setBackgroundColor(Color.TRANSPARENT)
                }
                false
            }
        }
    }

    private fun SectionBuilder.valueRow(label: String, value: String, chevron: Boolean = true, onClick: (() -> Unit)?) {
        val row = rowFrame(onClick)
        row.addView(text(label, 17f, Theme.LABEL, Theme.regular),
            FrameLayout.LayoutParams(-2, -2, Gravity.START or Gravity.CENTER_VERTICAL))
        row.addView(text(if (chevron) "$value  ›" else value, 17f, Theme.SECONDARY, Theme.regular),
            FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL))
        addRow(row)
    }

    private fun SectionBuilder.actionRow(label: String, onClick: () -> Unit) {
        val row = rowFrame(onClick)
        row.addView(text(label, 17f, Theme.CYAN, Theme.regular),
            FrameLayout.LayoutParams(-2, -2, Gravity.START or Gravity.CENTER_VERTICAL))
        addRow(row)
    }

    private fun SectionBuilder.toggleRow(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
        val toggle = PillSwitch(context, on)
        val row = rowFrame { toggle.toggle(); onChange(toggle.on) }
        row.addView(text(label, 17f, Theme.LABEL, Theme.regular),
            FrameLayout.LayoutParams(-2, -2, Gravity.START or Gravity.CENTER_VERTICAL))
        row.addView(toggle, FrameLayout.LayoutParams(dp(51), dp(31), Gravity.END or Gravity.CENTER_VERTICAL))
        addRow(row)
    }

    private fun text(s: String, sp: Float, color: Int, face: android.graphics.Typeface) = TextView(context).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        typeface = face
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private inline fun <reified T : Enum<T>> T.next(): T {
        val all = enumValues<T>()
        return all[(ordinal + 1) % all.size]
    }
}

/** An iOS-style switch: green pill track with a white knob that slides. */
private class PillSwitch(context: Context, initial: Boolean) : View(context) {
    var on = initial
        private set
    private var progress = if (initial) 1f else 0f
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val rect = RectF()
    private val green = Color.rgb(0x34, 0xC7, 0x59)

    fun toggle() {
        on = !on
        ValueAnimator.ofFloat(progress, if (on) 1f else 0f).apply {
            duration = 220
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        track.color = Theme.lerpColor(Theme.SEPARATOR, green, progress)
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, h / 2, h / 2, track)
        val r = h / 2 - h * 0.065f
        val x = h / 2 + (w - h) * progress
        canvas.drawCircle(x, h / 2, r, knob)
    }
}
