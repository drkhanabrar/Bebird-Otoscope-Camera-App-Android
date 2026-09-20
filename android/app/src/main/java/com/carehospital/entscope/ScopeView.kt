package com.carehospital.entscope

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Live scope viewport.
 *
 * Draws the latest JPEG frame with mirror / rotation / zoom / pan and applies
 * brightness, contrast and saturation through a ColorMatrix, which the GPU
 * handles for free. The same transforms are replayed at native resolution by
 * [processedBitmap] so that snapshots and recordings match what is on screen.
 */
class ScopeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    // ---- frame ----------------------------------------------------------
    private var frame: Bitmap? = null
    var frozen: Boolean = false

    // ---- view transforms -------------------------------------------------
    var zoom: Float = 1f
        set(value) {
            field = value.coerceIn(1f, 6f)
            if (field == 1f) { panX = 0f; panY = 0f }
            invalidate()
        }
    var panX: Float = 0f
    var panY: Float = 0f
    var mirror: Boolean = false
        set(value) { field = value; invalidate() }
    var flipVertical: Boolean = false
        set(value) { field = value; invalidate() }
    var rotation90: Int = 0          // 0, 90, 180, 270
        set(value) { field = ((value % 360) + 360) % 360; invalidate() }

    // ---- image adjustments ------------------------------------------------
    var brightness: Int = 0          // -100 .. 100
        set(value) { field = value; rebuildFilter(); invalidate() }
    var contrast: Int = 0            // -100 .. 100
        set(value) { field = value; rebuildFilter(); invalidate() }
    var saturation: Int = 100        // 0 .. 200
        set(value) { field = value; rebuildFilter(); invalidate() }

    // ---- overlays ---------------------------------------------------------
    var showGrid: Boolean = false
        set(value) { field = value; invalidate() }
    var showCrosshair: Boolean = false
        set(value) { field = value; invalidate() }
    var showTimestamp: Boolean = false
        set(value) { field = value; invalidate() }

    /** Called when the user pinches or drags, so the UI can show the zoom. */
    var onTransformChanged: (() -> Unit)? = null
    /** Called on a double tap - the activity uses it to toggle full screen. */
    var onDoubleTap: (() -> Unit)? = null

    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 210, 238, 242)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    private val stampPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 30f
    }
    private val stampShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 30f
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val matrix = Matrix()
    private val stampFormat = SimpleDateFormat("dd MMM yyyy  HH:mm:ss", Locale.getDefault())

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                zoom *= detector.scaleFactor
                onTransformChanged?.invoke()
                return true
            }
        }
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onScroll(
                e1: MotionEvent?, e2: MotionEvent,
                distanceX: Float, distanceY: Float,
            ): Boolean {
                if (zoom > 1f) {
                    panX -= distanceX
                    panY -= distanceY
                    invalidate()
                    onTransformChanged?.invoke()
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                onDoubleTap?.invoke()
                return true
            }
        }
    )

    init {
        setBackgroundColor(Color.parseColor("#03080A"))
        rebuildFilter()
    }

    // ---------------------------------------------------------------- input
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }

    // ---------------------------------------------------------------- frame
    fun setFrame(bitmap: Bitmap) {
        if (frozen) {
            bitmap.recycle()
            return
        }
        val previous = frame
        frame = bitmap
        previous?.recycle()
        invalidate()
    }

    fun hasFrame(): Boolean = frame != null

    fun clearFrame() {
        frame?.recycle()
        frame = null
        invalidate()
    }

    fun resetView() {
        zoom = 1f
        panX = 0f
        panY = 0f
        brightness = 0
        contrast = 0
        saturation = 100
        mirror = false
        flipVertical = false
        rotation90 = 0
        showGrid = false
        showCrosshair = false
        showTimestamp = false
        onTransformChanged?.invoke()
    }

    // --------------------------------------------------------------- filter
    private fun rebuildFilter() {
        val saturationMatrix = ColorMatrix().apply { setSaturation(saturation / 100f) }
        val scale = 1f + contrast / 100f
        val translate = 127.5f * (1f - scale) + brightness * 2.55f
        val levels = ColorMatrix(
            floatArrayOf(
                scale, 0f, 0f, 0f, translate,
                0f, scale, 0f, 0f, translate,
                0f, 0f, scale, 0f, translate,
                0f, 0f, 0f, 1f, 0f,
            )
        )
        levels.preConcat(saturationMatrix)
        imagePaint.colorFilter = ColorMatrixColorFilter(levels)
    }

    // ----------------------------------------------------------------- draw
    /** Effective size after rotation. */
    private fun effectiveSize(bitmap: Bitmap): Pair<Int, Int> =
        if (rotation90 % 180 == 0) bitmap.width to bitmap.height
        else bitmap.height to bitmap.width

    private fun buildMatrix(
        bitmap: Bitmap, targetW: Float, targetH: Float,
        scaleToFit: Boolean, applyPan: Boolean,
    ): Matrix {
        val (ew, eh) = effectiveSize(bitmap)
        matrix.reset()
        matrix.postTranslate(-bitmap.width / 2f, -bitmap.height / 2f)
        if (mirror) matrix.postScale(-1f, 1f)
        if (flipVertical) matrix.postScale(1f, -1f)
        if (rotation90 != 0) matrix.postRotate(rotation90.toFloat())
        val scale = if (scaleToFit) {
            min(targetW / ew, targetH / eh) * zoom
        } else 1f
        matrix.postScale(scale, scale)
        val dx = if (applyPan) panX else 0f
        val dy = if (applyPan) panY else 0f
        matrix.postTranslate(targetW / 2f + dx, targetH / 2f + dy)
        return matrix
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bitmap = frame ?: return
        if (bitmap.isRecycled) return

        val m = buildMatrix(
            bitmap, width.toFloat(), height.toFloat(),
            scaleToFit = true, applyPan = true,
        )
        canvas.drawBitmap(bitmap, m, imagePaint)
        drawOverlays(canvas, width.toFloat(), height.toFloat(), 1f)
    }

    private fun drawOverlays(canvas: Canvas, w: Float, h: Float, textScale: Float) {
        if (showGrid) {
            for (i in 1..2) {
                canvas.drawLine(w * i / 3f, 0f, w * i / 3f, h, overlayPaint)
                canvas.drawLine(0f, h * i / 3f, w, h * i / 3f, overlayPaint)
            }
        }
        if (showCrosshair) {
            val cx = w / 2f
            val cy = h / 2f
            val arm = min(w, h) * 0.06f
            canvas.drawLine(cx - arm, cy, cx + arm, cy, overlayPaint)
            canvas.drawLine(cx, cy - arm, cx, cy + arm, overlayPaint)
            canvas.drawRect(cx - arm, cy - arm, cx + arm, cy + arm, overlayPaint)
        }
        if (showTimestamp) {
            val text = stampFormat.format(Date())
            val size = 30f * textScale
            stampPaint.textSize = size
            stampShadow.textSize = size
            canvas.drawText(text, 20f, h - 22f, stampShadow)
            canvas.drawText(text, 20f, h - 22f, stampPaint)
        }
    }

    /**
     * The current frame with every transform and overlay applied, at the
     * camera's own resolution. Used for snapshots and for video recording so
     * that saved media matches the live view. Caller owns the bitmap.
     */
    fun processedBitmap(): Bitmap? {
        val bitmap = frame ?: return null
        if (bitmap.isRecycled) return null
        val (ew, eh) = effectiveSize(bitmap)
        if (ew <= 0 || eh <= 0) return null

        val output = Bitmap.createBitmap(ew, eh, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.BLACK)
        val m = buildMatrix(
            bitmap, ew.toFloat(), eh.toFloat(),
            scaleToFit = false, applyPan = false,
        )
        canvas.drawBitmap(bitmap, m, imagePaint)
        drawOverlays(canvas, ew.toFloat(), eh.toFloat(), max(1f, ew / 640f))
        return output
    }
}
