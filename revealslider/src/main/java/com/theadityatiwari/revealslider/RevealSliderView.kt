package com.theadityatiwari.revealslider

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
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
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
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

    // ── Public callback interfaces ────────────────────────────────────────────

    interface OnSliderChangeListener {
        fun onSliderMoved(position: Float)
    }

    /**
     * Fired on the **main thread** when a background computation fails.
     * Integrators should use this to show a fallback UI or log the error.
     * [cause] is typically an [OutOfMemoryError] on constrained devices.
     */
    fun interface OnSliderErrorListener {
        fun onError(cause: Throwable)
    }

    // ── Volatile render state (written on BG thread, read on main thread) ─────

    @Volatile private var scaledBitmap: Bitmap? = null
    @Volatile private var styledBitmap: Bitmap? = null

    // Dual-image mode — both must be non-null for dual rendering to activate
    @Volatile private var scaledBeforeBitmap: Bitmap? = null
    @Volatile private var scaledAfterBitmap: Bitmap? = null

    // ── Configuration ─────────────────────────────────────────────────────────

    private var originalBitmap: Bitmap? = null
    // @Volatile: written on main thread, referenced in guards inside mainHandler.post
    // callbacks that may interleave with clearDualBitmaps() calls.
    @Volatile private var dualBeforeBitmap: Bitmap? = null
    @Volatile private var dualAfterBitmap: Bitmap? = null

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

    // ── Listeners ─────────────────────────────────────────────────────────────

    private var sliderListener: OnSliderChangeListener? = null
    private var errorListener: OnSliderErrorListener? = null
    private var positionAnimator: ValueAnimator? = null

    // ── Background threading ──────────────────────────────────────────────────

    // var, not val: onAttachedToWindow must be able to replace these after
    // onDetachedFromWindow has permanently quit the previous HandlerThread.
    // A HandlerThread cannot be restarted after quit() — a new instance is required.
    private var computeThread = HandlerThread("RevealSliderCompute").also { it.start() }
    private var bgHandler = Handler(computeThread.looper)
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Monotonically increasing generation counter. Each time we schedule a new
     * compute, we increment this. The background runnable captures its generation
     * at posting time; if the captured value no longer matches on completion, the
     * result is stale and is discarded.
     */
    private val generation = AtomicInteger(0)

    /**
     * Tokens used as message tags so [scheduleBeforeRecompute] and
     * [scheduleAfterRecompute] can cancel only their own pending job without
     * interfering with the other side.
     */
    private val BEFORE_TOKEN = Any()
    private val AFTER_TOKEN  = Any()

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

        // Required for keyboard navigation and TalkBack focus
        isFocusable = true
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

    /**
     * Dual-image mode: set the "before" (left) image independently.
     * Both [setBeforeBitmap] and [setAfterBitmap] must be called before
     * dual rendering activates. Dual mode takes priority over single-image mode.
     */
    fun setBeforeBitmap(bitmap: Bitmap) {
        dualBeforeBitmap = bitmap
        if (width > 0 && height > 0) scheduleBeforeRecompute()
    }

    /**
     * Dual-image mode: set the "after" (right) image independently.
     * See [setBeforeBitmap].
     */
    fun setAfterBitmap(bitmap: Bitmap) {
        dualAfterBitmap = bitmap
        if (width > 0 && height > 0) scheduleAfterRecompute()
    }

    /**
     * Exit dual-image mode and return to single-image + effect rendering.
     * If a bitmap was previously set via [setBitmap], it will re-render immediately.
     */
    fun clearDualBitmaps() {
        dualBeforeBitmap = null
        dualAfterBitmap = null
        // Recycle before nulling to release memory immediately
        scaledBeforeBitmap?.recycle()
        scaledAfterBitmap?.recycle()
        scaledBeforeBitmap = null
        scaledAfterBitmap = null
        generation.incrementAndGet()
        bgHandler.removeCallbacksAndMessages(null)
        if (originalBitmap != null && width > 0 && height > 0) scheduleFullRecompute()
        else invalidate()
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
        positionAnimator?.cancel()
        dividerPosition = position.coerceIn(0f, 1f)
        invalidate()
    }

    /**
     * Smoothly animates the divider from its current position to [targetPosition] (0–1).
     * Fires [OnSliderChangeListener.onSliderMoved] on every frame, and announces the
     * final percentage to TalkBack when the animation ends.
     *
     * Calling [animateTo] while an animation is in progress cancels the previous one
     * and starts fresh from wherever the divider currently is.
     *
     * @param targetPosition Destination divider position in the range [0, 1].
     * @param durationMs     Animation duration in milliseconds (default 300 ms).
     */
    fun animateTo(targetPosition: Float, durationMs: Long = 300L) {
        val target = targetPosition.coerceIn(0f, 1f)
        positionAnimator?.cancel()
        positionAnimator = ValueAnimator.ofFloat(dividerPosition, target).apply {
            duration = durationMs
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { anim ->
                val pos = anim.animatedValue as Float
                dividerPosition = pos
                sliderListener?.onSliderMoved(pos)
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    announceForAccessibility(
                        resources.getString(R.string.rsv_accessibility_desc, (target * 100).toInt())
                    )
                }
            })
            start()
        }
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

    fun setOnSliderErrorListener(listener: OnSliderErrorListener?) {
        errorListener = listener
    }

    /** Posts [cause] to the main thread and delivers it to [errorListener]. */
    private fun notifyError(cause: Throwable) {
        mainHandler.post { errorListener?.onError(cause) }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Layout
    // ─────────────────────────────────────────────────────────────────────────

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildCornerPath()
        if (w > 0 && h > 0) {
            if (dualBeforeBitmap != null && dualAfterBitmap != null) scheduleDualRecompute()
            else if (originalBitmap != null) scheduleFullRecompute()
        }
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
        // Snapshot every mutable config field as an immutable local val before
        // crossing the thread boundary. The bg thread must never read shared
        // mutable state directly — fields written on the main thread after this
        // point are not guaranteed visible to the bg thread by the JMM.
        val scaleType     = scaleType
        val blurType      = blurType
        val blurRadius    = blurRadius
        val frostedAlpha  = frostedAlpha
        val darkFadeAlpha = darkFadeAlpha
        val pixelSize     = pixelSize

        bgHandler.removeCallbacksAndMessages(null)
        bgHandler.post {
            try {
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
            } catch (t: Throwable) { notifyError(t) }
        }
    }

    /**
     * Dual-image full recompute: re-scales BOTH sides. Used only by [onSizeChanged]
     * (e.g., screen rotation) where both must be regenerated at the new dimensions.
     * Cancels all pending jobs and uses the generation counter to discard stale results.
     */
    private fun scheduleDualRecompute() {
        val before = dualBeforeBitmap ?: return
        val after  = dualAfterBitmap  ?: return
        val gen = generation.incrementAndGet()
        val w = width; val h = height
        if (w <= 0 || h <= 0) return
        val scaleType = scaleType  // snapshot before crossing thread boundary

        bgHandler.removeCallbacksAndMessages(null)
        bgHandler.post {
            try {
                val newBefore = BlurEngine.scaleBitmap(before, w, h, scaleType)
                val newAfter  = BlurEngine.scaleBitmap(after,  w, h, scaleType)
                mainHandler.post {
                    if (generation.get() == gen) {
                        scaledBeforeBitmap?.recycle()
                        scaledAfterBitmap?.recycle()
                        scaledBeforeBitmap = newBefore
                        scaledAfterBitmap  = newAfter
                        invalidate()
                    } else {
                        newBefore.recycle()
                        newAfter.recycle()
                    }
                }
            } catch (t: Throwable) { notifyError(t) }
        }
    }

    /**
     * Re-scales only the "before" side. Called by [setBeforeBitmap] so a new
     * before image never re-scales the already-computed after side.
     * Uses [BEFORE_TOKEN] so only same-side pending jobs are cancelled.
     */
    private fun scheduleBeforeRecompute() {
        val before = dualBeforeBitmap ?: return
        val w = width; val h = height
        if (w <= 0 || h <= 0) return
        val scaleType = scaleType  // snapshot before crossing thread boundary

        bgHandler.removeCallbacksAndMessages(BEFORE_TOKEN)
        bgHandler.postAtTime({
            try {
                val newBefore = BlurEngine.scaleBitmap(before, w, h, scaleType)
                mainHandler.post {
                    if (dualBeforeBitmap != null) {   // guard: still in dual mode
                        scaledBeforeBitmap?.recycle()
                        scaledBeforeBitmap = newBefore
                        invalidate()
                    } else {
                        newBefore.recycle()           // mode switched away — discard
                    }
                }
            } catch (t: Throwable) { notifyError(t) }
        }, BEFORE_TOKEN, 0)
    }

    /**
     * Re-scales only the "after" side. Called by [setAfterBitmap] so a new
     * after image never re-scales the already-computed before side.
     * Uses [AFTER_TOKEN] so only same-side pending jobs are cancelled.
     */
    private fun scheduleAfterRecompute() {
        val after = dualAfterBitmap ?: return
        val w = width; val h = height
        if (w <= 0 || h <= 0) return
        val scaleType = scaleType  // snapshot before crossing thread boundary

        bgHandler.removeCallbacksAndMessages(AFTER_TOKEN)
        bgHandler.postAtTime({
            try {
                val newAfter = BlurEngine.scaleBitmap(after, w, h, scaleType)
                mainHandler.post {
                    if (dualAfterBitmap != null) {    // guard: still in dual mode
                        scaledAfterBitmap?.recycle()
                        scaledAfterBitmap = newAfter
                        invalidate()
                    } else {
                        newAfter.recycle()            // mode switched away — discard
                    }
                }
            } catch (t: Throwable) { notifyError(t) }
        }, AFTER_TOKEN, 0)
    }

    /** Style-only recompute: re-apply effect to the already-scaled bitmap. */
    private fun scheduleStyleRecompute() {
        val scaled = scaledBitmap ?: run { scheduleFullRecompute(); return }
        val gen = generation.incrementAndGet()
        // Snapshot before crossing thread boundary (see scheduleFullRecompute)
        val blurType      = blurType
        val blurRadius    = blurRadius
        val frostedAlpha  = frostedAlpha
        val darkFadeAlpha = darkFadeAlpha
        val pixelSize     = pixelSize

        bgHandler.removeCallbacksAndMessages(null)
        bgHandler.post {
            try {
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
            } catch (t: Throwable) { notifyError(t) }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Drawing
    // ─────────────────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Clip to rounded rectangle (no-op if cornerRadius == 0)
        if (cornerRadius > 0f) canvas.clipPath(cornerPath)

        val divX = width * dividerPosition

        // Dual-image mode takes priority when both scaled bitmaps are ready
        val (leftBmp, rightBmp) = run {
            val before = scaledBeforeBitmap
            val after  = scaledAfterBitmap
            if (before != null && after != null) {
                when (direction) {
                    SliderDirection.BEFORE_AFTER -> before to after
                    SliderDirection.AFTER_BEFORE -> after  to before
                }
            } else {
                val sharp  = scaledBitmap  ?: return
                val styled = styledBitmap  ?: return
                when (direction) {
                    SliderDirection.BEFORE_AFTER -> styled to sharp   // left = blurred
                    SliderDirection.AFTER_BEFORE -> sharp  to styled  // left = sharp
                }
            }
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

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!computeThread.isAlive) {
            // Previous thread was killed by onDetachedFromWindow. A HandlerThread
            // cannot be restarted after quit(), so create a fresh one.
            computeThread = HandlerThread("RevealSliderCompute").also { it.start() }
            bgHandler = Handler(computeThread.looper)
            // Re-trigger the correct compute path so the view renders on reattach.
            // If dimensions also changed, onSizeChanged will fire after this and
            // schedule its own recompute — the generation counter discards the stale one.
            if (width > 0 && height > 0) {
                when {
                    dualBeforeBitmap != null && dualAfterBitmap != null -> scheduleDualRecompute()
                    originalBitmap != null -> scheduleFullRecompute()
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Accessibility
    // ─────────────────────────────────────────────────────────────────────────

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        // Reporting as SeekBar causes TalkBack to announce "slider" and read
        // the current value as a percentage rather than just the view class name.
        info.className = android.widget.SeekBar::class.java.name
        AccessibilityNodeInfoCompat.wrap(info).setRangeInfo(
            AccessibilityNodeInfoCompat.RangeInfoCompat.obtain(
                AccessibilityNodeInfoCompat.RangeInfoCompat.RANGE_TYPE_INT,
                0f, 100f, dividerPosition * 100f,
            )
        )
    }

    /**
     * Keyboard / D-pad navigation: left/right arrows move the divider by 5% per key press.
     * Fires the same [OnSliderChangeListener] callback as touch, and announces the new
     * position to TalkBack via [announceForAccessibility].
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val step = 0.05f
        val newPos = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT  -> (dividerPosition - step).coerceIn(0f, 1f)
            KeyEvent.KEYCODE_DPAD_RIGHT -> (dividerPosition + step).coerceIn(0f, 1f)
            else -> return super.onKeyDown(keyCode, event)
        }
        if (newPos != dividerPosition) {
            dividerPosition = newPos
            sliderListener?.onSliderMoved(newPos)
            invalidate()
            announceForAccessibility(
                resources.getString(R.string.rsv_accessibility_desc, (newPos * 100).toInt())
            )
        }
        return true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        positionAnimator?.cancel()
        bgHandler.removeCallbacksAndMessages(null)
        computeThread.quitSafely()
    }
}
