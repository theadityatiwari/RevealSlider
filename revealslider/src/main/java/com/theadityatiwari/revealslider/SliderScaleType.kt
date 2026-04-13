package com.theadityatiwari.revealslider

/**
 * How the source bitmap is scaled to fill the view.
 */
enum class SliderScaleType {
    /** Scale uniformly so the image covers the entire view, cropping excess. Default. */
    CENTER_CROP,

    /** Scale uniformly so the entire image fits, padding the remaining area with transparency. */
    FIT_CENTER,
}
