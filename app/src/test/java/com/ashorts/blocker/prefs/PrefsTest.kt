package com.ashorts.blocker.prefs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrefsTest {

    @Test
    fun `countdown options never disable the wait`() {
        assertEquals(listOf(30, 60, 120, 300), Prefs.COOLDOWN_OPTIONS)
        assertTrue(Prefs.COOLDOWN_OPTIONS.all { it > 0 })
    }

    @Test
    fun `existing no wait setting uses the default countdown`() {
        assertEquals(Prefs.DEFAULT_COOLDOWN, Prefs.normalizeCooldown(0))
    }

    @Test
    fun `supported countdown settings are preserved`() {
        Prefs.COOLDOWN_OPTIONS.forEach {
            assertEquals(it, Prefs.normalizeCooldown(it))
        }
    }

    @Test
    fun `invalid countdown settings use the default`() {
        assertEquals(Prefs.DEFAULT_COOLDOWN, Prefs.normalizeCooldown(-1))
        assertEquals(Prefs.DEFAULT_COOLDOWN, Prefs.normalizeCooldown(15))
    }
}
