package com.ashorts.blocker.overlay

import com.ashorts.blocker.engine.IntBounds

/**
 * Pure math for the patch self-calibration (unit-tested on the JVM).
 *
 * Some OEM window managers position overlay windows with an offset (status-bar inset, decor
 * frame...) even with FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_NO_LIMITS. So after placing the patch
 * we read where the window *actually* landed and, if it is off, compute corrected parameters:
 * if asking for X produced a render at A, asking for X + (X - A) lands at X.
 */
object PatchCalibration {

    /** Returns bounds shifted so the window lands where [intended] wanted it. */
    fun correct(intended: IntBounds, actualLeft: Int, actualTop: Int): IntBounds {
        if (actualLeft == intended.left && actualTop == intended.top) return intended
        val dx = intended.left - actualLeft
        val dy = intended.top - actualTop
        return IntBounds(
            intended.left + dx,
            intended.top + dy,
            intended.right + dx,
            intended.bottom + dy
        )
    }
}
