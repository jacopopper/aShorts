package com.ashorts.blocker.overlay

import android.content.res.Configuration
import com.ashorts.blocker.engine.InstagramRules

/** Tracks the night mode delivered by Android, preserving it for partial configurations. */
class OverlayTheme(uiMode: Int) {

    var isNight = false
        private set

    init {
        update(uiMode)
    }

    val patchColor: Int
        get() = if (isNight) InstagramRules.BAR_COLOR_DARK else InstagramRules.BAR_COLOR_LIGHT

    fun update(uiMode: Int) {
        when (uiMode and Configuration.UI_MODE_NIGHT_MASK) {
            Configuration.UI_MODE_NIGHT_YES -> isNight = true
            Configuration.UI_MODE_NIGHT_NO -> isNight = false
        }
    }
}
