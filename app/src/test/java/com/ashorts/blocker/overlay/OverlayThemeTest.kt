package com.ashorts.blocker.overlay

import android.content.res.Configuration
import com.ashorts.blocker.engine.InstagramRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayThemeTest {

    @Test
    fun `light mode uses the white patch`() {
        val theme = OverlayTheme(Configuration.UI_MODE_NIGHT_NO)
        assertFalse(theme.isNight)
        assertEquals(InstagramRules.BAR_COLOR_LIGHT, theme.patchColor)
    }

    @Test
    fun `dark mode uses the dark Instagram bar color`() {
        val theme = OverlayTheme(Configuration.UI_MODE_NIGHT_YES)
        assertTrue(theme.isNight)
        assertEquals(InstagramRules.BAR_COLOR_DARK, theme.patchColor)
    }

    @Test
    fun `theme switches update the patch in both directions`() {
        val theme = OverlayTheme(Configuration.UI_MODE_NIGHT_YES)
        theme.update(Configuration.UI_MODE_NIGHT_NO)
        assertEquals(InstagramRules.BAR_COLOR_LIGHT, theme.patchColor)
        theme.update(Configuration.UI_MODE_NIGHT_YES)
        assertEquals(InstagramRules.BAR_COLOR_DARK, theme.patchColor)
    }

    @Test
    fun `other configuration bits do not affect night mode`() {
        val theme = OverlayTheme(Configuration.UI_MODE_TYPE_NORMAL or Configuration.UI_MODE_NIGHT_YES)
        assertTrue(theme.isNight)
        theme.update(Configuration.UI_MODE_TYPE_NORMAL or Configuration.UI_MODE_NIGHT_NO)
        assertFalse(theme.isNight)
    }

    @Test
    fun `partial configuration preserves the last known mode`() {
        val theme = OverlayTheme(Configuration.UI_MODE_NIGHT_YES)
        theme.update(Configuration.UI_MODE_NIGHT_UNDEFINED)
        assertTrue(theme.isNight)
        theme.update(Configuration.UI_MODE_NIGHT_NO)
        theme.update(Configuration.UI_MODE_NIGHT_UNDEFINED)
        assertFalse(theme.isNight)
    }
}
