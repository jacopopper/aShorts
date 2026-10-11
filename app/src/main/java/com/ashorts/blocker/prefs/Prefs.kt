package com.ashorts.blocker.prefs

import android.content.Context
import android.content.SharedPreferences

/** Single small preferences file shared by the accessibility service and the UI. */
object Prefs {

    const val FILE = "ashorts_prefs"

    const val KEY_MASTER = "master_enabled"
    const val KEY_COOLDOWN_SECONDS = "cooldown_seconds"

    /** Written by the service: Android kept a pre-update grant without screen capture. */
    const val KEY_CAPTURE_MISSING = "screen_capture_missing"

    const val DEFAULT_MASTER = true
    const val DEFAULT_COOLDOWN = 60
    val COOLDOWN_OPTIONS = listOf(30, 60, 120, 300)

    /** Existing "No wait" settings fall back to the default countdown. */
    fun normalizeCooldown(seconds: Int): Int =
        seconds.takeIf { it in COOLDOWN_OPTIONS } ?: DEFAULT_COOLDOWN

    fun get(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
