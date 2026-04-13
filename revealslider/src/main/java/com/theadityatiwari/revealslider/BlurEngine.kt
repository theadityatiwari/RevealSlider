package com.theadityatiwari.revealslider

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * Handles all bitmap scaling and effect-application logic.
 * Called on a background thread; all methods are stateless and thread-safe.
 */
internal object BlurEngine {

    // ── Scaling ──────────────────────────────────────────────────────────────

    fun scaleBitmap(src: Bitmap, targetW: Int, targetH: Int, scaleType: SliderScaleType): Bitmap {
        val srcW = src.width.toFloat()
        val srcH = src.height.toFloat()

        return when (scaleType) {
            SliderScaleType.CENTER_CROP -> {
                val scale = maxOf(targetW / srcW, targetH / srcH)
                val scaledW = (srcW * scale).toInt().coerceAtLeast(1)
                val scaledH = (srcH * scale).toInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(src, scaledW, scaledH, true)
                val offX = ((scaledW - targetW) / 2).coerceAtLeast(0)
                val offY = ((scaledH - targetH) / 2).coerceAtLeast(0)
                val safeW = (targetW).coerceAtMost(scaledW - offX)
                val safeH = (targetH).coerceAtMost(scaledH - offY)
                val cropped = Bitmap.createBitmap(scaled, offX, offY, safeW, safeH)
                if (cropped !== scaled) scaled.recycle()
                // If crop is already target size, return; otherwise pad to exact target size
                if (cropped.width == targetW && cropped.height == targetH) cropped
                else padToSize(cropped, targetW, targetH)
            }

            SliderScaleType.FIT_CENTER -> {
                val scale = minOf(targetW / srcW, targetH / srcH)
                val scaledW = (srcW * scale).toInt().coerceAtLeast(1)
                val scaledH = (srcH * scale).toInt().coerceAtLeast(1)
                val result = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(result)
                val left = (targetW - scaledW) / 2f
                val top = (targetH - scaledH) / 2f
                val scaled = Bitmap.createScaledBitmap(src, scaledW, scaledH, true)
                canvas.drawBitmap(scaled, left, top, null)
                scaled.recycle()
                result
            }
        }
    }

    private fun padToSize(src: Bitmap, w: Int, h: Int): Bitmap {
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(result).drawBitmap(src, ((w - src.width) / 2f), ((h - src.height) / 2f), null)
        src.recycle()
        return result
    }

    // ── Effect dispatch ───────────────────────────────────────────────────────

    fun applyEffect(
        context: Context,
        src: Bitmap,
        blurType: BlurType,
        blurRadius: Float,
        frostedAlpha: Int,
        darkFadeAlpha: Int,
        pixelSize: Int,
    ): Bitmap = when (blurType) {
        BlurType.GAUSSIAN -> applyGaussian(context, src, blurRadius)
        BlurType.FROSTED_GLASS -> {
            val blurred = applyGaussian(context, src, blurRadius)
            applyOverlay(blurred, Color.argb(frostedAlpha, 255, 255, 255))
        }
        BlurType.DARK_FADE -> {
            val blurred = applyGaussian(context, src, blurRadius)
            applyOverlay(blurred, Color.argb(darkFadeAlpha, 0, 0, 0))
        }
        BlurType.PIXELATE -> pixelate(src, pixelSize)
    }

    // ── Gaussian blur ─────────────────────────────────────────────────────────

    private fun applyGaussian(context: Context, src: Bitmap, radius: Float): Bitmap {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            blurWithRenderEffect(src, radius)?.let { return it }
        }
        return blurWithRenderScript(context, src, radius)
            ?: stackBlur(src, radius.toInt().coerceAtLeast(1))
    }

    // API 31+ — HardwareRenderer + RenderEffect (GPU path)
    @RequiresApi(Build.VERSION_CODES.S)
    private fun blurWithRenderEffect(src: Bitmap, radius: Float): Bitmap? = try {
        val imageReader = android.media.ImageReader.newInstance(
            src.width, src.height,
            android.graphics.PixelFormat.RGBA_8888, 1,
            android.hardware.HardwareBuffer.USAGE_CPU_READ_RARELY or
                    android.hardware.HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or
                    android.hardware.HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE,
        )

        val renderer = android.graphics.HardwareRenderer()
        renderer.setSurface(imageReader.surface)
        renderer.isOpaque = false

        val node = android.graphics.RenderNode("RevealBlur").apply {
            setPosition(0, 0, src.width, src.height)
            setRenderEffect(
                android.graphics.RenderEffect.createBlurEffect(radius, radius, android.graphics.Shader.TileMode.CLAMP),
            )
        }
        renderer.setContentRoot(node)
        node.beginRecording().also { it.drawBitmap(src, 0f, 0f, null) }
        node.endRecording()

        renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()

        val image = imageReader.acquireLatestImage()
            ?: run { imageReader.close(); renderer.destroy(); return null }

        val plane = image.planes[0]
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val paddedW = rowStride / pixelStride
        val full = Bitmap.createBitmap(paddedW, src.height, Bitmap.Config.ARGB_8888)
        full.copyPixelsFromBuffer(plane.buffer)

        val result = if (paddedW != src.width) {
            Bitmap.createBitmap(full, 0, 0, src.width, src.height).also { full.recycle() }
        } else full

        image.close(); imageReader.close(); renderer.destroy()
        result
    } catch (_: Exception) {
        null
    }

    // API 24-30 — RenderScript (deprecated API 31 but functional; @Suppress silences the warning)
    @Suppress("DEPRECATION")
    private fun blurWithRenderScript(context: Context, src: Bitmap, radius: Float): Bitmap? = try {
        val rs = android.renderscript.RenderScript.create(context)
        val output = src.copy(Bitmap.Config.ARGB_8888, true)
        val inputAlloc = android.renderscript.Allocation.createFromBitmap(rs, src)
        val outputAlloc = android.renderscript.Allocation.createFromBitmap(rs, output)
        val script = android.renderscript.ScriptIntrinsicBlur.create(
            rs, android.renderscript.Element.U8_4(rs),
        )
        script.setRadius(radius.coerceIn(1f, 25f))
        script.setInput(inputAlloc)
        script.forEach(outputAlloc)
        outputAlloc.copyTo(output)
        inputAlloc.destroy(); outputAlloc.destroy(); script.destroy(); rs.destroy()
        output
    } catch (_: Exception) {
        null
    }

    // Software fallback — 3-pass optimised box blur (O(w·h) per pass, approximates Gaussian)
    private fun stackBlur(src: Bitmap, radius: Int): Bitmap {
        val r = radius.coerceIn(1, 25)
        val w = src.width; val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        var buf = pixels
        repeat(3) {
            buf = boxBlurH(buf, w, h, r)
            buf = boxBlurV(buf, w, h, r)
        }
        return Bitmap.createBitmap(buf, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun boxBlurH(src: IntArray, w: Int, h: Int, r: Int): IntArray {
        val dst = IntArray(w * h)
        val diam = 2 * r + 1
        for (y in 0 until h) {
            val row = y * w
            var sumA = 0; var sumR = 0; var sumG = 0; var sumB = 0
            for (i in -r..r) {
                val c = src[row + i.coerceIn(0, w - 1)]
                sumA += (c ushr 24) and 0xFF
                sumR += (c ushr 16) and 0xFF
                sumG += (c ushr 8) and 0xFF
                sumB += c and 0xFF
            }
            for (x in 0 until w) {
                dst[row + x] = ((sumA / diam).coerceIn(0, 255) shl 24) or
                        ((sumR / diam).coerceIn(0, 255) shl 16) or
                        ((sumG / diam).coerceIn(0, 255) shl 8) or
                        (sumB / diam).coerceIn(0, 255)
                val add = src[row + (x + r + 1).coerceIn(0, w - 1)]
                val rem = src[row + (x - r).coerceIn(0, w - 1)]
                sumA += ((add ushr 24) and 0xFF) - ((rem ushr 24) and 0xFF)
                sumR += ((add ushr 16) and 0xFF) - ((rem ushr 16) and 0xFF)
                sumG += ((add ushr 8) and 0xFF) - ((rem ushr 8) and 0xFF)
                sumB += (add and 0xFF) - (rem and 0xFF)
            }
        }
        return dst
    }

    private fun boxBlurV(src: IntArray, w: Int, h: Int, r: Int): IntArray {
        val dst = IntArray(w * h)
        val diam = 2 * r + 1
        for (x in 0 until w) {
            var sumA = 0; var sumR = 0; var sumG = 0; var sumB = 0
            for (i in -r..r) {
                val c = src[i.coerceIn(0, h - 1) * w + x]
                sumA += (c ushr 24) and 0xFF
                sumR += (c ushr 16) and 0xFF
                sumG += (c ushr 8) and 0xFF
                sumB += c and 0xFF
            }
            for (y in 0 until h) {
                dst[y * w + x] = ((sumA / diam).coerceIn(0, 255) shl 24) or
                        ((sumR / diam).coerceIn(0, 255) shl 16) or
                        ((sumG / diam).coerceIn(0, 255) shl 8) or
                        (sumB / diam).coerceIn(0, 255)
                val add = src[(y + r + 1).coerceIn(0, h - 1) * w + x]
                val rem = src[(y - r).coerceIn(0, h - 1) * w + x]
                sumA += ((add ushr 24) and 0xFF) - ((rem ushr 24) and 0xFF)
                sumR += ((add ushr 16) and 0xFF) - ((rem ushr 16) and 0xFF)
                sumG += ((add ushr 8) and 0xFF) - ((rem ushr 8) and 0xFF)
                sumB += (add and 0xFF) - (rem and 0xFF)
            }
        }
        return dst
    }

    // ── Pixelate ──────────────────────────────────────────────────────────────

    private fun pixelate(src: Bitmap, blockSize: Int): Bitmap {
        val bs = blockSize.coerceAtLeast(2)
        val w = src.width; val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        var by = 0
        while (by < h) {
            var bx = 0
            while (bx < w) {
                val sampleX = (bx + bs / 2).coerceAtMost(w - 1)
                val sampleY = (by + bs / 2).coerceAtMost(h - 1)
                val color = pixels[sampleY * w + sampleX]
                for (py in by until minOf(by + bs, h)) {
                    for (px in bx until minOf(bx + bs, w)) {
                        pixels[py * w + px] = color
                    }
                }
                bx += bs
            }
            by += bs
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    // ── Overlay ───────────────────────────────────────────────────────────────

    /** Draws a solid colour over [src] in-place and returns [src]. */
    private fun applyOverlay(src: Bitmap, color: Int): Bitmap {
        Canvas(src).drawColor(color)
        return src
    }
}
