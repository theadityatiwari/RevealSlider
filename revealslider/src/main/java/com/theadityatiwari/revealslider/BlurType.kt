package com.theadityatiwari.revealslider

/**
 * Effect applied to the "Before" (styled) side of the slider.
 */
enum class BlurType {
    /** GPU-accelerated Gaussian blur (API 31+) with RenderScript fallback (API 24-30). */
    GAUSSIAN,

    /** Gaussian blur with a semi-transparent white overlay, giving a frosted-glass look. */
    FROSTED_GLASS,

    /** Gaussian blur with a semi-transparent dark overlay. */
    DARK_FADE,

    /** Pixel-block sampling — no blur, gives a mosaic/pixelated effect. */
    PIXELATE,
}
