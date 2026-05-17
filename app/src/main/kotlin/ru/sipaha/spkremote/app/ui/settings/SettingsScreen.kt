package ru.sipaha.spkremote.app.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import ru.sipaha.spkremote.app.BuildConfig
import ru.sipaha.spkremote.app.vm.MainViewModel
import ru.sipaha.spkremote.core.ConnectionState
import ru.sipaha.spkremote.core.PairingUrl

/**
 * Settings / About screen. Reachable from the gear icon on
 * [ru.sipaha.spkremote.app.ui.solutions.SolutionsListScreen].
 *
 * Surfaces:
 *  - **Server info** — host:port + client name + fingerprint excerpt
 *    (first 8 + last 8 hex chars of the SHA-256 cert pin, monospace).
 *  - **Connection** — live [ConnectionState] from the underlying transport.
 *  - **Actions** — Forget paired server (destructive, with confirm dialog)
 *    + Re-pair (clear + jump to the QR screen — same destination, just
 *    skipping the confirm step because Re-pair is the explicit "I know
 *    what I'm doing" choice).
 *  - **About** — version, GitHub link, license blurb.
 *
 * [onForget] is invoked after a confirmed Forget; the nav graph wires it
 * to `forgetPairing()` + `navigate("pairing", popUpTo("pairing", inclusive=true))`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onForget: () -> Unit,
) {
    val pairing by viewModel.pairing.collectAsState()
    val connectionState by viewModel.rawConnectionState.collectAsState()
    val context = LocalContext.current

    var showForgetDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            SectionHeader("Server")
            ServerInfo(pairing)
            HorizontalDivider()

            SectionHeader("Connection")
            ConnectionRow(connectionState)
            HorizontalDivider()

            SectionHeader("Actions")
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Re-pair is non-destructive in intent ("I want to scan a
                // new QR") — it still wipes persistence, but the user is
                // about to immediately overwrite it. Skip the confirm.
                OutlinedButton(
                    onClick = onForget,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Re-pair (scan a new QR)") }
                OutlinedButton(
                    onClick = { showForgetDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Forget paired server") }
            }
            HorizontalDivider()

            SectionHeader("About")
            AboutInfo(
                onOpenGithub = {
                    val intent = Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/Sipaha/spk-editor"),
                    )
                    runCatching { context.startActivity(intent) }
                },
            )
        }
    }

    if (showForgetDialog) {
        AlertDialog(
            onDismissRequest = { showForgetDialog = false },
            title = { Text("Forget paired server?") },
            text = {
                Text(
                    "This will remove the pairing from this device. " +
                        "You'll need to scan the QR again to reconnect.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showForgetDialog = false
                        onForget()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Forget") }
            },
            dismissButton = {
                TextButton(onClick = { showForgetDialog = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SectionHeader(label: String) {
    Text(
        text = label.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun ServerInfo(pairing: PairingUrl?) {
    val rowPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        if (pairing == null) {
            Text(
                text = "Not paired.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(rowPadding),
            )
            return
        }
        LabelValue("Server", "${pairing.host}:${pairing.port}", padding = rowPadding)
        LabelValue("Client name", pairing.client, padding = rowPadding)
        LabelValue(
            "Fingerprint",
            // Render only first 8 + last 8 hex chars (≈ 64 bits of entropy
            // shown, which is enough for human-eyeballing a mismatch against
            // the editor's Remote Control UI without filling 64 chars of
            // small monospace text).
            fingerprintShort(pairing.fingerprint),
            padding = rowPadding,
            monospace = true,
        )
    }
}

@Composable
private fun ConnectionRow(state: ConnectionState) {
    val (text, color) = when (state) {
        ConnectionState.Connected ->
            "Connected" to MaterialTheme.colorScheme.primary
        ConnectionState.Connecting ->
            "Connecting…" to MaterialTheme.colorScheme.onSurfaceVariant
        ConnectionState.Disconnected ->
            "Disconnected" to MaterialTheme.colorScheme.onSurfaceVariant
        is ConnectionState.Reconnecting -> {
            val seconds = (state.nextRetryMs / 1000).coerceAtLeast(1)
            "Reconnecting (attempt ${state.attempt}, retry in ${seconds}s)" to
                MaterialTheme.colorScheme.tertiary
        }
        is ConnectionState.FailedTerminal ->
            "Re-pair required (${state.reason})" to MaterialTheme.colorScheme.error
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun AboutInfo(onOpenGithub: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "SPK Remote · v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            style = MaterialTheme.typography.bodyMedium,
        )
        TextButton(onClick = onOpenGithub, contentPadding = PaddingValues(0.dp)) {
            Text("Sipaha/spk-editor on GitHub")
        }
        Text(
            text = "GPL-3.0-or-later · © 2026 Pavel Simonov",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LabelValue(
    label: String,
    value: String,
    padding: PaddingValues,
    monospace: Boolean = false,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(padding)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = if (monospace) FontFamily.Monospace else null,
        )
    }
}

/**
 * Render a SHA-256 fingerprint as `aabbccdd…11223344` — enough characters
 * for a human to spot-check against the editor's QR/pairing dialog without
 * displaying the full 64-char string in a phone-sized label.
 */
private fun fingerprintShort(fp: ByteArray): String {
    val hex = fp.joinToString(separator = "") { "%02x".format(it) }
    if (hex.length <= 16) return hex
    return hex.substring(0, 8) + "…" + hex.substring(hex.length - 8)
}
