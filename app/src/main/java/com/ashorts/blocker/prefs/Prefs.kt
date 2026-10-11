package com.ashorts.blocker.prefs

import android.content.Context
import android.content.SharedPreferences

/** Single small preferences file shared by the accessibility service and the UI. */
object Prefs {

    const val FILE = "ashorts_prefs"

    const val KEY_MASTER = "master_enabled"
    const val KEY_HIDE_TAB = "hide_tab"
    const val KEY_BLOCKER = "blocker_screen"
    const val KEY_COOLDOWN_SECONDS = "cooldown_seconds"
    const val KEY_AGGRESSIVE = "aggressive_text_match"
    const val KEY_BAR_THEME = "instagram_theme"

    /** Instagram's own appearance setting can differ from the system theme. */
    const val THEME_AUTO = "auto"
    const val THEME_DARK = "dark"
    const val THEME_LIGHT = "light"

    const val DEFAULT_MASTER = true
    const val DEFAULT_HIDE_TAB = true
    const val DEFAULT_BLOCKER = true
    const val DEFAULT_COOLDOWN = 60
    const val DEFAULT_AGGRESSIVE = false
    const val DEFAULT_BAR_THEME = THEME_AUTO

    fun get(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
