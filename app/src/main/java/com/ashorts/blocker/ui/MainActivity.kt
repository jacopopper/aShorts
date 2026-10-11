package com.ashorts.blocker.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ashorts.blocker.prefs.Prefs

class MainActivity : ComponentActivity() {

    /** Bumped on every resume so the UI re-reads permissions and prefs. */
    private val refreshTick = mutableIntStateOf(0)
    var pendingDisable by mutableStateOf(false)
    var showLog by mutableStateOf(false)

    override fun onResume() {
        super.onResume()
        refreshTick.intValue++
    }

    override fun onStop() {
        super.onStop()
        // Strictness: leaving the app cancels an unfinished disable.
        pendingDisable = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            aShortsTheme {
                Root()
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Root() {
        if (showLog) {
            LogScreen(onBack = { showLog = false })
            return
        }

        val ctx: Context = this
        val tick = refreshTick.intValue
        val prefs = remember(tick) { Prefs.get(ctx) }

        var master by remember(tick) {
            mutableStateOf(prefs.getBoolean(Prefs.KEY_MASTER, Prefs.DEFAULT_MASTER))
        }
        var hideTab by remember(tick) {
            mutableStateOf(prefs.getBoolean(Prefs.KEY_HIDE_TAB, Prefs.DEFAULT_HIDE_TAB))
        }
        var useBlocker by remember(tick) {
            mutableStateOf(prefs.getBoolean(Prefs.KEY_BLOCKER, Prefs.DEFAULT_BLOCKER))
        }
        var cooldownSec by remember(tick) {
            mutableIntStateOf(prefs.getInt(Prefs.KEY_COOLDOWN_SECONDS, Prefs.DEFAULT_COOLDOWN))
        }
        var aggressive by remember(tick) {
            mutableStateOf(prefs.getBoolean(Prefs.KEY_AGGRESSIVE, Prefs.DEFAULT_AGGRESSIVE))
        }

        val serviceOk = remember(tick) { AppStatus.isServiceEnabled(ctx) }
        val overlayOk = remember(tick) { AppStatus.isOverlayGranted(ctx) }
        val igInstalled = remember(tick) { AppStatus.isInstagramInstalled(ctx) }

        fun putBoolean(key: String, value: Boolean) {
            prefs.edit().putBoolean(key, value).apply()
        }

        Scaffold(
            topBar = { TopAppBar(title = { Text("aShorts") }) }
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                HeroCard(
                    active = master && serviceOk && overlayOk && igInstalled,
                    master = master,
                    serviceOk = serviceOk,
                    overlayOk = overlayOk,
                    igInstalled = igInstalled
                )

                if (!serviceOk) {
                    SetupRow(
                        icon = Icons.Filled.Warning,
                        title = "Accessibility service is off",
                        subtitle = "Required. aShorts only watches the Instagram app.",
                        button = "Open settings"
                    ) {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                    if (Build.VERSION.SDK_INT >= 33) {
                        Text(
                            "Android 13+: if the aShorts switch is greyed out in accessibility " +
                                "settings, open Apps → aShorts → ⋮ → \"Allow restricted settings\" first.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (!overlayOk) {
                    SetupRow(
                        icon = Icons.Filled.Warning,
                        title = "Overlay permission is off",
                        subtitle = "Required to cover the Reels tab and show the blocker.",
                        button = "Allow"
                    ) {
                        startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:$packageName")
                            )
                        )
                    }
                }

                if (serviceOk && overlayOk && !igInstalled) {
                    SetupRow(
                        icon = Icons.Filled.Info,
                        title = "Instagram not found",
                        subtitle = "aShorts is ready, but Instagram (com.instagram.android) " +
                            "is not installed on this device.",
                        button = "Recheck"
                    ) { refreshTick.intValue++ }
                }

                SwitchCard(
                    title = "Block Instagram Reels",
                    subtitle = when {
                        !serviceOk || !overlayOk -> "Finish setup above first."
                        master -> "Active: the Reels tab is hidden and the feed is blocked."
                        else -> "Paused: Reels work normally."
                    },
                    checked = master
                ) { on ->
                    if (on) {
                        putBoolean(Prefs.KEY_MASTER, true)
                        master = true
                    } else if (cooldownSec > 0) {
                        pendingDisable = true
                    } else {
                        putBoolean(Prefs.KEY_MASTER, false)
                        master = false
                    }
                }

                if (pendingDisable) {
                    StrictCooldownDialog(
                        seconds = cooldownSec,
                        onComplete = {
                            putBoolean(Prefs.KEY_MASTER, false)
                            master = false
                            pendingDisable = false
                        },
                        onCancel = { pendingDisable = false }
                    )
                }

                OptionsCard(
                    hideTab = hideTab,
                    useBlocker = useBlocker,
                    aggressive = aggressive,
                    cooldownSec = cooldownSec,
                    onHideTab = { hideTab = it; putBoolean(Prefs.KEY_HIDE_TAB, it) },
                    onBlocker = { useBlocker = it; putBoolean(Prefs.KEY_BLOCKER, it) },
                    onAggressive = { aggressive = it; putBoolean(Prefs.KEY_AGGRESSIVE, it) },
                    onCooldown = {
                        cooldownSec = it
                        prefs.edit().putInt(Prefs.KEY_COOLDOWN_SECONDS, it).apply()
                    }
                )

                OutlinedButton(onClick = { showLog = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Detection log")
                }

                HowItWorks()
            }
        }
    }

    // ------------------------------------------------------------- cards

    @Composable
    private fun HeroCard(
        active: Boolean,
        master: Boolean,
        serviceOk: Boolean,
        overlayOk: Boolean,
        igInstalled: Boolean
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        if (active) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                        contentDescription = null,
                        tint = if (active) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
                    Text(
                        when {
                            active -> "Reels blocked"
                            !master -> "Protection paused"
                            else -> "Setup needed"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    when {
                        active -> "Everything is running. Open Instagram: the Reels tab is " +
                            "covered and the Reels feed is blocked."
                        !serviceOk -> "Enable the accessibility service and the overlay " +
                            "permission to start protecting you."
                        !overlayOk -> "One more permission: allow aShorts to draw over other apps."
                        !igInstalled -> "aShorts is set up. It will kick in once Instagram is " +
                            "installed."
                        else -> "Everything checks out."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    @Composable
    private fun SetupRow(
        icon: ImageVector,
        title: String,
        subtitle: String,
        button: String,
        tint: Color = MaterialTheme.colorScheme.error,
        onClick: () -> Unit
    ) {
        Card(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(icon, contentDescription = null, tint = tint)
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.size(12.dp))
                TextButton(onClick = onClick) { Text(button) }
            }
        }
    }

    @Composable
    private fun SwitchCard(
        title: String,
        subtitle: String,
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit
    ) {
        Card(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = checked, onCheckedChange = onCheckedChange)
            }
        }
    }

    @Composable
    private fun OptionsCard(
        hideTab: Boolean,
        useBlocker: Boolean,
        aggressive: Boolean,
        cooldownSec: Int,
        onHideTab: (Boolean) -> Unit,
        onBlocker: (Boolean) -> Unit,
        onAggressive: (Boolean) -> Unit,
        onCooldown: (Int) -> Unit
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    "Options",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                OptionSwitch(
                    "Hide the Reels tab",
                    "Covers the Reels button in Instagram's bottom bar.",
                    hideTab,
                    onHideTab
                )
                OptionSwitch(
                    "Full-screen blocker",
                    "Covers the Reels feed when it opens from links, DMs or search.",
                    useBlocker,
                    onBlocker
                )
                OptionSwitch(
                    "Aggressive matching (beta)",
                    "Also blocks screens whose labels mention Reels. May over-block.",
                    aggressive,
                    onAggressive
                )
                CooldownRow(cooldownSec, onCooldown)
            }
        }
    }

    @Composable
    private fun OptionSwitch(
        title: String,
        subtitle: String,
        checked: Boolean,
        onChange: (Boolean) -> Unit
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }

    @Composable
    private fun CooldownRow(seconds: Int, onPick: (Int) -> Unit) {
        var expanded by remember { mutableStateOf(false) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Wait before disabling", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Strict mode: turning blocking off requires sitting through a countdown.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Box {
                TextButton(onClick = { expanded = true }) { Text(cooldownLabel(seconds)) }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    listOf(0, 30, 60, 120, 300).forEach { s ->
                        DropdownMenuItem(
                            text = { Text(cooldownLabel(s)) },
                            onClick = {
                                onPick(s)
                                expanded = false
                            }
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun HowItWorks() {
        Card(Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    "How it works",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "aShorts uses Android's accessibility API to recognize Instagram's Reels UI " +
                        "and draws an overlay on top of it. No root, no modification of " +
                        "Instagram, and no network access — the app has no internet permission " +
                        "and cannot send data anywhere.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "Battery: the service inspects the screen only while Instagram is visible, " +
                        "with throttled and size-capped scans. While Instagram is closed it does " +
                        "nothing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "If an Instagram update ever breaks blocking, open the Detection log: it " +
                        "records exactly what aShorts matched, so the rules can be updated in " +
                        "minutes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun cooldownLabel(seconds: Int): String = when (seconds) {
    0 -> "No wait"
    30 -> "30 seconds"
    60 -> "1 minute"
    120 -> "2 minutes"
    300 -> "5 minutes"
    else -> "$seconds seconds"
}
