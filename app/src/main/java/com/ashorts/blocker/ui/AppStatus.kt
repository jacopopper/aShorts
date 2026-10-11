package com.ashorts.blocker.ui

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import com.ashorts.blocker.engine.InstagramRules
import com.ashorts.blocker.service.BlockerAccessibilityService

/** Checks the pieces aShorts needs, so the UI can show exactly what is missing. */
object AppStatus {

    fun isServiceEnabled(context: Context): Boolean {
        val expected = ComponentName(context, BlockerAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
    }

    fun isOverlayGranted(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun isInstagramInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(InstagramRules.PACKAGE, 0)
        true
    } catch (_: Exception) {
        false
    }
}
