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

    const val DEFAULT_MASTER = true
    const val DEFAULT_HIDE_TAB = true
    const val DEFAULT_BLOCKER = true
    const val DEFAULT_COOLDOWN = 60

    fun get(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
