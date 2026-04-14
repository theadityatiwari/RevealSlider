package com.theadityatiwari.revealslider

import android.app.Application
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * JVM unit tests for [BlurEngine] using Robolectric so that [Bitmap] allocations
 * behave like a real device rather than throwing stub errors.
 *
 * All tests run at API 28 ([Config.sdk]) to exercise the software blur path
 * ([blurWithDownscale] → [stackBlur]) and avoid the API 31+ GPU path, which
 * requires a real display surface.
 *
 * Note: [org.robolectric.annotation.GraphicsMode.Mode.NATIVE] is intentionally
 * omitted. NATIVE mode loads a Skia native binary at runtime; on headless CI
 * (Ubuntu, no GPU) that load fails and crashes the entire test class. Legacy mode
 * is sufficient for dimension/structure assertions. Visual pixel-accuracy tests
 * (overlay colour, blur intensity) belong in instrumented tests on a real device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
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

    @Test fun `scaleBitmap handles 1×1 source CENTER_CROP`() {
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
    // applyEffect — extreme radii
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
}
