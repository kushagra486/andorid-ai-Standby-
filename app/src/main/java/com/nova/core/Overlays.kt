package com.nova.core

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.nova.ui.ColorRole
import com.nova.ui.Look
import com.nova.ui.Theme

private fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

private fun rounded(color: Int, radius: Float, stroke: Int? = null) = GradientDrawable().apply {
    setColor(color); cornerRadius = radius
    if (stroke != null) setStroke(1, stroke)
}

/**
 * NØVA quick menu (long-press the pet): big buttons for every family and common actions.
 * Also the accessible, button-based alternative to the swipe gestures (spec §14.4).
 */
class QuickMenu(context: Context, private val onPick: (String) -> Unit) : FrameLayout(context) {

    data class Item(val id: String, val glyph: String, val label: String)

    var items: List<Item> = emptyList()
        set(value) { field = value; build() }

    private val card = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val grid = GridLayout(context).apply { columnCount = 6 }

    init {
        visibility = View.GONE
        isClickable = true
        setOnClickListener { hide() }
        card.setPadding(context.dp(18), context.dp(14), context.dp(18), context.dp(18))
        card.isClickable = true
        card.addView(grid)
        addView(card, LayoutParams(-2, -2, Gravity.CENTER))
    }

    private fun build() {
        setBackgroundColor(Color.argb(170, 0, 0, 0))
        card.background = rounded(Look.c(ColorRole.TILE), context.dp(22).toFloat(), Look.c(ColorRole.TILE_BORDER))
        grid.removeAllViews()
        items.forEach { item ->
            val cell = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                setPadding(context.dp(6), context.dp(8), context.dp(6), context.dp(8))
                isClickable = true
                contentDescription = item.label
                setOnClickListener { hide(); onPick(item.id) }
            }
            cell.addView(TextView(context).apply {
                text = item.glyph; gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f); setTextColor(Look.c(ColorRole.ACCENT))
                background = rounded(Look.c(ColorRole.BACKGROUND), context.dp(16).toFloat())
            }, LinearLayout.LayoutParams(context.dp(64), context.dp(52)))
            cell.addView(TextView(context).apply {
                text = item.label; gravity = Gravity.CENTER; typeface = Theme.regular
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); setTextColor(Look.c(ColorRole.TEXT))
                setPadding(0, context.dp(5), 0, 0); maxLines = 1
            }, LinearLayout.LayoutParams(context.dp(84), -2))
            grid.addView(cell)
        }
    }

    fun show() {
        build()
        visibility = View.VISIBLE; alpha = 0f
        card.scaleX = 0.92f; card.scaleY = 0.92f
        animate().alpha(1f).setDuration(160).start()
        card.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
    }

    fun hide() {
        if (visibility != View.VISIBLE) return
        animate().alpha(0f).setDuration(140).withEndAction { visibility = View.GONE }.start()
    }

    val showing: Boolean get() = visibility == View.VISIBLE
}

/**
 * Quick controls panel pulled down from the top edge: brightness and volume sliders plus
 * toggles. It is NØVA's own panel — Android's notification shade is never imitated or blocked.
 */
class QuickControls(context: Context) : FrameLayout(context) {

    interface Host {
        fun brightness(): Int
        fun setBrightness(pct: Int)
        fun volume(): Int
        fun setVolume(pct: Int)
        fun toggles(): List<Triple<String, String, Boolean>>   // id, label, on
        fun onToggle(id: String)
    }

    var host: Host? = null
    private val panel = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private var panelHeight = 0

    init {
        visibility = View.GONE
        isClickable = true
        setOnClickListener { close() }
        panel.isClickable = true
        addView(panel, LayoutParams(context.dp(600), -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
    }

    private fun build() {
        val h = host ?: return
        setBackgroundColor(Color.argb(140, 0, 0, 0))
        panel.background = rounded(Look.c(ColorRole.TILE), context.dp(24).toFloat(), Look.c(ColorRole.TILE_BORDER))
        panel.setPadding(context.dp(22), context.dp(16), context.dp(22), context.dp(16))
        panel.removeAllViews()
        panel.addView(slider("☀  Brightness", h.brightness()) { h.setBrightness(it) })
        panel.addView(slider("🔊  Volume", h.volume()) { h.setVolume(it) })
        var row = LinearLayout(context)
        h.toggles().forEachIndexed { i, (id, label, on) ->
            if (i % 4 == 0) { row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }; panel.addView(row) }
            row.addView(TextView(context).apply {
                text = label; gravity = Gravity.CENTER; typeface = Theme.medium
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(if (on) Theme.onColor(Look.c(ColorRole.ACCENT)) else Look.c(ColorRole.TEXT))
                background = rounded(if (on) Look.c(ColorRole.ACCENT) else Look.c(ColorRole.BACKGROUND), context.dp(18).toFloat())
                setPadding(context.dp(12), context.dp(9), context.dp(12), context.dp(9))
                setOnClickListener { h.onToggle(id); build() }
                maxLines = 1
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(context.dp(4), context.dp(10), context.dp(4), 0) })
        }
        panel.addView(TextView(context).apply {
            text = "Swipe up or tap outside to close"; gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f); setTextColor(Look.c(ColorRole.TEXT_2))
            setPadding(0, context.dp(10), 0, 0)
        })
    }

    private fun slider(label: String, value: Int, onChange: (Int) -> Unit): View {
        val box = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        box.addView(TextView(context).apply { text = label; setTextColor(Look.c(ColorRole.TEXT)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f) },
            LinearLayout.LayoutParams(context.dp(150), -2))
        box.addView(SeekBar(context).apply {
            max = 100; progress = value
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) onChange(p) }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }, LinearLayout.LayoutParams(0, context.dp(44), 1f))
        return box
    }

    /** Follow a pull from the top edge; [release] decides open/close. */
    fun pull(progress: Float, release: Boolean) {
        if (visibility != View.VISIBLE) { build(); visibility = View.VISIBLE; panel.measure(0, 0); panelHeight = panel.measuredHeight.coerceAtLeast(context.dp(200)) }
        val p = progress.coerceIn(0f, 1f)
        if (!release) {
            alpha = p.coerceAtLeast(0.15f)
            panel.translationY = -panelHeight * (1 - p)
            return
        }
        if (p > 0.3f) open() else close()
    }

    fun open() {
        if (visibility != View.VISIBLE) { build(); visibility = View.VISIBLE; panel.translationY = -context.dp(240).toFloat(); alpha = 0f }
        animate().alpha(1f).setDuration(160).start()
        panel.animate().translationY(0f).setDuration(200).start()
    }

    fun close() {
        if (visibility != View.VISIBLE) return
        panel.animate().translationY(-panel.height.toFloat().coerceAtLeast(200f)).setDuration(180).start()
        animate().alpha(0f).setDuration(180).withEndAction { visibility = View.GONE }.start()
    }

    val showing: Boolean get() = visibility == View.VISIBLE
}
