package ru.sipaha.spkremote.app.ui.settings

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ru.sipaha.spkremote.app.diagnostics.CrashLogger
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * Settings → Crash logs. Lists every file under `filesDir/crash-logs`
 * newest-first, lets the user tap one to view the contents, share it
 * via Intent.ACTION_SEND (text/plain), or clear them all. No automatic
 * upload to anywhere — the user is in charge of where the report goes.
 *
 * State is held locally (`var files by remember`) and reseeded only
 * when the user clears. We *don't* re-read on every recomposition —
 * crashes happen between process lifetimes, so the list of files
 * doesn't change during a single screen session except as a result of
 * `clearAll()`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrashLogsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var files by remember { mutableStateOf(CrashLogger.listCrashFiles(context)) }
    var viewing by remember { mutableStateOf<File?>(null) }
    var showClearDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Crash logs") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                actions = {
                    if (files.isNotEmpty()) {
                        IconButton(onClick = { showClearDialog = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Clear all")
                        }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            // Clear the gesture nav bar so the last crash-log row isn't
            // half-hidden at rest.
            contentPadding = WindowInsets.navigationBars.asPaddingValues(),
        ) {
            if (files.isEmpty()) {
                item {
                    Text(
                        text = "No crash logs.",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(files, key = { it.name }) { file ->
                    ListItem(
                        headlineContent = { Text(file.name) },
                        supportingContent = {
                            Text(
                                text = DateFormat.getDateTimeInstance()
                                    .format(Date(file.lastModified())),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                        modifier = Modifier.clickable { viewing = file },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    viewing?.let { file ->
        AlertDialog(
            onDismissRequest = { viewing = null },
            title = { Text(file.name) },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = CrashLogger.readCrashFile(file),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    shareCrashLog(context, file)
                    viewing = null
                }) { Text("Share") }
            },
            dismissButton = {
                TextButton(onClick = { viewing = null }) { Text("Close") }
            },
        )
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear crash logs?") },
            text = { Text("All ${files.size} crash file(s) will be deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    CrashLogger.clearAll(context)
                    files = emptyList()
                    showClearDialog = false
                }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("Cancel") }
            },
        )
    }
}

/**
 * Fire an ACTION_SEND chooser so the user can pipe the crash text into
 * mail / Telegram / Files. We attach the body as EXTRA_TEXT (not a
 * `content://` Uri via FileProvider) because:
 *  1. We don't ship a FileProvider authority yet — adding one is more
 *     work than the share flow benefits from at this stage.
 *  2. Crash files are small (a few KB) and fit in an intent extra.
 *  3. The text body is the same shape the user sees on screen, so
 *     "send the message you just read to me" is intuitively the
 *     correct mental model.
 */
private fun shareCrashLog(context: Context, file: File) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "spk-editor crash: ${file.name}")
        putExtra(Intent.EXTRA_TEXT, CrashLogger.readCrashFile(file))
    }
    context.startActivity(Intent.createChooser(intent, "Share crash log"))
}
