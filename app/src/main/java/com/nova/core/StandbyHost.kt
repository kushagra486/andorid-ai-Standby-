package com.nova.core

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import com.nova.storage.Prefs
import com.nova.ui.ColorRole
import com.nova.ui.Look
import com.nova.ui.Theme
import kotlin.math.abs

/** The StandBy layout families (spec v1.1 §14.3), in swipe order. */
enum class Family(val label: String) {
    PET("Pixel Pet"), CLOCK("Clocks"), DUAL("Dual widget"), DASHBOARD("Dashboard"),
    PHOTOS("Photo frame"), MUSIC("Music"), ASSISTANT("Assistant");

    companion object {
        fun byName(n: String) = entries.firstOrNull { it.name == n } ?: PET
    }
}

/** One full-screen family page. Variations are cycled with vertical swipes. */
interface FamilyPage {
    val family: Family
    val view: View
    val variationCount: Int
    var variation: Int
    fun variationLabel(i: Int): String
    fun onShow() {}
    fun onHide() {}
    /** Back/escape inside the page (e.g. leave edit mode). Return true if consumed. */
    fun onBack(): Boolean = false
    /** True while the page owns all gestures (edit mode, scrubbing…). */
    val capturesGestures: Boolean get() = false
}

/**
 * Hosts the family pages and routes gestures (spec v1.1 §14.4):
 *  - horizontal swipe → previous/next family (page follows the finger, StandBy-style),
 *  - vertical swipe → previous/next variation inside the family,
 *  - swipe down from the top edge → NØVA quick controls.
 * Gestures are offered to the page's own views first: only movement that a child did not
 * claim (via requestDisallowInterceptTouchEvent) and that clearly exceeds touch slop navigates.
 */
class StandbyHost(context: Context, private val factory: (Family) -> FamilyPage) : FrameLayout(context) {

    interface Listener {
        fun onFamilyChanged(family: Family)
        fun onQuickControlsPull(progress: Float, release: Boolean)
        fun onUserActivity()
        fun onSettingsButton()
    }

    var listener: Listener? = null

    private val pages = HashMap<Family, FamilyPage>()
    var current: Family = Family.PET
        private set
    val currentPage: FamilyPage get() = page(current)

    private val slop = ViewConfiguration.get(context).scaledTouchSlop * 1.5f
    private val edge = 28 * resources.displayMetrics.density
    private var downX = 0f; private var downY = 0f
    private var mode = 0                 // 0 none, 1 horizontal, 2 vertical, 3 quick controls
    private var velocity: VelocityTracker? = null
    private var neighbour: Family? = null

    private val overlay = Overlay(context)
    private val gear = GearButton(context).apply { setOnClickListener { listener?.onSettingsButton() } }

    init {
        addView(overlay, LayoutParams(-1, -1))
        val d = resources.displayMetrics.density
        addView(gear, LayoutParams((44 * d).toInt(), (44 * d).toInt(), android.view.Gravity.TOP or android.view.Gravity.END).apply {
            topMargin = (2 * d).toInt(); rightMargin = (2 * d).toInt()
        })
    }

    /** Hide the corner settings button while a page owns the screen (e.g. layout editing). */
    fun refreshChrome() {
        gear.visibility = if (pages[current]?.capturesGestures == true) View.GONE else View.VISIBLE
        gear.invalidate()
    }

    fun page(f: Family): FamilyPage = pages.getOrPut(f) {
        factory(f).also { p ->
            p.variation = Prefs.variation(f.name).coerceIn(0, (p.variationCount - 1).coerceAtLeast(0))
            p.view.visibility = View.GONE
            addView(p.view, 0, LayoutParams(-1, -1))
        }
    }

    fun enabledFamilies(): List<Family> {
        val off = Prefs.disabledFamilies.split(',').filter { it.isNotBlank() }.toSet()
        return Family.entries.filter { it == Family.PET || it.name !in off }
    }

    /** Show [f] immediately (direction only affects the animation: -1 left, +1 right, 0 fade). */
    fun show(f: Family, direction: Int = 0) {
        val target = page(f)
        if (f == current && target.view.visibility == View.VISIBLE) { overlay.flash(); return }
        val old = pages[current]
        if (old != null && old !== target) {
            old.onHide()
            val ov = old.view
            ov.animate().cancel()
            if (direction == 0) ov.animate().alpha(0f).setDuration(200).withEndAction { ov.visibility = View.GONE; ov.alpha = 1f }.start()
            else ov.animate().translationX(-direction * width.toFloat()).setDuration(240)
                .withEndAction { ov.visibility = View.GONE; ov.translationX = 0f }.start()
        }
        current = f
        Prefs.raw.edit().putString("lastFamily", f.name).apply()
        val v = target.view
        v.animate().cancel()
        v.visibility = View.VISIBLE
        v.translationY = 0f
        if (direction == 0) { v.translationX = 0f; v.alpha = 0f; v.animate().alpha(1f).setDuration(220).start() }
        else { v.alpha = 1f; if (v.translationX == 0f) v.translationX = direction * width.toFloat(); v.animate().translationX(0f).setDuration(240).start() }
        target.onShow()
        overlay.flash()
        refreshChrome()
        listener?.onFamilyChanged(f)
    }

    fun step(delta: Int): Boolean {
        val list = enabledFamilies()
        val i = list.indexOf(current)
        val next = list.getOrNull(i + delta) ?: return false
        show(next, delta)
        return true
    }

    fun stepVariation(delta: Int) {
        val p = currentPage
        if (p.variationCount <= 1) { overlay.flash(); return }
        val v = p.view
        val n = ((p.variation + delta) % p.variationCount + p.variationCount) % p.variationCount
        val dist = height * 0.25f
        v.animate().cancel()
        v.animate().translationY(-delta * dist).alpha(0f).setDuration(140).withEndAction {
            p.variation = n
            Prefs.setVariation(current.name, n)
            v.translationY = delta * dist
            v.animate().translationY(0f).alpha(1f).setDuration(180).start()
        }.start()
        overlay.flash(p.variationLabel(n))
    }

    fun pauseAll() { pages.values.forEach { if (it.view.visibility == View.VISIBLE) it.onHide() } }
    fun resumeCurrent() { currentPage.onShow() }

    // ---- Gesture routing ----------------------------------------------------------------

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        if (currentPage.capturesGestures) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; mode = 0
                velocity?.recycle(); velocity = VelocityTracker.obtain()
                velocity?.addMovement(e)
                listener?.onUserActivity()
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(e)
                if (mode == 0) mode = classify(e)
                if (mode != 0) return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> return false
        }
        return false
    }

    private fun classify(e: MotionEvent): Int {
        val dx = e.x - downX; val dy = e.y - downY
        if (abs(dx) < slop && abs(dy) < slop) return 0
        return when {
            downY < edge && dy > 0 && abs(dy) > abs(dx) -> 3
            abs(dx) > abs(dy) * 1.2f -> 1
            abs(dy) > abs(dx) * 1.2f -> 2
            else -> 0
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        velocity?.addMovement(e)
        val dx = e.x - downX; val dy = e.y - downY
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; mode = 0
                velocity?.recycle(); velocity = VelocityTracker.obtain(); velocity?.addMovement(e)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == 0) mode = classify(e)
                when (mode) {
                    1 -> dragHorizontal(dx)
                    2 -> { currentPage.view.translationY = dy * 0.35f; currentPage.view.alpha = 1f - (abs(dy) / height).coerceAtMost(0.6f) }
                    3 -> listener?.onQuickControlsPull(dy / (height * 0.55f), false)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                velocity?.computeCurrentVelocity(1000)
                val vx = velocity?.xVelocity ?: 0f
                val vy = velocity?.yVelocity ?: 0f
                when (mode) {
                    1 -> releaseHorizontal(dx, vx)
                    2 -> {
                        val v = currentPage.view
                        if (abs(dy) > height * 0.12f || abs(vy) > 1200) {
                            stepVariation(if (dy < 0) 1 else -1)
                        } else v.animate().translationY(0f).alpha(1f).setDuration(160).start()
                    }
                    3 -> listener?.onQuickControlsPull(dy / (height * 0.55f), true)
                }
                mode = 0
                velocity?.recycle(); velocity = null
            }
        }
        return true
    }

    private fun dragHorizontal(dx: Float) {
        val list = enabledFamilies()
        val i = list.indexOf(current)
        val want = list.getOrNull(if (dx < 0) i + 1 else i - 1)
        if (want != neighbour) {
            neighbour?.let { n -> if (n != current) pages[n]?.view?.visibility = View.GONE }
            neighbour = want
            want?.let { n -> page(n).view.apply { visibility = View.VISIBLE; alpha = 1f; translationY = 0f } }
        }
        val w = width.toFloat()
        val eff = if (want == null) dx * 0.25f else dx      // resistance at the ends
        currentPage.view.translationX = eff
        want?.let { n -> page(n).view.translationX = eff + (if (dx < 0) w else -w) }
    }

    private fun releaseHorizontal(dx: Float, vx: Float) {
        val n = neighbour
        neighbour = null
        val commit = n != null && (abs(dx) > width * 0.22f || (abs(vx) > 1000 && vx * dx > 0))
        if (commit && n != null) {
            show(n, if (dx < 0) 1 else -1)
        } else {
            currentPage.view.animate().translationX(0f).setDuration(180).start()
            n?.let { f -> pages[f]?.view?.let { v -> v.animate().translationX(if (dx < 0) width.toFloat() else -width.toFloat())
                .setDuration(180).withEndAction { v.visibility = View.GONE; v.translationX = 0f }.start() } }
        }
    }

    /** Page dots + transient variation label, drawn above all pages. */
    private inner class Overlay(context: Context) : View(context) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private var shownAt = 0L
        private var label: String? = null
        private val fadeMs = 2_200L

        init { isClickable = false; isFocusable = false }

        fun flash(text: String? = null) {
            label = text; shownAt = SystemClock.uptimeMillis(); invalidate()
            postDelayed({ invalidate() }, fadeMs + 50)
            postDelayed({ invalidate() }, fadeMs / 2)
        }

        override fun onDraw(canvas: Canvas) {
            val age = SystemClock.uptimeMillis() - shownAt
            if (age > fadeMs) return
            val a = (1f - (age - fadeMs * 0.6f).coerceAtLeast(0f) / (fadeMs * 0.4f)).coerceIn(0f, 1f)
            val list = enabledFamilies()
            val dr = height * 0.008f
            val gap = dr * 4f
            val startX = width / 2f - (list.size - 1) * gap / 2
            val y = height - height * 0.03f
            list.forEachIndexed { i, f ->
                p.color = Theme.withAlpha(Look.c(if (f == current) ColorRole.TEXT else ColorRole.TEXT_2), (255 * a * (if (f == current) 1f else 0.5f)).toInt())
                canvas.drawCircle(startX + i * gap, y, dr, p)
            }
            val text = label ?: current.label
            p.textAlign = Paint.Align.CENTER; p.typeface = Theme.medium; p.textSize = height * 0.034f
            val tw = p.measureText(text) + height * 0.06f
            val ty = height * 0.07f
            p.color = Theme.withAlpha(Look.c(ColorRole.TILE), (230 * a).toInt())
            canvas.drawRoundRect(width / 2f - tw / 2, ty - height * 0.045f, width / 2f + tw / 2, ty + height * 0.025f, height * 0.035f, height * 0.035f, p)
            p.color = Theme.withAlpha(Look.c(ColorRole.TEXT), (255 * a).toInt())
            canvas.drawText(text, width / 2f, ty, p)
        }
    }

    override fun onViewAdded(child: View?) {
        super.onViewAdded(child)
        if (child !== overlay && child !== gear) { overlay.bringToFront(); gear.bringToFront() }
    }
}

/** Always-visible settings button for the top-right corner: a small gear on a translucent disc. */
class GearButton(context: Context) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        isClickable = true
        isFocusable = true
        contentDescription = "Settings"
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.alpha = 0.6f
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.alpha = 1f
            }
            false
        }
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val r = minOf(width, height) * 0.36f
        p.style = Paint.Style.FILL
        p.color = Theme.withAlpha(Look.c(ColorRole.BACKGROUND), 170)
        c.drawCircle(cx, cy, r * 1.25f, p)
        val color = Look.c(ColorRole.TEXT_2)
        // Teeth.
        p.color = color; p.strokeWidth = r * 0.34f; p.strokeCap = Paint.Cap.ROUND; p.style = Paint.Style.STROKE
        for (i in 0 until 8) {
            val a = Math.toRadians(i * 45.0)
            c.drawLine(cx + (Math.cos(a) * r * 0.55).toFloat(), cy + (Math.sin(a) * r * 0.55).toFloat(),
                cx + (Math.cos(a) * r * 0.82).toFloat(), cy + (Math.sin(a) * r * 0.82).toFloat(), p)
        }
        // Ring + hole.
        p.strokeWidth = r * 0.26f
        c.drawCircle(cx, cy, r * 0.52f, p)
        p.style = Paint.Style.FILL; p.color = Look.c(ColorRole.BACKGROUND)
        c.drawCircle(cx, cy, r * 0.2f, p)
    }
}
