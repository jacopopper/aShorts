package com.ashorts.blocker.engine

/** Plain rectangle in screen coordinates, independent of any Android framework class. */
data class IntBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val isEmpty: Boolean get() = width <= 0 || height <= 0

    override fun toString(): String = "[$left,$top $right,$bottom]"
}
