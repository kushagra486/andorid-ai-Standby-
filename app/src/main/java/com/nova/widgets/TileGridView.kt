package com.nova.widgets

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.nova.core.DataHub
import com.nova.storage.Prefs
import com.nova.ui.ColorRole
import com.nova.ui.Look
import com.nova.ui.Theme
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/** Persisted saved layouts (one book shared by direct editing and the Settings editor). */
object LayoutStore {
    private var cacheJson: String? = null
    private var cache: LayoutBook? = null

    var book: LayoutBook
        get() {
            val json = Prefs.raw.getString("layouts", "") ?: ""
            if (json != cacheJson || cache == null) { cache = LayoutBook.fromJson(json); cacheJson = json }
            return cache!!
        }
        set(value) { Prefs.raw.edit().putString("layouts", value.toJson()).apply(); Prefs.notifyChanged() }

    fun save(layout: Layout) { book = book.update(layout) }
    fun newId(): Long = System.currentTimeMillis() % 1_000_000_000L
}

/**
 * Renders a [Layout] as rounded widget tiles on a responsive grid and supports direct
 * manipulation (spec v1.1 §14.4/14.5):
 *  - tap a tile → its primary safe action; swipe up/down on a stack → next widget in stack,
 *  - long-press → edit mode: tiles jiggle, "−" removes, "⤢" cycles size, drag reorders,
 *    tap a tile for precise options, top bar adds widgets / changes columns / Done.
 * While editing (or swiping a stack) the view claims the gesture so the parent never navigates.
 */
class TileGridView(context: Context, private val renderer: WidgetRenderer) : View(context) {

    interface Listener {
        fun onWidgetTap(type: WidgetType, tile: Tile)
        fun onLayoutEdited(layout: Layout)
        fun onAddWidget(layout: Layout)
        fun onTileOptions(layout: Layout, tile: Tile)
        fun onLockedLongPress() {}
        fun onEditModeChanged(editing: Boolean) {}
    }

    var listener: Listener? = null
    var editable = true

    var layout: Layout = LayoutBook.defaults().active
        set(value) { field = value; pack = GridPacker.pack(value); invalidate() }
    private var pack = GridPacker.pack(layout)

    var editing = false
        private set

    private val stackIndex = HashMap<Long, Int>()
    private val stackChangedAt = HashMap<Long, Long>()
    private val rects = HashMap<Long, RectF>()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Theme.medium }
    private val tmp = RectF()

    private val addButton = RectF(); private val colsButton = RectF(); private val doneButton = RectF()

    private var dragTile: Tile? = null
    private var dragDX = 0f; private var dragDY = 0f
    private var downX = 0f; private var downY = 0f
    private var downTile: Tile? = null
    private var stackSwipe = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    private var running = false
    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            rotateStacks()
            invalidate()
            postDelayed(this, if (editing || Look.transitioning) 33L else 1_000L - System.currentTimeMillis() % 1_000 + 5)
        }
    }
    private val dataListener = { invalidate() }

    fun resume() { if (!running) { running = true; DataHub.addListener(dataListener); post(ticker) } }
    fun pause() { running = false; removeCallbacks(ticker); DataHub.removeListener(dataListener) }

    fun setEditing(on: Boolean) {
        if (editing == on) return
        editing = on
        dragTile = null
        listener?.onEditModeChanged(on)
        removeCallbacks(ticker); if (running) post(ticker)
        invalidate()
    }

    private fun rotateStacks() {
        if (editing) return
        val now = SystemClock.uptimeMillis()
        for (p in pack.placements) {
            val t = p.tile
            if (t.stack.size > 1 && t.rotateSec > 0) {
                val last = stackChangedAt.getOrPut(t.id) { now }
                if (now - last >= t.rotateSec * 1000L) { stackIndex[t.id] = ((stackIndex[t.id] ?: 0) + 1) % t.stack.size; stackChangedAt[t.id] = now }
            }
        }
    }

    private fun shown(t: Tile): WidgetType = t.stack[(stackIndex[t.id] ?: 0) % t.stack.size]

    // ---- Geometry -----------------------------------------------------------------------
    private fun computeRects() {
        val w = width.toFloat(); val h = height.toFloat()
        val pad = h * 0.05f
        val top = pad + (if (editing) h * 0.1f else 0f)
        val bottom = h - pad * 1.5f
        val gap = h * 0.035f
        val cols = layout.columns
        val cw = (w - pad * 2 - gap * (cols - 1)) / cols
        val ch = (bottom - top - gap) / 2
        rects.clear()
        for (p in pack.placements) {
            val l = pad + p.col * (cw + gap)
            val t = top + p.row * (ch + gap)
            rects[p.tile.id] = RectF(l, t, l + cw * p.colSpan + gap * (p.colSpan - 1), t + ch * p.rowSpan + gap * (p.rowSpan - 1))
        }
    }

    private fun tileAt(x: Float, y: Float): Tile? =
        pack.placements.firstOrNull { rects[it.tile.id]?.contains(x, y) == true }?.tile

    // ---- Drawing ------------------------------------------------------------------------
    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Look.c(ColorRole.BACKGROUND))
        computeRects()
        val h = height.toFloat()
        val radius = h * 0.06f
        val now = SystemClock.uptimeMillis()

        for ((i, p) in pack.placements.withIndex()) {
            val t = p.tile
            if (t == dragTile) continue
            val r = rects[t.id] ?: continue
            canvas.save()
            if (editing) canvas.rotate(sin(now / 90.0 + i * 1.7).toFloat() * 0.9f, r.centerX(), r.centerY())
            drawTile(canvas, t, r, radius)
            canvas.restore()
        }
        dragTile?.let { t ->
            val r0 = rects[t.id] ?: return@let
            tmp.set(r0); tmp.offset(dragDX, dragDY)
            canvas.save(); canvas.scale(1.04f, 1.04f, tmp.centerX(), tmp.centerY())
            drawTile(canvas, t, RectF(tmp), radius)
            canvas.restore()
        }
        if (editing) drawEditBar(canvas)
        if (pack.placements.isEmpty()) {
            text.textSize = h * 0.045f; text.color = Look.c(ColorRole.TEXT_2)
            canvas.drawText(if (editing) "Tap + to add a widget" else "Long-press to add widgets", width / 2f, h / 2, text)
        }
    }

    private fun drawTile(canvas: Canvas, t: Tile, r: RectF, radius: Float) {
        renderer.drawTile(canvas, r, radius)
        canvas.save(); canvas.clipRect(r)
        renderer.draw(canvas, shown(t), r)
        canvas.restore()
        if (t.stack.size > 1) {
            // Stack indicator dots on the right edge.
            val dr = height * 0.006f
            val sy = r.centerY() - (t.stack.size - 1) * dr * 1.75f
            t.stack.indices.forEach { k ->
                fill.color = Theme.withAlpha(Look.c(ColorRole.TEXT), if (k == (stackIndex[t.id] ?: 0) % t.stack.size) 230 else 70)
                canvas.drawCircle(r.right - dr * 3, sy + k * dr * 3.5f, dr, fill)
            }
        }
        if (editing) {
            val b = height * 0.028f
            fill.color = Look.c(ColorRole.WARNING); canvas.drawCircle(r.left + b * 0.6f, r.top + b * 0.6f, b, fill)
            fill.color = Theme.onColor(Look.c(ColorRole.WARNING)); canvas.drawRect(r.left + b * 0.6f - b * 0.5f, r.top + b * 0.6f - b * 0.12f, r.left + b * 0.6f + b * 0.5f, r.top + b * 0.6f + b * 0.12f, fill)
            fill.color = Look.c(ColorRole.ACCENT); canvas.drawCircle(r.right - b * 0.6f, r.bottom - b * 0.6f, b, fill)
            text.textSize = b * 1.2f; text.color = Theme.onColor(Look.c(ColorRole.ACCENT))
            canvas.drawText(t.size.label.first().toString(), r.right - b * 0.6f, r.bottom - b * 0.6f + b * 0.42f, text)
        }
    }

    private fun drawEditBar(canvas: Canvas) {
        val h = height.toFloat(); val w = width.toFloat()
        val bh = h * 0.075f; val y = h * 0.02f
        fun button(r: RectF, label: String, role: ColorRole) {
            fill.color = Look.c(ColorRole.TILE); canvas.drawRoundRect(r, bh / 2, bh / 2, fill)
            text.textSize = bh * 0.45f; text.color = Look.c(role)
            canvas.drawText(label, r.centerX(), r.centerY() + bh * 0.16f, text)
        }
        addButton.set(w * 0.04f, y, w * 0.04f + w * 0.16f, y + bh)
        colsButton.set(w * 0.42f, y, w * 0.58f, y + bh)
        doneButton.set(w * 0.84f, y, w * 0.96f, y + bh)
        button(addButton, "+  Add widget", ColorRole.ACCENT)
        button(colsButton, "${layout.columns} columns", ColorRole.TEXT)
        button(doneButton, "Done", ColorRole.ACCENT)
        if (pack.overflow.isNotEmpty()) {
            text.textSize = h * 0.03f; text.color = Look.c(ColorRole.WARNING)
            canvas.drawText("${pack.overflow.size} tile(s) don't fit — make tiles smaller or add columns", w / 2, h - h * 0.02f, text)
        }
    }

    // ---- Touch --------------------------------------------------------------------------
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (editing) { editTap(e.x, e.y); return true }
            val t = tileAt(e.x, e.y) ?: return false
            playSoundEffect(android.view.SoundEffectConstants.CLICK)
            listener?.onWidgetTap(shown(t), t)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (!editable) return
            if (layout.locked) { listener?.onLockedLongPress(); return }
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            setEditing(true)
            computeRects()
            tileAt(e.x, e.y)?.let { startDrag(it, e) }
        }
    })

    private fun startDrag(t: Tile, e: MotionEvent) {
        dragTile = t; dragDX = 0f; dragDY = 0f; downX = e.x; downY = e.y
        parent?.requestDisallowInterceptTouchEvent(true)
    }

    private fun editTap(x: Float, y: Float) {
        when {
            addButton.contains(x, y) -> { listener?.onAddWidget(layout); return }
            colsButton.contains(x, y) -> { commit(layout.copy(columns = if (layout.columns >= 4) 2 else layout.columns + 1)); return }
            doneButton.contains(x, y) -> { setEditing(false); return }
        }
        val t = tileAt(x, y) ?: return
        val r = rects[t.id] ?: return
        val b = height * 0.06f
        when {
            x < r.left + b && y < r.top + b -> commit(layout.copy(tiles = layout.tiles.filter { it.id != t.id }))
            x > r.right - b && y > r.bottom - b -> {
                val next = TileSize.entries[(t.size.ordinal + 1) % TileSize.entries.size]
                commit(layout.copy(tiles = layout.tiles.map { if (it.id == t.id) it.copy(size = next) else it }))
            }
            else -> listener?.onTileOptions(layout, t)
        }
    }

    private fun commit(l: Layout) {
        layout = l
        listener?.onLayoutEdited(l)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (editing) parent?.requestDisallowInterceptTouchEvent(true)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; stackSwipe = false
                computeRects()
                downTile = tileAt(e.x, e.y)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - downX; val dy = e.y - downY
                val d = dragTile
                if (d != null) {
                    dragDX = dx; dragDY = dy; invalidate(); return true
                }
                if (editing) {
                    val t = downTile
                    if (t != null && (abs(dx) > slop || abs(dy) > slop)) { startDrag(t, e); dragDX = dx; dragDY = dy }
                    return true
                }
                // Gesture precedence: a vertical swipe that starts on a stack belongs to the stack.
                val t = downTile
                if (!stackSwipe && t != null && t.stack.size > 1 && abs(dy) > slop && abs(dy) > abs(dx) * 1.3f) {
                    stackSwipe = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (stackSwipe) return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val d = dragTile
                if (d != null) {
                    val r = rects[d.id]
                    val target = r?.let { tileAt(it.centerX() + dragDX, it.centerY() + dragDY) }
                    dragTile = null
                    if (target != null && target.id != d.id) {
                        val list = layout.tiles.toMutableList()
                        val from = list.indexOfFirst { it.id == d.id }
                        val to = list.indexOfFirst { it.id == target.id }
                        list.removeAt(from); list.add(to, d)
                        commit(layout.copy(tiles = list))
                    }
                    invalidate()
                    return true
                }
                if (stackSwipe) {
                    val t = downTile
                    if (t != null && e.actionMasked == MotionEvent.ACTION_UP) {
                        val dir = if (e.y - downY < 0) 1 else -1
                        stackIndex[t.id] = (((stackIndex[t.id] ?: 0) + dir) % t.stack.size + t.stack.size) % t.stack.size
                        stackChangedAt[t.id] = SystemClock.uptimeMillis()
                        invalidate()
                    }
                    stackSwipe = false
                    return true
                }
            }
        }
        return gestures.onTouchEvent(e)
    }
}
