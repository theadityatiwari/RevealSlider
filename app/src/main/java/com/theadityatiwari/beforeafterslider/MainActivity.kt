package com.theadityatiwari.beforeafterslider

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.SeekBar
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.theadityatiwari.beforeafterslider.databinding.ActivityMainBinding
import com.theadityatiwari.revealslider.BlurType
import com.theadityatiwari.revealslider.RevealSliderView
import com.theadityatiwari.revealslider.SliderDirection

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var demoBitmap: Bitmap? = null

    // ── Image pickers (must be registered before onCreate) ───────────────────

    private val pickBeforeImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@registerForActivityResult
        val reqW = binding.revealSlider.width.takeIf  { it > 0 } ?: 1080
        val reqH = binding.revealSlider.height.takeIf { it > 0 } ?: 800
        Thread {
            val bmp = decodeSubsampled(uri, reqW * 2, reqH * 2)
            if (bmp != null) runOnUiThread {
                binding.imgBefore.setImageBitmap(bmp)
                binding.imgBefore.visibility      = View.VISIBLE
                binding.placeholderBefore.visibility = View.GONE
                binding.revealSlider.setBeforeBitmap(bmp)
            }
        }.start()
    }

    private val pickAfterImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@registerForActivityResult
        val reqW = binding.revealSlider.width.takeIf  { it > 0 } ?: 1080
        val reqH = binding.revealSlider.height.takeIf { it > 0 } ?: 800
        Thread {
            val bmp = decodeSubsampled(uri, reqW * 2, reqH * 2)
            if (bmp != null) runOnUiThread {
                binding.imgAfter.setImageBitmap(bmp)
                binding.imgAfter.visibility      = View.VISIBLE
                binding.placeholderAfter.visibility = View.GONE
                binding.revealSlider.setAfterBitmap(bmp)
            }
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        // Programmatic bitmap demo — setBitmap() API
        demoBitmap = createDemoBitmap()
        binding.revealSlider.setBitmap(demoBitmap!!)

        setupSliderCallback()
        setupModeToggle()
        setupImagePickers()
        setupBlurTypeChips()
        setupBlurRadiusSeekBar()
        setupPixelSizeSeekBar()
        setupDirectionToggleGroup()
        setupLabelsSwitch()
        setupCornerRadiusSeekBar()
    }

    // ── Slider callback ───────────────────────────────────────────────────────

    private fun setupSliderCallback() {
        binding.revealSlider.setOnSliderChangeListener(object : RevealSliderView.OnSliderChangeListener {
            override fun onSliderMoved(position: Float) {
                binding.tvPosition.text = "Position: ${(position * 100).toInt()}%"
            }
        })
    }

    // ── Mode toggle ───────────────────────────────────────────────────────────

    private fun setupModeToggle() {
        binding.toggleMode.check(R.id.btnModeSingle)
        binding.toggleMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val isDual = checkedId == R.id.btnModeDual
            binding.cardImages.visibility  = if (isDual) View.VISIBLE else View.GONE
            binding.cardEffect.visibility  = if (isDual) View.GONE    else View.VISIBLE
            if (!isDual) {
                // Reset image picker tiles to placeholder state
                binding.imgBefore.visibility = View.GONE
                binding.imgAfter.visibility  = View.GONE
                binding.placeholderBefore.visibility = View.VISIBLE
                binding.placeholderAfter.visibility  = View.VISIBLE
                binding.revealSlider.clearDualBitmaps()
            }
        }
    }

    // ── Image pickers ─────────────────────────────────────────────────────────

    private fun setupImagePickers() {
        binding.cardPickBefore.setOnClickListener { pickBeforeImage.launch("image/*") }
        binding.cardPickAfter.setOnClickListener  { pickAfterImage.launch("image/*")  }
    }

    /**
     * Two-pass sub-sampled decode:
     *  Pass 1 — read only dimensions (zero pixel allocation via inJustDecodeBounds)
     *  Pass 2 — decode at power-of-2 sample size that keeps output >= [reqW]×[reqH],
     *            using RGB_565 (2 bytes/px) since photos have no alpha channel.
     *
     * A 12 MP photo decoded at inSampleSize=4 uses ~2 MB instead of ~36 MB.
     * Must be called on a background thread.
     */
    private fun decodeSubsampled(uri: Uri, reqW: Int, reqH: Int): Bitmap? = try {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        opts.inSampleSize      = calculateSampleSize(opts, reqW, reqH)
        opts.inJustDecodeBounds = false
        opts.inPreferredConfig  = Bitmap.Config.RGB_565  // halves memory vs ARGB_8888
        val raw = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: return null
        applyExifRotation(uri, raw)
    } catch (_: Throwable) { null }   // catches OutOfMemoryError (extends Error, not Exception)

    /**
     * Reads EXIF orientation from [uri] and rotates [bitmap] to upright if needed.
     * Camera photos from Android 7+ often arrive as landscape with an EXIF tag — without
     * this fix they display sideways or upside-down in the slider.
     * Opens a third InputStream (cheap: header-only read) and recycles the original if a
     * rotated copy is created.
     */
    private fun applyExifRotation(uri: Uri, bitmap: Bitmap): Bitmap {
        val degrees = try {
            contentResolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90  -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        } catch (_: Throwable) { 0f }
        if (degrees == 0f) return bitmap
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            .also { rotated -> if (rotated !== bitmap) bitmap.recycle() }
    }

    /**
     * Returns the largest power-of-2 inSampleSize such that the decoded image
     * is still at least [reqW]×[reqH] pixels — enough for a high-quality centerCrop.
     */
    private fun calculateSampleSize(opts: BitmapFactory.Options, reqW: Int, reqH: Int): Int {
        val rawW = opts.outWidth; val rawH = opts.outHeight
        var size = 1
        while ((rawW / (size * 2)) >= reqW && (rawH / (size * 2)) >= reqH) size *= 2
        return size
    }

    // ── Blur Type Chips ───────────────────────────────────────────────────────

    private fun setupBlurTypeChips() {
        val chipToType = mapOf(
            R.id.chipGaussian  to BlurType.GAUSSIAN,
            R.id.chipFrosted   to BlurType.FROSTED_GLASS,
            R.id.chipDarkFade  to BlurType.DARK_FADE,
            R.id.chipPixelate  to BlurType.PIXELATE,
        )
        binding.chipGroupBlurType.setOnCheckedStateChangeListener { _, checkedIds ->
            val id = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val type = chipToType[id] ?: return@setOnCheckedStateChangeListener
            binding.revealSlider.setBlurType(type)
            val isPixelate = type == BlurType.PIXELATE
            binding.groupBlurRadius.visibility = if (isPixelate) View.GONE else View.VISIBLE
            binding.groupPixelSize.visibility  = if (isPixelate) View.VISIBLE else View.GONE
        }
    }

    // ── Blur Radius SeekBar ───────────────────────────────────────────────────

    private fun setupBlurRadiusSeekBar() {
        // SeekBar max=24, value = progress+1 → range 1..25
        binding.seekBlurRadius.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                val radius = (progress + 1).toFloat()
                binding.tvBlurRadiusValue.text = radius.toInt().toString()
                binding.revealSlider.setBlurRadius(radius)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    // ── Pixel Size SeekBar ────────────────────────────────────────────────────

    private fun setupPixelSizeSeekBar() {
        // SeekBar max=46, value = progress+2 → range 2..48
        binding.seekPixelSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                val size = progress + 2
                binding.tvPixelSizeValue.text = size.toString()
                binding.revealSlider.setPixelSize(size)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    // ── Direction Toggle Group ────────────────────────────────────────────────

    private fun setupDirectionToggleGroup() {
        binding.toggleDirection.check(R.id.btnBeforeAfter)
        binding.toggleDirection.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val dir = when (checkedId) {
                R.id.btnBeforeAfter -> SliderDirection.BEFORE_AFTER
                else                -> SliderDirection.AFTER_BEFORE
            }
            binding.revealSlider.setDirection(dir)
        }
    }

    // ── Labels Switch ─────────────────────────────────────────────────────────

    private fun setupLabelsSwitch() {
        binding.switchLabels.setOnCheckedChangeListener { _, isChecked ->
            binding.revealSlider.setShowLabels(isChecked)
        }
    }

    // ── Corner Radius SeekBar ─────────────────────────────────────────────────

    private fun setupCornerRadiusSeekBar() {
        val density = resources.displayMetrics.density
        binding.seekCornerRadius.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                binding.tvCornerRadiusValue.text = "${progress}dp"
                binding.revealSlider.setCornerRadius(progress * density)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    // ── Demo bitmap ───────────────────────────────────────────────────────────

    /**
     * Creates a procedural landscape scene that clearly demonstrates blur effects.
     * Used in lieu of a binary image asset so the project has zero resources.
     */
    private fun createDemoBitmap(): Bitmap {
        val w = 1200; val h = 600
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Sky gradient
        paint.shader = LinearGradient(
            0f, 0f, 0f, h * 0.65f,
            intArrayOf(0xFF0D1B4B.toInt(), 0xFF1565C0.toInt(), 0xFF42A5F5.toInt()),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w.toFloat(), h * 0.65f, paint)
        paint.shader = null

        // Ground gradient
        paint.shader = LinearGradient(
            0f, h * 0.65f, 0f, h.toFloat(),
            intArrayOf(0xFF388E3C.toInt(), 0xFF1B5E20.toInt()),
            null,
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, h * 0.65f, w.toFloat(), h.toFloat(), paint)
        paint.shader = null

        // Sun
        paint.color = 0xFFFFF59D.toInt()
        canvas.drawCircle(w * 0.78f, h * 0.20f, h * 0.11f, paint)
        // Sun glow
        paint.color = Color.argb(40, 255, 245, 157)
        canvas.drawCircle(w * 0.78f, h * 0.20f, h * 0.17f, paint)
        paint.color = Color.argb(20, 255, 245, 157)
        canvas.drawCircle(w * 0.78f, h * 0.20f, h * 0.24f, paint)

        // Mountains — back layer (lighter, farther away)
        paint.color = 0xFF607D8B.toInt()
        canvas.drawPath(Path().apply {
            moveTo(0f, h * 0.65f)
            lineTo(w * 0.08f, h * 0.40f); lineTo(w * 0.18f, h * 0.60f)
            lineTo(w * 0.30f, h * 0.28f); lineTo(w * 0.42f, h * 0.60f)
            lineTo(w * 0.55f, h * 0.35f); lineTo(w * 0.65f, h * 0.58f)
            lineTo(w * 0.76f, h * 0.42f); lineTo(w * 0.86f, h * 0.62f)
            lineTo(w * 0.95f, h * 0.30f); lineTo(w.toFloat(), h * 0.55f)
            lineTo(w.toFloat(), h * 0.65f); close()
        }, paint)

        // Mountains — front layer (darker)
        paint.color = 0xFF455A64.toInt()
        canvas.drawPath(Path().apply {
            moveTo(0f, h * 0.65f)
            lineTo(w * 0.12f, h * 0.34f); lineTo(w * 0.22f, h * 0.55f)
            lineTo(w * 0.36f, h * 0.18f); lineTo(w * 0.50f, h * 0.65f)
            lineTo(w * 0.62f, h * 0.42f); lineTo(w * 0.74f, h * 0.65f)
            lineTo(w * 0.88f, h * 0.25f); lineTo(w.toFloat(), h * 0.60f)
            lineTo(w.toFloat(), h * 0.65f); close()
        }, paint)

        // Snow caps
        paint.color = Color.WHITE
        canvas.drawPath(Path().apply {
            moveTo(w * 0.36f, h * 0.18f)
            lineTo(w * 0.30f, h * 0.33f); lineTo(w * 0.42f, h * 0.33f); close()
        }, paint)
        canvas.drawPath(Path().apply {
            moveTo(w * 0.88f, h * 0.25f)
            lineTo(w * 0.82f, h * 0.40f); lineTo(w * 0.94f, h * 0.40f); close()
        }, paint)

        // Pine trees
        paint.color = 0xFF1B5E20.toInt()
        for (i in 0 until 12) {
            val tx = w * (0.02f + i * 0.085f)
            val ty = h * 0.65f
            val th = h * 0.11f
            // Three-tier pine silhouette
            canvas.drawPath(Path().apply {
                moveTo(tx, ty - th)
                lineTo(tx - th * 0.5f, ty - th * 0.45f)
                lineTo(tx + th * 0.5f, ty - th * 0.45f); close()
            }, paint)
            canvas.drawPath(Path().apply {
                moveTo(tx, ty - th * 0.65f)
                lineTo(tx - th * 0.65f, ty - th * 0.15f)
                lineTo(tx + th * 0.65f, ty - th * 0.15f); close()
            }, paint)
            canvas.drawPath(Path().apply {
                moveTo(tx, ty - th * 0.30f)
                lineTo(tx - th * 0.80f, ty)
                lineTo(tx + th * 0.80f, ty); close()
            }, paint)
        }

        // Horizontal fog band at the horizon
        paint.shader = LinearGradient(
            0f, h * 0.58f, 0f, h * 0.70f,
            intArrayOf(Color.TRANSPARENT, Color.argb(80, 255, 255, 255), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, h * 0.58f, w.toFloat(), h * 0.70f, paint)
        paint.shader = null

        return bmp
    }

    // ─────────────────────────────────────────────────────────────────────────

    override fun onDestroy() {
        super.onDestroy()
        demoBitmap?.recycle()
        demoBitmap = null
    }
}
