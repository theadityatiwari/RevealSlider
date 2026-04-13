package com.theadityatiwari.revealslider

/**
 * Controls which side receives the blur/styled effect.
 */
enum class SliderDirection {
    /** Left side = blurred (Before), right side = sharp (After). Default. */
    BEFORE_AFTER,

    /** Left side = sharp (After), right side = blurred (Before). */
    AFTER_BEFORE,
}
