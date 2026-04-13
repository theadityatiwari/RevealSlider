package com.theadityatiwari.revealslider

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import java.util.concurrent.atomic.AtomicInteger

/**
 * A custom View that displays a single image split by a draggable vertical divider.
 *
 * - Left side: "Before" — blurred / styled region
 * - Right side: "After" — original sharp image
 *
 * Direction can be flipped via [setDirection]. Heavy blur computation is performed
 * once on a background thread; [onDraw] only does Canvas clipping and drawing.
 */
class RevealSliderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    // ── Public callback interface ─────────────────────────────────────────────

    interface OnSliderChangeListener {
        fun onSliderMoved(position: Float)
    }

    // ── Volatile render state (written on BG thread, read on main thread) ─────

    @Volatile private var scaledBitmap: Bitmap? = null
    @Volatile private var styledBitmap: Bitmap? = null

    // ── Configuration ─────────────────────────────────────────────────────────

    private var originalBitmap: Bitmap? = null

    private var blurType: BlurType = BlurType.GAUSSIAN
    private var blurRadius: Float = 15f
    private var frostedAlpha: Int = 120
    private var darkFadeAlpha: Int = 140
    private var pixelSize: Int = 16

    private var dividerWidthPx: Float = 0f
    private var dividerColor: Int = Color.WHITE

    private var handleSizePx: Float = 0f
    private var handleColor: Int = Color.WHITE
    private var handleIconDrawable: Drawable? = null

    private var cornerRadius: Float = 0f

    private var direction: SliderDirection = SliderDirection.BEFORE_AFTER
    private var dividerPosition: Float = 0.5f
    private var scaleType: SliderScaleType = SliderScaleType.CENTER_CROP

    private var showLabels: Boolean = false
    private var beforeLabel: String = "Before"
    private var afterLabel: String = "After"
    private var labelTextColor: Int = Color.WHITE
    private var labelTextSizePx: Float = 0f
    private var labelBackground: Int = Color.argb(128, 0, 0, 0)

    // ── Paints ────────────────────────────────────────────────────────────────

    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val handleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val handleShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    // ── Reusable path/rect objects ────────────────────────────────────────────

    private val cornerPath = Path()
    private val leftArrowPath = Path()
    private val rightArrowPath = Path()
    private val labelRect = RectF()

    // ── Listener ──────────────────────────────────────────────────────────────

    private var sliderListener: OnSliderChangeListener? = null

    // ── Background threading ──────────────────────────────────────────────────

    private val computeThread = HandlerThread("RevealSliderCompute").also { it.start() }
    private val bgHandler = Handler(computeThread.looper)
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Monotonically increasing generation counter. Each time we schedule a new
     * compute, we increment this. The background runnable captures its generation
     * at posting time; if the captured value no longer matches on completion, the
     * result is stale and is discarded.
     */
    private val generation = AtomicInteger(0)

    // ─────────────────────────────────────────────────────────────────────────
    // Initialisation
    // ─────────────────────────────────────────────────────────────────────────

    init {
        val density = resources.displayMetrics.density
        val scaledDensity = resources.displayMetrics.scaledDensity

        // Apply sensible defaults in px before reading XML attributes
        dividerWidthPx = 2f * density
        handleSizePx = 44f * density
        labelTextSizePx = 14f * scaledDensity

        if (attrs != null) {
            val ta = context.obtainStyledAttributes(attrs, R.styleable.RevealSliderView, defStyleAttr, 0)
            try {
                val srcResId = ta.getResourceId(R.styleable.RevealSliderView_revealSrc, -1)
                if (srcResId != -1) {
                    ContextCompat.getDrawable(context, srcResId)?.let { setBitmap(it.toBitmap()) }
                }

                blurType = BlurType.entries[ta.getInt(R.styleable.RevealSliderView_blurType, 0)]
                blurRadius = ta.getFloat(R.styleable.RevealSliderView_blurRadius, 15f).coerceIn(1f, 25f)
                frostedAlpha = ta.getInt(R.styleable.RevealSliderView_frostedAlpha, 120)
                darkFadeAlpha = ta.getInt(R.styleable.RevealSliderView_darkFadeAlpha, 140)
                pixelSize = ta.getInt(R.styleable.RevealSliderView_pixelSize, 16).coerceAtLeast(2)

                dividerWidthPx = ta.getDimension(R.styleable.RevealSliderView_dividerWidth, dividerWidthPx)
                dividerColor = ta.getColor(R.styleable.RevealSliderView_dividerColor, Color.WHITE)

                val iconResId = ta.getResourceId(R.styleable.RevealSliderView_handleIcon, -1)
                if (iconResId != -1) handleIconDrawable = ContextCompat.getDrawable(context, iconResId)
                handleSizePx = ta.getDimension(R.styleable.RevealSliderView_handleSize, handleSizePx)
                handleColor = ta.getColor(R.styleable.RevealSliderView_handleColor, Color.WHITE)

                cornerRadius = ta.getDimension(R.styleable.RevealSliderView_cornerRadius, 0f)
                direction = SliderDirection.entries[ta.getInt(R.styleable.RevealSliderView_sliderDirection, 0)]
                dividerPosition = ta.getFloat(R.styleable.RevealSliderView_initialPosition, 0.5f).coerceIn(0f, 1f)
                scaleType = SliderScaleType.entries[ta.getInt(R.styleable.RevealSliderView_sliderScaleType, 0)]

                showLabels = ta.getBoolean(R.styleable.RevealSliderView_showLabels, false)
                beforeLabel = ta.getString(R.styleable.RevealSliderView_beforeLabel) ?: "Before"
                afterLabel = ta.getString(R.styleable.RevealSliderView_afterLabel) ?: "After"
                labelTextColor = ta.getColor(R.styleable.RevealSliderView_labelTextColor, Color.WHITE)
                labelTextSizePx = ta.getDimension(R.styleable.RevealSliderView_labelTextSize, labelTextSizePx)
                labelBackground = ta.getColor(R.styleable.RevealSliderView_labelBackground, Color.argb(128, 0, 0, 0))
            } finally {
                ta.recycle()
            }
        }

        applyPaints()
    }

    private fun applyPaints() {
        dividerPaint.color = dividerColor
        dividerPaint.strokeWidth = dividerWidthPx

        handleFillPaint.color = handleColor

        handleShadowPaint.color = Color.argb(55, 0, 0, 0)

        arrowPaint.color = if (handleColor == Color.WHITE || handleColor == 0xFFFFFFFF.toInt())
            Color.argb(200, 60, 60, 60) else Color.argb(220, 255, 255, 255)

        labelTextPaint.color = labelTextColor
        labelTextPaint.textSize = labelTextSizePx
        labelTextPaint.typeface = Typeface.DEFAULT_BOLD

        labelBgPaint.color = labelBackground
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /** Set the bitmap to compare. Triggers async scaling + blur computation. */
    fun setBitmap(bitmap: Bitmap) {
        originalBitmap = bitmap
        if (width > 0 && height > 0) scheduleFullRecompute()
        // else: triggered once onSizeChanged delivers non-zero dimensions
    }

    fun setBlurType(type: BlurType) {
        if (blurType == type) return
        blurType = type
        scheduleStyleRecompute()
    }

    fun setBlurRadius(radius: Float) {
        val clamped = radius.coerceIn(1f, 25f)
        if (blurRadius == clamped) return
        blurRadius = clamped
        if (blurType != BlurType.PIXELATE) scheduleStyleRecompute()
    }

    fun setFrostedAlpha(alpha: Int) {
        frostedAlpha = alpha.coerceIn(0, 255)
        if (blurType == BlurType.FROSTED_GLASS) scheduleStyleRecompute()
    }

    fun setDarkFadeAlpha(alpha: Int) {
        darkFadeAlpha = alpha.coerceIn(0, 255)
        if (blurType == BlurType.DARK_FADE) scheduleStyleRecompute()
    }

    fun setPixelSize(size: Int) {
        pixelSize = size.coerceAtLeast(2)
        if (blurType == BlurType.PIXELATE) scheduleStyleRecompute()
    }

    fun setDirection(dir: SliderDirection) {
        direction = dir
        invalidate()
    }

    fun setDividerPosition(position: Float) {
        dividerPosition = position.coerceIn(0f, 1f)
        invalidate()
    }

    fun setShowLabels(show: Boolean) {
        showLabels = show
        invalidate()
    }

    fun setBeforeLabel(label: String) {
        beforeLabel = label
        invalidate()
    }

    fun setAfterLabel(label: String) {
        afterLabel = label
        invalidate()
    }

    fun setLabelTextColor(color: Int) {
        labelTextColor = color
        labelTextPaint.color = color
        invalidate()
    }

    fun setLabelBackground(color: Int) {
        labelBackground = color
        labelBgPaint.color = color
        invalidate()
    }

    fun setLabelTextSize(px: Float) {
        labelTextSizePx = px
        labelTextPaint.textSize = px
        invalidate()
    }

    fun setCornerRadius(radiusPx: Float) {
        cornerRadius = radiusPx.coerceAtLeast(0f)
        rebuildCornerPath()
        invalidate()
    }

    fun setDividerColor(color: Int) {
        dividerColor = color
        dividerPaint.color = color
        invalidate()
    }

    fun setHandleColor(color: Int) {
        handleColor = color
        handleFillPaint.color = color
        arrowPaint.color = if (color == Color.WHITE || color == 0xFFFFFFFF.toInt())
            Color.argb(200, 60, 60, 60) else Color.argb(220, 255, 255, 255)
        invalidate()
    }

    fun setHandleSize(px: Float) {
        handleSizePx = px.coerceAtLeast(16f)
        invalidate()
    }

    fun setScaleType(type: SliderScaleType) {
        if (scaleType == type) return
        scaleType = type
        scheduleFullRecompute()
    }

    fun setOnSliderChangeListener(listener: OnSliderChangeListener?) {
        sliderListener = listener
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Layout
    // ─────────────────────────────────────────────────────────────────────────

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildCornerPath()
        if (w > 0 && h > 0 && originalBitmap != null) scheduleFullRecompute()
    }

    private fun rebuildCornerPath() {
        cornerPath.reset()
        if (cornerRadius > 0f && width > 0 && height > 0) {
            cornerPath.addRoundRect(
                0f, 0f, width.toFloat(), height.toFloat(),
                cornerRadius, cornerRadius, Path.Direction.CW,
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Async bitmap computation
    // ─────────────────────────────────────────────────────────────────────────

    /** Full recompute: re-scale original bitmap then re-apply style. */
    private fun scheduleFullRecompute() {
        val gen = generation.incrementAndGet()
        val src = originalBitmap ?: return
        val w = width; val h = height
        if (w <= 0 || h <= 0) return

        bgHandler.removeCallbacksAndMessages(null)
        bgHandler.post {
            val scaled = BlurEngine.scaleBitmap(src, w, h, scaleType)
            val styled = BlurEngine.applyEffect(
                context, scaled.copy(Bitmap.Config.ARGB_8888, true),
                blurType, blurRadius, frostedAlpha, darkFadeAlpha, pixelSize,
            )
            mainHandler.post {
                if (generation.get() == gen) {
                    scaledBitmap = scaled
                    styledBitmap = styled
                    invalidate()
                }
            }
        }
    }

    /** Style-only recompute: re-apply effect to the already-scaled bitmap. */
    private fun scheduleStyleRecompute() {
        val scaled = scaledBitmap ?: run { scheduleFullRecompute(); return }
        val gen = generation.incrementAndGet()

        bgHandler.removeCallbacksAndMessages(null)
        bgHandler.post {
            val styled = BlurEngine.applyEffect(
                context, scaled.copy(Bitmap.Config.ARGB_8888, true),
                blurType, blurRadius, frostedAlpha, darkFadeAlpha, pixelSize,
            )
            mainHandler.post {
                if (generation.get() == gen) {
                    styledBitmap = styled
                    invalidate()
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Drawing
    // ─────────────────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val sharp = scaledBitmap ?: return
        val styled = styledBitmap ?: return

        // Clip to rounded rectangle (no-op if cornerRadius == 0)
        if (cornerRadius > 0f) canvas.clipPath(cornerPath)

        val divX = width * dividerPosition
        val (leftBmp, rightBmp) = when (direction) {
            SliderDirection.BEFORE_AFTER -> styled to sharp   // left = blurred
            SliderDirection.AFTER_BEFORE -> sharp to styled   // left = sharp
        }

        // Left half
        canvas.save()
        canvas.clipRect(0f, 0f, divX, height.toFloat())
        canvas.drawBitmap(leftBmp, 0f, 0f, bitmapPaint)
        canvas.restore()

        // Right half
        canvas.save()
        canvas.clipRect(divX, 0f, width.toFloat(), height.toFloat())
        canvas.drawBitmap(rightBmp, 0f, 0f, bitmapPaint)
        canvas.restore()

        // Labels drawn before the divider line so the line renders on top
        if (showLabels) drawLabels(canvas)

        // Divider line
        canvas.drawLine(divX, 0f, divX, height.toFloat(), dividerPaint)

        // Draggable handle
        drawHandle(canvas, divX)
    }

    private fun drawHandle(canvas: Canvas, divX: Float) {
        val cy = height / 2f
        val halfSize = handleSizePx / 2f

        if (handleIconDrawable != null) {
            val l = (divX - halfSize).toInt()
            val t = (cy - halfSize).toInt()
            handleIconDrawable!!.setBounds(l, t, l + handleSizePx.toInt(), t + handleSizePx.toInt())
            handleIconDrawable!!.draw(canvas)
            return
        }

        // Subtle offset shadow — no BlurMaskFilter needed (works with hardware acceleration)
        canvas.drawCircle(divX, cy + halfSize * 0.1f, halfSize + 1.5f, handleShadowPaint)
        // Handle fill circle
        canvas.drawCircle(divX, cy, halfSize, handleFillPaint)
        // Arrow triangles
        drawArrows(canvas, divX, cy, halfSize)
    }

    private fun drawArrows(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val aw = radius * 0.28f   // arrow depth
        val ah = radius * 0.44f   // arrow height
        val gap = radius * 0.22f  // gap between arrows and centre

        // Left arrow ◄
        leftArrowPath.reset()
        leftArrowPath.moveTo(cx - gap - aw, cy)
        leftArrowPath.lineTo(cx - gap, cy - ah / 2f)
        leftArrowPath.lineTo(cx - gap, cy + ah / 2f)
        leftArrowPath.close()

        // Right arrow ►
        rightArrowPath.reset()
        rightArrowPath.moveTo(cx + gap + aw, cy)
        rightArrowPath.lineTo(cx + gap, cy - ah / 2f)
        rightArrowPath.lineTo(cx + gap, cy + ah / 2f)
        rightArrowPath.close()

        canvas.drawPath(leftArrowPath, arrowPaint)
        canvas.drawPath(rightArrowPath, arrowPaint)
    }

    private fun drawLabels(canvas: Canvas) {
        val density = resources.displayMetrics.density
        val pad = 8f * density
        val hPad = 8f * density
        val vPad = 4f * density
        val cornerR = 4f * density

        val fm = labelTextPaint.fontMetrics
        val textH = fm.descent - fm.ascent
        val rectH = textH + vPad * 2f

        val (leftLabel, rightLabel) = when (direction) {
            SliderDirection.BEFORE_AFTER -> beforeLabel to afterLabel
            SliderDirection.AFTER_BEFORE -> afterLabel to beforeLabel
        }
        val baseline = pad + vPad - fm.ascent

        // Left label
        val lw = labelTextPaint.measureText(leftLabel)
        labelRect.set(pad, pad, pad + lw + hPad * 2f, pad + rectH)
        canvas.drawRoundRect(labelRect, cornerR, cornerR, labelBgPaint)
        canvas.drawText(leftLabel, labelRect.left + hPad, baseline, labelTextPaint)

        // Right label
        val rw = labelTextPaint.measureText(rightLabel)
        labelRect.set(width - pad - rw - hPad * 2f, pad, width - pad, pad + rectH)
        canvas.drawRoundRect(labelRect, cornerR, cornerR, labelBgPaint)
        canvas.drawText(rightLabel, labelRect.left + hPad, baseline, labelTextPaint)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Touch
    // ─────────────────────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val newPos = (event.x / width.toFloat()).coerceIn(0f, 1f)
                if (newPos != dividerPosition) {
                    dividerPosition = newPos
                    sliderListener?.onSliderMoved(newPos)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        bgHandler.removeCallbacksAndMessages(null)
        computeThread.quitSafely()
    }
}
