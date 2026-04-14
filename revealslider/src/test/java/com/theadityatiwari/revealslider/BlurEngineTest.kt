package com.theadityatiwari.revealslider

import android.app.Application
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * JVM unit tests for [BlurEngine] using Robolectric so that [Bitmap] allocations
 * and pixel operations behave like a real device rather than throwing stub errors.
 *
 * All tests run at API 28 ([Config.sdk]) to exercise the software blur path
 * ([blurWithDownscale] → [stackBlur]) and avoid the API 31+ GPU path, which
 * requires a real display surface.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)   // real Skia pipeline — pixel ops are accurate
class BlurEngineTest {

    private val context: Application
        get() = ApplicationProvider.getApplicationContext()

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Creates an ARGB_8888 bitmap filled with a solid [color]. */
    private fun solidBitmap(w: Int, h: Int, color: Int = 0xFF_44_88_CC.toInt()): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.eraseColor(color) }

    // ─────────────────────────────────────────────────────────────────────────
    // scaleBitmap — output dimensions
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `CENTER_CROP produces exact target dimensions`() {
        val result = BlurEngine.scaleBitmap(solidBitmap(800, 600), 400, 400, SliderScaleType.CENTER_CROP)
        assertEquals(400, result.width)
        assertEquals(400, result.height)
    }

    @Test fun `FIT_CENTER produces exact target dimensions`() {
        val result = BlurEngine.scaleBitmap(solidBitmap(800, 600), 400, 400, SliderScaleType.FIT_CENTER)
        assertEquals(400, result.width)
        assertEquals(400, result.height)
    }

    @Test fun `CENTER_CROP with landscape source and portrait target`() {
        val result = BlurEngine.scaleBitmap(solidBitmap(1280, 720), 300, 600, SliderScaleType.CENTER_CROP)
        assertEquals(300, result.width)
        assertEquals(600, result.height)
    }

    @Test fun `FIT_CENTER with landscape source and portrait target`() {
        val result = BlurEngine.scaleBitmap(solidBitmap(1280, 720), 300, 600, SliderScaleType.FIT_CENTER)
        assertEquals(300, result.width)
        assertEquals(600, result.height)
    }

    @Test fun `CENTER_CROP handles square source`() {
        val result = BlurEngine.scaleBitmap(solidBitmap(512, 512), 300, 150, SliderScaleType.CENTER_CROP)
        assertEquals(300, result.width)
        assertEquals(150, result.height)
    }

    @Test fun `scaleBitmap handles 1×1 source without crash`() {
        val result = BlurEngine.scaleBitmap(solidBitmap(1, 1), 200, 200, SliderScaleType.CENTER_CROP)
        assertEquals(200, result.width)
        assertEquals(200, result.height)
    }

    @Test fun `scaleBitmap handles 1×1 source FIT_CENTER`() {
        val result = BlurEngine.scaleBitmap(solidBitmap(1, 1), 200, 200, SliderScaleType.FIT_CENTER)
        assertEquals(200, result.width)
        assertEquals(200, result.height)
    }

    @Test fun `scaleBitmap handles very large source without OOM`() {
        // 8 MP source — the max-size guard must downscale the working surface first
        val result = BlurEngine.scaleBitmap(solidBitmap(3264, 2448), 720, 480, SliderScaleType.CENTER_CROP)
        assertEquals(720, result.width)
        assertEquals(480, result.height)
    }

    @Test fun `scaleBitmap source already smaller than target`() {
        val result = BlurEngine.scaleBitmap(solidBitmap(100, 100), 400, 400, SliderScaleType.CENTER_CROP)
        assertEquals(400, result.width)
        assertEquals(400, result.height)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // applyEffect — output dimensions preserved for every BlurType
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `GAUSSIAN preserves dimensions`() {
        val result = BlurEngine.applyEffect(
            context, solidBitmap(200, 200).copy(Bitmap.Config.ARGB_8888, true),
            BlurType.GAUSSIAN, 15f, 120, 140, 16,
        )
        assertEquals(200, result.width);  assertEquals(200, result.height)
    }

    @Test fun `FROSTED_GLASS preserves dimensions`() {
        val result = BlurEngine.applyEffect(
            context, solidBitmap(200, 100).copy(Bitmap.Config.ARGB_8888, true),
            BlurType.FROSTED_GLASS, 10f, 120, 140, 16,
        )
        assertEquals(200, result.width);  assertEquals(100, result.height)
    }

    @Test fun `DARK_FADE preserves dimensions`() {
        val result = BlurEngine.applyEffect(
            context, solidBitmap(200, 100).copy(Bitmap.Config.ARGB_8888, true),
            BlurType.DARK_FADE, 10f, 120, 140, 16,
        )
        assertEquals(200, result.width);  assertEquals(100, result.height)
    }

    @Test fun `PIXELATE preserves dimensions`() {
        val result = BlurEngine.applyEffect(
            context, solidBitmap(200, 200).copy(Bitmap.Config.ARGB_8888, true),
            BlurType.PIXELATE, 15f, 120, 140, 16,
        )
        assertEquals(200, result.width);  assertEquals(200, result.height)
    }

    @Test fun `all BlurTypes handle 1×1 bitmap without crash`() {
        BlurType.entries.forEach { type ->
            val result = BlurEngine.applyEffect(
                context, solidBitmap(1, 1).copy(Bitmap.Config.ARGB_8888, true),
                type, 15f, 120, 140, 4,
            )
            assertEquals("$type width",  1, result.width)
            assertEquals("$type height", 1, result.height)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // applyEffect — extreme blur radii
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `GAUSSIAN with minimum radius 1 does not crash`() {
        val result = BlurEngine.applyEffect(
            context, solidBitmap(100, 100).copy(Bitmap.Config.ARGB_8888, true),
            BlurType.GAUSSIAN, 1f, 120, 140, 16,
        )
        assertEquals(100, result.width)
    }

    @Test fun `GAUSSIAN with maximum radius 25 does not crash`() {
        val result = BlurEngine.applyEffect(
            context, solidBitmap(100, 100).copy(Bitmap.Config.ARGB_8888, true),
            BlurType.GAUSSIAN, 25f, 120, 140, 16,
        )
        assertEquals(100, result.width)
    }

    @Test fun `PIXELATE with minimum pixel size 2 does not crash`() {
        val result = BlurEngine.applyEffect(
            context, solidBitmap(100, 100).copy(Bitmap.Config.ARGB_8888, true),
            BlurType.PIXELATE, 15f, 120, 140, 2,
        )
        assertEquals(100, result.width)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // applyEffect — visual correctness
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `DARK_FADE darkens a white image`() {
        val result = BlurEngine.applyEffect(
            context, solidBitmap(50, 50, 0xFF_FF_FF_FF.toInt()).copy(Bitmap.Config.ARGB_8888, true),
            BlurType.DARK_FADE, 5f, 120, 200, 16,
        )
        val r = (result.getPixel(25, 25) shr 16) and 0xFF
        assertTrue("Expected darkened pixel (r < 255), got $r", r < 255)
    }

    @Test fun `FROSTED_GLASS brightens a black image`() {
        val result = BlurEngine.applyEffect(
            context, solidBitmap(50, 50, 0xFF_00_00_00.toInt()).copy(Bitmap.Config.ARGB_8888, true),
            BlurType.FROSTED_GLASS, 5f, 200, 140, 16,
        )
        val r = (result.getPixel(25, 25) shr 16) and 0xFF
        assertTrue("Expected brightened pixel (r > 0), got $r", r > 0)
    }

    @Test fun `PIXELATE blocks are uniform within each cell`() {
        // A gradient source: every column is a different shade of grey
        val w = 20; val h = 20; val blockSize = 4
        val src = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (x in 0 until w) for (y in 0 until h) {
            val v = (x * 255 / w) or (x * 255 / w shl 8) or (x * 255 / w shl 16) or (0xFF shl 24)
            src.setPixel(x, y, v)
        }
        val result = BlurEngine.applyEffect(
            context, src.copy(Bitmap.Config.ARGB_8888, true),
            BlurType.PIXELATE, 15f, 120, 140, blockSize,
        )
        // All pixels in the first block row/col must be identical
        val sample = result.getPixel(0, 0)
        for (x in 0 until blockSize) for (y in 0 until blockSize) {
            assertEquals("Pixel at ($x,$y) should match block sample", sample, result.getPixel(x, y))
        }
    }
}
