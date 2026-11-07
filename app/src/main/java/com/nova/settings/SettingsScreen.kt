package com.nova.settings

import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.nova.storage.Prefs
import com.nova.ui.ColorRole
import com.nova.ui.Look
import com.nova.ui.Theme

/** One Settings page: a title and a builder that fills it. Rebuilt whenever settings change. */
class SettingsPage(val title: String, val build: SettingsBuilder.() -> Unit)

/**
 * Settings shell in the style of an iOS navigation stack: a header with back chevron,
 * title and Done, and a scrolling grouped list. Pages are pushed/popped with a slide.
 */
class SettingsScreen(context: Context) : FrameLayout(context) {

    var onDone: (() -> Unit)? = null

    private val stack = ArrayList<SettingsPage>()
    private val header = FrameLayout(context)
    private val backButton = TextView(context)
    private val titleView = TextView(context)
    private val doneButton = TextView(context)
    private val scroll = ScrollView(context)
    private val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    // Coalesce bursts of setting writes and let switch animations finish before rebuilding.
    private val refreshRunnable = Runnable { if (isShown) refresh() }
    private val prefsListener = { removeCallbacks(refreshRunnable); postDelayed(refreshRunnable, 240); Unit }

    init {
        isClickable = true   // swallow touches so nothing underneath reacts
        scroll.isVerticalScrollBarEnabled = false
        scroll.isFillViewport = true
        val wrapper = FrameLayout(context)
        wrapper.addView(column, LayoutParams(dp(560), LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
        column.setPadding(0, 0, 0, dp(32))
        scroll.addView(wrapper)

        backButton.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f); typeface = Theme.regular
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setOnClickListener { pop() }
        }
        titleView.apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f); typeface = Theme.medium }
        doneButton.apply {
            text = "Done"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f); typeface = Theme.medium
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setOnClickListener { onDone?.invoke() }
        }
        header.addView(backButton, LayoutParams(-2, -2, Gravity.START or Gravity.CENTER_VERTICAL))
        header.addView(titleView, LayoutParams(-2, -2, Gravity.CENTER))
        header.addView(doneButton, LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL))
        addView(header, LayoutParams(-1, dp(48), Gravity.TOP))
        addView(scroll, LayoutParams(-1, -1).apply { topMargin = dp(48) })
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); Prefs.addListener(prefsListener) }
    override fun onDetachedFromWindow() { Prefs.removeListener(prefsListener); super.onDetachedFromWindow() }

    fun open(root: SettingsPage) {
        stack.clear(); stack += root; render(0)
    }

    fun push(page: SettingsPage) { stack += page; render(+1) }

    fun pop(): Boolean {
        if (stack.size <= 1) { onDone?.invoke(); return false }
        stack.removeAt(stack.lastIndex); render(-1); return true
    }

    /** Rebuild the current page in place (keeps scroll position). */
    fun refresh() {
        val y = scroll.scrollY
        renderContent()
        scroll.post { scroll.scrollTo(0, y) }
    }

    private fun render(direction: Int) {
        renderContent()
        scroll.scrollTo(0, 0)
        if (direction != 0) {
            scroll.translationX = direction * width * 0.3f
            scroll.alpha = 0.3f
            scroll.animate().translationX(0f).alpha(1f).setDuration(220).start()
        }
    }

    private fun renderContent() {
        val page = stack.lastOrNull() ?: return
        setBackgroundColor(Look.c(ColorRole.BACKGROUND))
        header.setBackgroundColor(Look.c(ColorRole.BACKGROUND))
        val accent = Look.c(ColorRole.ACCENT)
        backButton.setTextColor(accent); doneButton.setTextColor(accent)
        backButton.text = if (stack.size > 1) "‹  ${stack[stack.size - 2].title}" else ""
        backButton.visibility = if (stack.size > 1) View.VISIBLE else View.INVISIBLE
        titleView.setTextColor(Look.c(ColorRole.TEXT))
        titleView.text = if (stack.size > 1) page.title else ""
        column.removeAllViews()
        if (stack.size == 1) {
            column.addView(label(page.title, 30f, Look.c(ColorRole.TEXT), Theme.medium),
                LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(20), dp(4), dp(16), dp(4)) })
        }
        SettingsBuilder(this, column).apply(page.build)
    }

    internal fun label(s: String, sp: Float, color: Int, face: Typeface) = TextView(context).apply {
        text = s; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); setTextColor(color); typeface = face
    }

    internal fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Simple text entry dialog, styled dark. */
    internal fun editText(title: String, value: String, hint: String, secret: Boolean, numeric: Boolean, onSave: (String) -> Unit) {
        val input = EditText(context).apply {
            setText(value); setHint(hint); setSingleLine(true)
            inputType = when {
                secret -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                numeric -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or InputType.TYPE_NUMBER_FLAG_DECIMAL
                else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            }
            setSelection(text.length)
        }
        val box = FrameLayout(context).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input) }
        AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(title).setView(box)
            .setPositiveButton("Save") { _, _ -> onSave(input.text.toString().trim()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    internal fun confirm(title: String, message: String, yes: String, onYes: () -> Unit) {
        AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(title).setMessage(message)
            .setPositiveButton(yes) { _, _ -> onYes() }
            .setNegativeButton("Cancel", null).show()
    }
}

/** DSL used by pages to add grouped sections and rows. */
class SettingsBuilder(val screen: SettingsScreen, private val column: LinearLayout) {
    private val context = screen.context
    private var card: LinearLayout? = null

    private fun dp(v: Int) = screen.dp(v)
    private val text get() = Look.c(ColorRole.TEXT)
    private val text2 get() = Look.c(ColorRole.TEXT_2)
    private val accent get() = Look.c(ColorRole.ACCENT)

    fun section(title: String? = null, footer: String? = null, rows: SettingsBuilder.() -> Unit) {
        if (title != null) {
            column.addView(screen.label(title.uppercase(), 13f, text2, Theme.regular),
                LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(36), dp(20), dp(16), dp(6)) })
        } else {
            column.addView(View(context), LinearLayout.LayoutParams(-1, dp(18)))
        }
        val c = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Look.c(ColorRole.TILE)); cornerRadius = dp(12).toFloat()
                setStroke(1, Look.c(ColorRole.TILE_BORDER))
            }
            clipToOutline = true
        }
        card = c
        rows()
        card = null
        column.addView(c, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(20), 0, dp(20), 0) })
        if (footer != null) {
            column.addView(screen.label(footer, 13f, text2, Theme.regular),
                LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(36), dp(6), dp(36), 0) })
        }
    }

    private fun addRow(row: View, height: Int = dp(46)) {
        val c = card ?: error("row outside section")
        if (c.childCount > 0) {
            c.addView(View(context).apply { setBackgroundColor(Look.c(ColorRole.TILE_BORDER)) },
                LinearLayout.LayoutParams(-1, 1).apply { leftMargin = dp(16) })
        }
        c.addView(row, LinearLayout.LayoutParams(-1, height))
    }

    private fun frame(onClick: (() -> Unit)?): FrameLayout = FrameLayout(context).apply {
        setPadding(dp(16), 0, dp(14), 0)
        if (onClick != null) {
            isClickable = true
            setOnClickListener { onClick() }
            val pressed = Theme.lerpColor(Look.c(ColorRole.TILE), Look.c(ColorRole.TEXT), 0.08f)
            setOnTouchListener { v, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> v.setBackgroundColor(pressed)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.setBackgroundColor(Color.TRANSPARENT)
                }
                false
            }
        }
    }

    private fun left(row: FrameLayout, s: String, color: Int = text) =
        row.addView(screen.label(s, 17f, color, Theme.regular),
            FrameLayout.LayoutParams(-2, -2, Gravity.START or Gravity.CENTER_VERTICAL))

    private fun right(row: FrameLayout, s: String, color: Int = text2) =
        row.addView(screen.label(s, 17f, color, Theme.regular).apply { maxWidth = dp(300); setSingleLine(true) },
            FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL))

    /** Row that opens another page. */
    fun nav(label: String, value: String = "", page: () -> SettingsPage) {
        val row = frame { screen.push(page()) }
        left(row, label); right(row, if (value.isEmpty()) "›" else "$value  ›")
        addRow(row)
    }

    /** Value row that opens a checkmark list of [options]. */
    fun <T> choice(label: String, options: List<T>, current: T, name: (T) -> String, footer: String? = null, onPick: (T) -> Unit) {
        nav(label, name(current)) {
            SettingsPage(label) {
                section(footer = footer) {
                    options.forEach { o ->
                        val row = frame { onPick(o); screen.pop() }
                        left(row, name(o))
                        if (o == current) right(row, "✓", accent)
                        addRow(row)
                    }
                }
            }
        }
    }

    fun toggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
        val sw = PillSwitch(context, on)
        val row = frame { sw.toggle(); onChange(sw.on) }
        left(row, label)
        row.addView(sw, FrameLayout.LayoutParams(dp(51), dp(31), Gravity.END or Gravity.CENTER_VERTICAL))
        addRow(row)
    }

    fun action(label: String, color: Int = accent, onClick: () -> Unit) {
        val row = frame(onClick); left(row, label, color); addRow(row)
    }

    fun info(label: String, value: String) {
        val row = frame(null); left(row, label); right(row, value); addRow(row)
    }

    /** Multi-line explanatory text inside a card. */
    fun note(textValue: String) {
        val tv = screen.label(textValue, 15f, text2, Theme.regular).apply { setPadding(dp(16), dp(12), dp(16), dp(12)) }
        addRow(tv, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    fun textField(label: String, value: String, hint: String = "", secret: Boolean = false, numeric: Boolean = false, onSave: (String) -> Unit) {
        val shown = when { value.isEmpty() -> "Not set"; secret -> "••••••"; else -> value }
        val row = frame { screen.editText(label, if (secret) "" else value, hint, secret, numeric, onSave) }
        left(row, label); right(row, "$shown  ›")
        addRow(row)
    }

    fun slider(label: String, min: Int, max: Int, value: Int, format: (Int) -> String, onChange: (Int) -> Unit) {
        val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(6)) }
        val top = FrameLayout(context)
        val valueLabel = screen.label(format(value), 15f, text2, Theme.regular)
        top.addView(screen.label(label, 17f, text, Theme.regular), FrameLayout.LayoutParams(-2, -2, Gravity.START))
        top.addView(valueLabel, FrameLayout.LayoutParams(-2, -2, Gravity.END))
        val bar = SeekBar(context).apply {
            this.max = max - min; progress = value - min
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { valueLabel.text = format(p + min) }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) { onChange(s.progress + min) }
            })
        }
        box.addView(top); box.addView(bar)
        addRow(box, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    /** Row with a colour swatch on the right. */
    fun swatch(label: String, color: Int, onClick: () -> Unit) {
        val row = frame(onClick)
        left(row, label)
        row.addView(SwatchView(context, color), FrameLayout.LayoutParams(dp(60), dp(28), Gravity.END or Gravity.CENTER_VERTICAL))
        addRow(row)
    }

    /** Arbitrary view inside the current card (or as its own block outside sections). */
    fun custom(view: View, height: Int = LinearLayout.LayoutParams.WRAP_CONTENT) {
        if (card != null) addRow(view, height)
        else column.addView(view, LinearLayout.LayoutParams(-1, height).apply { setMargins(dp(20), dp(12), dp(20), 0) })
    }
}

/** Rounded colour chip with a chevron-like hint, used in colour rows. */
class SwatchView(context: Context, var color: Int) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        r.set(width - h * 1.6f, h * 0.1f, width - h * 0.45f, h * 0.9f)
        p.style = Paint.Style.FILL; p.color = color
        canvas.drawRoundRect(r, h * 0.25f, h * 0.25f, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f; p.color = Theme.withAlpha(Color.WHITE, 60)
        canvas.drawRoundRect(r, h * 0.25f, h * 0.25f, p)
        p.style = Paint.Style.FILL; p.color = Look.c(ColorRole.TEXT_2); p.textSize = h * 0.6f; p.textAlign = Paint.Align.RIGHT
        canvas.drawText("›", width.toFloat(), h * 0.72f, p)
    }
}

/** An iOS-style switch: pill track (theme "Switches" colour) with a white knob that slides. */
class PillSwitch(context: Context, initial: Boolean) : View(context) {
    var on = initial
        private set
    private var progress = if (initial) 1f else 0f
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val rect = RectF()

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
        track.color = Theme.lerpColor(Look.c(ColorRole.TILE_BORDER), Look.c(ColorRole.SWITCH_ON), progress)
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, h / 2, h / 2, track)
        canvas.drawCircle(h / 2 + (w - h) * progress, h / 2, h / 2 - h * 0.065f, knob)
    }
}
