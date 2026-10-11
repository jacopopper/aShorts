package com.ashorts.blocker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Google Play's prominent disclosure for the Accessibility API: shown in the app, before the
 * user is sent to the system settings, stating what is accessed and why, and requiring an
 * explicit "Agree" tap. Declining does nothing.
 */
@Composable
fun AccessibilityDisclosureDialog(onAgree: () -> Unit, onDecline: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDecline,
        title = { Text("aShorts uses the Accessibility service") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "To block Instagram Reels, aShorts uses Android's AccessibilityService API.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Section(
                    "What it reads",
                    "Only while Instagram is open: the names and positions of on-screen " +
                        "elements, such as the Reels tab and the Reels viewer. aShorts ignores " +
                        "every other app."
                )
                Section(
                    "What it does",
                    "Covers the Reels tab and presses Back for you when Reels opens."
                )
                Section(
                    "What it never does",
                    "No collecting, storing or sharing of personal data, messages or what " +
                        "you type. aShorts has no internet permission, so nothing can leave " +
                        "your phone. A short detection log stays on the device until you " +
                        "clear it."
                )
                Text(
                    "Tap Agree to open Accessibility settings, then turn on aShorts.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { Button(onClick = onAgree) { Text("Agree") } },
        dismissButton = { TextButton(onClick = onDecline) { Text("No thanks") } }
    )
}

@Composable
private fun Section(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(body, style = MaterialTheme.typography.bodyMedium)
    }
}
