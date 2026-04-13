package com.theadityatiwari.revealslider

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Jetpack Compose wrapper for [RevealSliderView].
 *
 * Example:
 * ```kotlin
 * RevealSlider(
 *     bitmap = myBitmap,
 *     blurType = BlurType.GAUSSIAN,
 *     blurRadius = 15f,
 *     showLabels = true,
 *     modifier = Modifier.fillMaxWidth().height(240.dp),
 * )
 * ```
 */
@Composable
fun RevealSlider(
    bitmap: Bitmap,
    modifier: Modifier = Modifier,
    blurType: BlurType = BlurType.GAUSSIAN,
    blurRadius: Float = 15f,
    frostedAlpha: Int = 120,
    darkFadeAlpha: Int = 140,
    pixelSize: Int = 16,
    dividerColor: Color = Color.White,
    dividerWidth: Dp = 2.dp,
    handleSize: Dp = 44.dp,
    handleColor: Color = Color.White,
    cornerRadius: Dp = 0.dp,
    showLabels: Boolean = false,
    beforeLabel: String = "Before",
    afterLabel: String = "After",
    labelTextColor: Color = Color.White,
    labelTextSize: TextUnit = 14.sp,
    labelBackground: Color = Color.Black.copy(alpha = 0.5f),
    direction: SliderDirection = SliderDirection.BEFORE_AFTER,
    initialPosition: Float = 0.5f,
    scaleType: SliderScaleType = SliderScaleType.CENTER_CROP,
    onSliderMoved: ((Float) -> Unit)? = null,
) {
    val density = LocalDensity.current
    val handleSizePx = with(density) { handleSize.toPx() }
    val cornerRadiusPx = with(density) { cornerRadius.toPx() }
    val labelTextSizePx = with(density) { labelTextSize.toPx() }
    val dividerWidthPx = with(density) { dividerWidth.toPx() }

    AndroidView(
        factory = { context ->
            RevealSliderView(context).apply {
                setBlurType(blurType)
                setBlurRadius(blurRadius)
                setFrostedAlpha(frostedAlpha)
                setDarkFadeAlpha(darkFadeAlpha)
                setPixelSize(pixelSize)
                setDividerColor(dividerColor.toArgb())
                setHandleColor(handleColor.toArgb())
                setHandleSize(handleSizePx)
                setCornerRadius(cornerRadiusPx)
                setShowLabels(showLabels)
                setBeforeLabel(beforeLabel)
                setAfterLabel(afterLabel)
                setLabelTextColor(labelTextColor.toArgb())
                setLabelTextSize(labelTextSizePx)
                setLabelBackground(labelBackground.toArgb())
                setDirection(direction)
                setDividerPosition(initialPosition)
                setScaleType(scaleType)
                onSliderMoved?.let { cb ->
                    setOnSliderChangeListener(object : RevealSliderView.OnSliderChangeListener {
                        override fun onSliderMoved(position: Float) = cb(position)
                    })
                }
                // Set bitmap last so that all config is ready before the first compute
                setBitmap(bitmap)
            }
        },
        update = { view ->
            view.setBlurType(blurType)
            view.setBlurRadius(blurRadius)
            view.setFrostedAlpha(frostedAlpha)
            view.setDarkFadeAlpha(darkFadeAlpha)
            view.setPixelSize(pixelSize)
            view.setDividerColor(dividerColor.toArgb())
            view.setHandleColor(handleColor.toArgb())
            view.setHandleSize(handleSizePx)
            view.setCornerRadius(cornerRadiusPx)
            view.setShowLabels(showLabels)
            view.setBeforeLabel(beforeLabel)
            view.setAfterLabel(afterLabel)
            view.setLabelTextColor(labelTextColor.toArgb())
            view.setLabelTextSize(labelTextSizePx)
            view.setLabelBackground(labelBackground.toArgb())
            view.setDirection(direction)
            view.setScaleType(scaleType)
            view.setBitmap(bitmap)
        },
        modifier = modifier,
    )
}
