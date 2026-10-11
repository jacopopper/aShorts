package com.ashorts.blocker.engine

import com.ashorts.blocker.overlay.PatchCalibration
import org.junit.Assert.assertEquals
import org.junit.Test

class PatchCalibrationTest {

    @Test
    fun `no correction when window landed where asked`() {
        val intended = IntBounds(216, 2112, 432, 2256)
        assertEquals(intended, PatchCalibration.correct(intended, 216, 2112))
    }

    @Test
    fun `corrects a downward status-bar-style offset`() {
        // We asked for [216,2112]; the OEM rendered it 84px lower and to the right.
        val intended = IntBounds(216, 2112, 432, 2256)
        val corrected = PatchCalibration.correct(intended, actualLeft = 300, actualTop = 2196)
        assertEquals(IntBounds(132, 2028, 348, 2172), corrected)
        // Asking for the corrected position with the same 84px offset lands exactly on target.
        assertEquals(216, corrected.left + 84)
        assertEquals(2112, corrected.top + 84)
    }

    @Test
    fun `preserves patch size`() {
        val intended = IntBounds(216, 2112, 432, 2256)
        val corrected = PatchCalibration.correct(intended, actualLeft = 0, actualTop = 2400)
        assertEquals(intended.width, corrected.width)
        assertEquals(intended.height, corrected.height)
    }
}
