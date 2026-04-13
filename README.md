# RevealSlider

A production-ready Android library for a before/after image comparison slider.
A single draggable divider splits one image into a blurred/styled left side and a sharp right side.

## Features

- **4 effect types**: Gaussian blur, Frosted Glass, Dark Fade, Pixelate
- **GPU-accelerated blur**: `RenderEffect` on API 31+, `RenderScript` fallback on API 24-30, pure-software 3-pass box blur as final fallback
- **Jetpack Compose** wrapper included
- **Zero heavy dependencies** — no Glide, Coil, or Picasso required
- Fully customisable divider, handle, labels, corner radius, and direction
- Async pre-computation — `onDraw` only clips and blits, never computes

---

## Installation

### JitPack

Add JitPack to your root `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

Add the dependency in `app/build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.github.theadityatiwari:reveal-slider:1.0.0")
}
```

---

## XML Usage

```xml
<com.theadityatiwari.revealslider.RevealSliderView
    android:id="@+id/slider"
    android:layout_width="match_parent"
    android:layout_height="240dp"
    app:revealSrc="@drawable/my_photo"
    app:blurType="gaussian"
    app:blurRadius="15"
    app:cornerRadius="12dp"
    app:showLabels="true"
    app:beforeLabel="Before"
    app:afterLabel="After"
    app:initialPosition="0.5"
    app:sliderDirection="before_after" />
```

---

## Programmatic Usage

```kotlin
val slider = RevealSliderView(context)
slider.setBitmap(myBitmap)
slider.setBlurType(BlurType.FROSTED_GLASS)
slider.setBlurRadius(18f)
slider.setCornerRadius(24f)   // pixels
slider.setShowLabels(true)
slider.setDirection(SliderDirection.BEFORE_AFTER)

slider.setOnSliderChangeListener(object : RevealSliderView.OnSliderChangeListener {
    override fun onSliderMoved(position: Float) {
        // position: 0.0 (full left) → 1.0 (full right)
    }
})
```

---

## Glide Integration

```kotlin
Glide.with(context)
    .asBitmap()
    .load(imageUrl)
    .into(object : CustomTarget<Bitmap>() {
        override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
            slider.setBitmap(resource)
        }
        override fun onLoadCleared(placeholder: Drawable?) {}
    })
```

---

## Coil Integration

```kotlin
// Kotlin coroutines
lifecycleScope.launch {
    val bitmap = imageLoader.execute(
        ImageRequest.Builder(context).data(imageUrl).build()
    ).drawable?.toBitmap()
    bitmap?.let { slider.setBitmap(it) }
}

// Or with the extension
imageView.load(imageUrl) // not needed — use ImageLoader directly:
val request = ImageRequest.Builder(context)
    .data(imageUrl)
    .target { drawable -> slider.setBitmap(drawable.toBitmap()) }
    .build()
imageLoader.enqueue(request)
```

---

## Jetpack Compose Usage

```kotlin
RevealSlider(
    bitmap = myBitmap,
    blurType = BlurType.GAUSSIAN,
    blurRadius = 15f,
    dividerColor = Color.White,
    handleSize = 44.dp,
    cornerRadius = 12.dp,
    showLabels = true,
    beforeLabel = "Before",
    afterLabel = "After",
    direction = SliderDirection.BEFORE_AFTER,
    modifier = Modifier.fillMaxWidth().height(240.dp),
    onSliderMoved = { position -> /* 0f..1f */ },
)
```

---

## XML Attributes Reference

| Attribute | Type | Default | Description |
|---|---|---|---|
| `app:revealSrc` | reference | — | Drawable/bitmap source (like ImageView's src) |
| `app:sliderScaleType` | enum | `center_crop` | `center_crop` or `fit_center` |
| `app:blurType` | enum | `gaussian` | `gaussian`, `frosted_glass`, `dark_fade`, `pixelate` |
| `app:blurRadius` | float | `15` | Blur radius, 1–25 (ignored for PIXELATE) |
| `app:frostedAlpha` | integer | `120` | White overlay alpha for FROSTED_GLASS, 0–255 |
| `app:darkFadeAlpha` | integer | `140` | Dark overlay alpha for DARK_FADE, 0–255 |
| `app:pixelSize` | integer | `16` | Pixel block size for PIXELATE |
| `app:dividerWidth` | dimension | `2dp` | Width of the divider line |
| `app:dividerColor` | color | `#FFFFFF` | Colour of the divider line |
| `app:handleIcon` | reference | — | Custom drawable for the handle; omit for default |
| `app:handleSize` | dimension | `44dp` | Diameter of the handle circle |
| `app:handleColor` | color | `#FFFFFF` | Fill colour of the default handle |
| `app:cornerRadius` | dimension | `0dp` | Rounded corners for the entire view |
| `app:sliderDirection` | enum | `before_after` | `before_after` (left=blurred) or `after_before` |
| `app:initialPosition` | float | `0.5` | Starting divider position, 0.0–1.0 |
| `app:showLabels` | boolean | `false` | Show Before/After labels in the corners |
| `app:beforeLabel` | string | `"Before"` | Text for the styled side |
| `app:afterLabel` | string | `"After"` | Text for the sharp side |
| `app:labelTextColor` | color | `#FFFFFF` | Label text colour |
| `app:labelTextSize` | dimension | `14sp` | Label text size |
| `app:labelBackground` | color | `#80000000` | Label background colour (semi-transparent) |

---

## Blur Implementation Details

| API level | Blur path |
|---|---|
| 31+ (Android 12+) | `HardwareRenderer` + `RenderEffect.createBlurEffect` (GPU) |
| 24–30 | `android.renderscript.ScriptIntrinsicBlur` |
| Any (fallback) | 3-pass optimised box blur (O(w·h) per pass) |

Heavy computation runs once on a `HandlerThread`. `onDraw` only does canvas clipping
and bitmap blitting — no computation at draw time.

---

## License

```
MIT License — see LICENSE
```
