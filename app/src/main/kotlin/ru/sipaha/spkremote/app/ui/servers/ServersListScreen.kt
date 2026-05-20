package ru.sipaha.spkremote.app.ui.servers

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ru.sipaha.spkremote.app.data.PairedServer
import ru.sipaha.spkremote.app.vm.MainViewModel
import ru.sipaha.spkremote.core.ConnectionState
import ru.sipaha.spkremote.core.PairingUrl

/**
 * Servers list screen — the multi-server entry point (R-6c-multi).
 *
 * Reached on cold start when 2+ paired servers exist. The
 * single-paired-server case keeps the R-6b auto-resume behavior (lands
 * directly on `solutions`), so this screen is for "I have a desk
 * server + a laptop + a home machine paired; pick one now".
 *
 * **Per-row state pill:** only the currently-active server's row shows
 * a live [ConnectionState] pill (Connected / Reconnecting / Re-pair
 * required). Other rows show a neutral "Tap to connect" hint — we
 * don't background-ping non-active servers (deferred to R-6c-push
 * with FCM).
 *
 * **Long-press to remove:** confirmation dialog before
 * [MainViewModel.removeServer]. Tap dismisses; only the explicit
 * "Remove" button in the confirm dialog triggers the destructive
 * action.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ServersListScreen(
    viewModel: MainViewModel,
    onOpenServer: (serverId: String) -> Unit,
    onAddNew: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val servers by viewModel.pairedServers.collectAsState()
    val activeId by viewModel.activeServerId.collectAsState()
    val connectionState by viewModel.rawConnectionState.collectAsState()

    var pendingRemoval by remember { mutableStateOf<PairedServer?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SPK Editor servers") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddNew,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Pair new server") },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (servers.isEmpty()) {
                EmptyState(
                    title = "No servers paired",
                    body = "Tap + to scan a pairing QR.",
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    // Clear the gesture nav bar AND the FAB so the last row's
                    // tap / long-press target isn't hidden under either.
                    contentPadding = PaddingValues(
                        bottom = 88.dp +
                            WindowInsets.navigationBars.asPaddingValues()
                                .calculateBottomPadding(),
                    ),
                ) {
                    items(servers, key = { it.id }) { server ->
                        ServerRow(
                            server = server,
                            isActive = server.id == activeId,
                            activeConnectionState = connectionState,
                            onClick = {
                                viewModel.switchToServer(server.id)
                                onOpenServer(server.id)
                            },
                            onLongClick = { pendingRemoval = server },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    val toRemove = pendingRemoval
    if (toRemove != null) {
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { Text("Remove paired server?") },
            text = {
                Text(
                    "\"${toRemove.label}\" will be removed from this device, along " +
                        "with any drafts, queued messages, and saved chat history. " +
                        "You'll need to scan the QR again to reconnect.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.removeServer(toRemove.id)
                        pendingRemoval = null
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoval = null }) { Text("Cancel") }
            },
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ServerRow(
    server: PairedServer,
    isActive: Boolean,
    activeConnectionState: ConnectionState,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = server.label,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = hostPortFor(server),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "fingerprint ${fingerprintShort(server.fingerprintHex)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
            )
            server.lastConnectedAtMs?.let { ts ->
                Text(
                    text = "Last connected ${relativeTime(ts)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        StatePill(isActive = isActive, activeConnectionState = activeConnectionState)
    }
}

@Composable
private fun StatePill(isActive: Boolean, activeConnectionState: ConnectionState) {
    val (text, container, content) = when {
        !isActive -> Triple(
            "Tap to connect",
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
        activeConnectionState is ConnectionState.Connected -> Triple(
            "Connected",
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
        )
        activeConnectionState is ConnectionState.Reconnecting -> Triple(
            "Reconnecting…",
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
        )
        activeConnectionState is ConnectionState.FailedTerminal -> Triple(
            "Re-pair required",
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
        )
        activeConnectionState is ConnectionState.Connecting -> Triple(
            "Connecting…",
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
        )
        else -> Triple(
            "Disconnected",
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Surface(
        color = container,
        contentColor = content,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun EmptyState(title: String, body: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/**
 * Parse the embedded `host:port` from [PairedServer.pairingUrl] for
 * display. Returns a placeholder when the URL fails to parse —
 * shouldn't happen in practice (we only persist URLs that parsed at
 * pair time), but the defensive path keeps the row from crashing the
 * whole screen.
 */
private fun hostPortFor(server: PairedServer): String {
    val parsed = PairingUrl.parse(server.pairingUrl).getOrNull()
        ?: return "(unparseable URL)"
    return "${parsed.host}:${parsed.port}"
}

/**
 * `aabbccdd…11223344` — 4 leading + 4 trailing hex chars, joined by an
 * ellipsis. Matches the editor's QR-pairing dialog so a human can
 * spot-check both sides.
 */
private fun fingerprintShort(fpHex: String): String {
    if (fpHex.length <= 8) return fpHex
    return fpHex.substring(0, 4) + "…" + fpHex.substring(fpHex.length - 4)
}

/**
 * Simple "Xs / Xm / Xh / Xd ago" formatter. Avoids pulling in a full
 * relative-time formatter (would add an extra dep) — accuracy here is
 * cosmetic and the user just needs "recent" vs "old" disambiguation.
 */
private fun relativeTime(ms: Long): String {
    val deltaSec = ((System.currentTimeMillis() - ms) / 1000L).coerceAtLeast(0L)
    return when {
        deltaSec < 60 -> "just now"
        deltaSec < 3_600 -> "${deltaSec / 60}m ago"
        deltaSec < 86_400 -> "${deltaSec / 3_600}h ago"
        else -> "${deltaSec / 86_400}d ago"
    }
}

