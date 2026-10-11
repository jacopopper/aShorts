package com.ashorts.blocker.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Color(0xFF1F6B4E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB7F0D2),
    secondary = Color(0xFF4C6358),
    surface = Color(0xFFFAFAF8)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7FD8AC),
    onPrimary = Color(0xFF003822),
    primaryContainer = Color(0xFF005231),
    secondary = Color(0xFFB3CCBD),
    surface = Color(0xFF0B0E12)
)

@Composable
fun aShortsTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (dark) DarkColors else LightColors
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
