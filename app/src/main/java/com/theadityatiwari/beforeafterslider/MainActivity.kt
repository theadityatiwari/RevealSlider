package com.theadityatiwari.beforeafterslider

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import androidx.activity.enableEdgeToEdge
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
        setupBlurTypeSpinner()
        setupBlurRadiusSeekBar()
        setupPixelSizeSeekBar()
        setupDirectionRadioGroup()
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

    // ── Blur Type Spinner ─────────────────────────────────────────────────────

    private fun setupBlurTypeSpinner() {
        val labels = BlurType.entries.map { it.name.replace('_', ' ') }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerBlurType.adapter = adapter

        binding.spinnerBlurType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, pos: Int, id: Long) {
                val type = BlurType.entries[pos]
                binding.revealSlider.setBlurType(type)
                val isPixelate = type == BlurType.PIXELATE
                binding.groupBlurRadius.visibility = if (isPixelate) View.GONE else View.VISIBLE
                binding.groupPixelSize.visibility = if (isPixelate) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
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

    // ── Direction RadioGroup ──────────────────────────────────────────────────

    private fun setupDirectionRadioGroup() {
        binding.rgDirection.setOnCheckedChangeListener { _, checkedId ->
            val dir = when (checkedId) {
                R.id.rbBeforeAfter -> SliderDirection.BEFORE_AFTER
                else -> SliderDirection.AFTER_BEFORE
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
