package com.ashorts.blocker.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.ashorts.blocker.log.DetectionLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Shows what aShorts matched and when. This is the debugging surface: when an Instagram update
 * renames its view ids and blocking stops working, this log is what you (or we) read to fix the
 * patterns in InstagramRules.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var entries by remember { mutableStateOf(DetectionLog.snapshot()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Detection log") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { entries = DetectionLog.snapshot() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = { shareLog(context, entries) }) {
                        Icon(Icons.Filled.Share, contentDescription = "Share")
                    }
                    IconButton(onClick = {
                        DetectionLog.clear()
                        entries = emptyList()
                    }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Clear")
                    }
                }
            )
        }
    ) { padding ->
        if (entries.isEmpty()) {
            Box(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Nothing detected yet.\n\nOpen Instagram with aShorts running — matches appear here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(modifier = Modifier.padding(padding).fillMaxSize()) {
                items(entries.asReversed()) { entry ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text(
                            formatTime(entry.timestamp) +
                                if (entry.screenDetected) " · Reels screen" else " · Reels tab",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            entry.detail,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

private fun shareLog(context: Context, entries: List<DetectionLog.Entry>) {
    if (entries.isEmpty()) return
    val text = entries.joinToString("\n") { e ->
        "${formatTime(e.timestamp)} ${if (e.screenDetected) "SCREEN" else "TAB"} ${e.detail}"
    }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, "aShorts detection log:\n$text")
    }
    context.startActivity(Intent.createChooser(intent, "Share detection log"))
}

private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

private fun formatTime(millis: Long): String =
    synchronized(timeFormat) { timeFormat.format(Date(millis)) }
