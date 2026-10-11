package com.ashorts.blocker.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Strict mode. Turning blocking off requires sitting through a countdown; if the user leaves the
 * app (the activity stops), the dialog disappears without ever calling [onComplete], so
 * protection stays on. Impulse-toggling requires patience, which is the point.
 */
@Composable
fun StrictCooldownDialog(seconds: Int, onComplete: () -> Unit, onCancel: () -> Unit) {
    var remaining by remember { mutableIntStateOf(seconds) }

    LaunchedEffect(Unit) {
        repeat(seconds) {
            delay(1_000)
            remaining -= 1
        }
        onComplete()
    }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Turning off protection") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(progress = { 1f - remaining.toFloat() / seconds })
                Spacer(Modifier.height(16.dp))
                Text(
                    "Blocking stays on for " +
                        "${remaining / 60}:${String.format(Locale.US, "%02d", remaining % 60)} more."
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Keep this screen open. If you leave the app, protection stays on.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onCancel) { Text("Keep blocking") }
        }
    )
}
