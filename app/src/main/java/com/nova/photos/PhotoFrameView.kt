package com.nova.photos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.media.ExifInterface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.DocumentsContract
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import com.nova.core.Clock
import com.nova.integrations.Net
import com.nova.storage.Prefs
import com.nova.ui.ColorRole
import com.nova.ui.Look
import com.nova.ui.Theme
import com.nova.ui.ThemePreset
import java.util.Date
import kotlin.math.max
import kotlin.math.min

enum class PhotoStyle(val label: String) { FILL("Fill screen"), FIT("Whole photo"), CLOCK("Clock focus") }

/**
 * Local photo frame (spec v1.1 §14.6). Photos come only from the folder the user picked through
 * Android's document picker (narrowest access: one persisted tree URI; nothing is scanned or
 * uploaded). Images are decoded down-sampled to screen size with EXIF rotation, one ahead, and
 * failures (missing/corrupt files, low memory) are skipped instead of crashing.
 */
class PhotoFrameView(context: Context) : View(context) {

    var onPickFolder: (() -> Unit)? = null
    var style = PhotoStyle.FILL
        set(value) { field = value; invalidate() }

    private val main = Handler(Looper.getMainLooper())
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val m = Matrix()
    private var files: List<Uri> = emptyList()
    private var order: List<Int> = emptyList()
    private var index = 0
    private var current: Bitmap? = null
    private var previous: Bitmap? = null
    private var changedAt = 0L
    private var paused = false
    private var controlsUntil = 0L
    private var status = ""
    private var loadedTree = ""
    private var running = false
    private var loading = false
    private var nextAt = 0L

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            val now = SystemClock.uptimeMillis()
            if (!paused && files.isNotEmpty() && now >= nextAt && !loading) show(index + 1)
            invalidate()
            val fading = now - changedAt < FADE_MS
            postDelayed(this, if (fading || now < controlsUntil || Look.transitioning) 33L else 1_000L)
        }
    }

    fun resume() {
        if (running) return
        running = true
        if (Prefs.photoTreeUri != loadedTree) scan()
        nextAt = SystemClock.uptimeMillis() + Prefs.photoIntervalSec * 1000L
        post(ticker)
    }

    fun pause() { running = false; removeCallbacks(ticker) }

    fun rescan() { loadedTree = ""; scan() }

    private fun scan() {
        val tree = Prefs.photoTreeUri
        loadedTree = tree
        if (tree.isBlank()) { files = emptyList(); status = "Choose a photo folder"; invalidate(); return }
        status = "Loading photos…"; invalidate()
        Net.bg.execute {
            val found = ArrayList<Uri>()
            val result = runCatching {
                val treeUri = Uri.parse(tree)
                val rootId = DocumentsContract.getTreeDocumentId(treeUri)
                listImages(treeUri, rootId, found, depth = 0)
            }
            main.post {
                files = found
                order = if (Prefs.photoShuffle) found.indices.shuffled() else found.indices.toList()
                status = when {
                    result.isFailure -> "Can't open the folder — choose it again in Settings"
                    found.isEmpty() -> "No photos in this folder"
                    else -> ""
                }
                if (found.isNotEmpty()) show(0)
                invalidate()
            }
        }
    }

    private fun listImages(tree: Uri, docId: String, out: MutableList<Uri>, depth: Int) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        context.contentResolver.query(children, arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_MIME_TYPE,
        ), null, null, null)?.use { c ->
            while (c.moveToNext() && out.size < 2_000) {
                val id = c.getString(0); val mime = c.getString(1) ?: ""
                when {
                    mime.startsWith("image/") -> out += DocumentsContract.buildDocumentUriUsingTree(tree, id)
                    mime == DocumentsContract.Document.MIME_TYPE_DIR && depth < 2 -> listImages(tree, id, out, depth + 1)
                }
            }
        }
    }

    private fun show(i: Int, attempts: Int = 0) {
        if (files.isEmpty() || attempts > 5) return
        val n = files.size
        index = ((i % n) + n) % n
        val uri = files[order.getOrElse(index) { index }]
        loading = true
        val w = width.coerceAtLeast(1280); val h = height.coerceAtLeast(720)
        Net.bg.execute {
            val bmp = runCatching { decode(uri, w, h) }.getOrNull()
            main.post {
                loading = false
                if (bmp == null) { show(index + 1, attempts + 1); return@post }   // skip missing/corrupt files
                previous?.takeIf { it !== current }?.recycle()
                previous = current
                current = bmp
                changedAt = SystemClock.uptimeMillis()
                nextAt = changedAt + Prefs.photoIntervalSec * 1000L
                Look.setAmbient(averageColor(bmp))
                if (Look.preset == ThemePreset.PHOTO_AMBIENT) Prefs.notifyChanged()
                invalidate()
            }
        }
    }

    private fun decode(uri: Uri, reqW: Int, reqH: Int): Bitmap? {
        val cr = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= reqW && bounds.outHeight / (sample * 2) >= reqH) sample *= 2
        val rotation = runCatching {
            cr.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        }.getOrNull()
        var bmp: Bitmap? = null
        var s = sample
        while (bmp == null && s <= 32) {
            try {
                bmp = cr.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s; inPreferredConfig = Bitmap.Config.RGB_565 })
                }
                if (bmp == null) return null
            } catch (oom: OutOfMemoryError) { s *= 2 }   // low memory: try smaller
        }
        val b = bmp ?: return null
        val deg = when (rotation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f; ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f; else -> 0f
        }
        if (deg == 0f) return b
        val rotated = Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(deg) }, true)
        if (rotated !== b) b.recycle()
        return rotated
    }

    private fun averageColor(b: Bitmap): Int {
        val s = Bitmap.createScaledBitmap(b, 8, 8, true)
        var r = 0L; var g = 0L; var bl = 0L
        for (x in 0 until 8) for (y in 0 until 8) { val c = s.getPixel(x, y); r += Color.red(c); g += Color.green(c); bl += Color.blue(c) }
        if (s !== b) s.recycle()
        return Color.rgb((r / 64).toInt(), (g / 64).toInt(), (bl / 64).toInt())
    }

    // ---- Drawing ------------------------------------------------------------------------
    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Look.c(ColorRole.BACKGROUND))
        val now = SystemClock.uptimeMillis()
        val t = ((now - changedAt) / FADE_MS.toFloat()).coerceIn(0f, 1f)
        val dimNight = 1f - Look.night * 0.65f
        val styleDim = if (style == PhotoStyle.CLOCK) 0.45f else 1f
        previous?.let { if (t < 1f && !it.isRecycled) drawPhoto(canvas, it, ((1 - t) * 255 * dimNight * styleDim).toInt()) }
        current?.let { if (!it.isRecycled) drawPhoto(canvas, it, (t * 255 * dimNight * styleDim).toInt()) }

        if (current == null) {
            p.color = Look.c(ColorRole.TEXT_2); p.textAlign = Paint.Align.CENTER; p.textSize = height * 0.05f; p.typeface = Theme.regular
            canvas.drawText(status.ifEmpty { "Choose a photo folder" }, width / 2f, height / 2f, p)
            if (Prefs.photoTreeUri.isBlank()) {
                p.color = Look.c(ColorRole.ACCENT); p.textSize = height * 0.04f
                canvas.drawText("Tap to choose a folder", width / 2f, height / 2f + height * 0.09f, p)
            }
        }
        if (Prefs.photoClockOverlay || style == PhotoStyle.CLOCK) drawClock(canvas)
        if (now < controlsUntil) drawControls(canvas)
    }

    private fun drawPhoto(canvas: Canvas, b: Bitmap, alpha: Int) {
        val vw = width.toFloat(); val vh = height.toFloat()
        val scale = if (style == PhotoStyle.FIT) min(vw / b.width, vh / b.height) else max(vw / b.width, vh / b.height)
        m.reset(); m.postScale(scale, scale)
        m.postTranslate((vw - b.width * scale) / 2, (vh - b.height * scale) / 2)
        p.alpha = alpha.coerceIn(0, 255)
        canvas.drawBitmap(b, m, p)
        p.alpha = 255
    }

    private fun drawClock(canvas: Canvas) {
        val d = Date()
        val big = style == PhotoStyle.CLOCK
        val h = height.toFloat(); val w = width.toFloat()
        val size = h * (if (big) 0.32f else 0.12f)
        val corner = if (big) -1 else Prefs.photoOverlayCorner
        val alpha = (Prefs.photoOverlayOpacity * 2.55f).toInt()
        p.typeface = Theme.light; p.textSize = size
        p.setShadowLayer(h * 0.01f, 0f, 0f, Color.argb(160, 0, 0, 0))
        val (x, y, align) = when (corner) {
            0 -> Triple(w * 0.04f, h * 0.06f + size, Paint.Align.LEFT)
            1 -> Triple(w * 0.96f, h * 0.06f + size, Paint.Align.RIGHT)
            2 -> Triple(w * 0.04f, h * 0.84f, Paint.Align.LEFT)
            3 -> Triple(w * 0.96f, h * 0.84f, Paint.Align.RIGHT)
            else -> Triple(w / 2, h * 0.55f, Paint.Align.CENTER)
        }
        p.textAlign = align
        p.color = Theme.withAlpha(Look.c(Color.WHITE), alpha)
        canvas.drawText(Clock.time(context, d), x, y, p)
        p.textSize = size * 0.28f; p.typeface = Theme.regular
        p.color = Theme.withAlpha(Look.c(Color.WHITE), (alpha * 0.85f).toInt())
        canvas.drawText(Clock.date(d), x, y + size * 0.42f, p)
        p.clearShadowLayer()
    }

    private fun drawControls(canvas: Canvas) {
        val h = height.toFloat(); val w = width.toFloat()
        p.color = Color.argb(140, 0, 0, 0)
        canvas.drawRoundRect(RectF(w / 2 - h * 0.3f, h * 0.82f, w / 2 + h * 0.3f, h * 0.95f), h * 0.065f, h * 0.065f, p)
        p.color = Color.WHITE; p.textAlign = Paint.Align.CENTER; p.textSize = h * 0.06f; p.typeface = Theme.medium
        canvas.drawText("‹      ${if (paused) "▶" else "❚❚"}      ›", w / 2, h * 0.905f, p)
        p.textSize = h * 0.03f; p.typeface = Theme.regular
        if (files.isNotEmpty()) canvas.drawText("${index + 1} / ${files.size}", w / 2, h * 0.78f, p)
    }

    // ---- Touch: left third = previous, right third = next, middle = pause/resume ----------
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (files.isEmpty()) { onPickFolder?.invoke(); return true }
            controlsUntil = SystemClock.uptimeMillis() + 3_000
            when {
                e.x < width / 3f -> show(index - 1)
                e.x > width * 2 / 3f -> show(index + 1)
                else -> { paused = !paused; nextAt = SystemClock.uptimeMillis() + Prefs.photoIntervalSec * 1000L }
            }
            removeCallbacks(ticker); if (running) post(ticker)
            invalidate()
            return true
        }
    })

    override fun onTouchEvent(event: MotionEvent) = gestures.onTouchEvent(event)

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        pause()
        previous?.recycle(); previous = null
    }

    private companion object { const val FADE_MS = 900L }
}
