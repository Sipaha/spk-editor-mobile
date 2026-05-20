package ru.sipaha.spkremote.app.ui.solutions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import ru.sipaha.spkremote.core.CatalogProjectInfo

/**
 * Reusable project-picker body — mirrors the desktop "+" project picker
 * minus the git-clone-from-URL row (desktop-only on this fork). Used by
 * both the New Solution dialog and the Solution detail "+ Add project"
 * sheet.
 *
 * Renders as a [Column] so the caller owns the surrounding container
 * (AlertDialog text slot, bottom sheet, …). Two affordances:
 *   - a "Create new project" row that expands to a name field, calling
 *     [onCreateEmpty] with the trimmed name on confirm,
 *   - one checkbox row per [CatalogProjectInfo], toggling membership in
 *     [selected] via [onToggle].
 *
 * Selection is fully caller-owned ([selected] + [onToggle]) so the picker
 * stays stateless across recompositions and works identically whether the
 * target solution already exists or is about to be created.
 */
@Composable
fun ProjectPicker(
    catalog: List<CatalogProjectInfo>,
    selected: Set<String>,
    onToggle: (catalogId: String) -> Unit,
    onCreateEmpty: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        CreateEmptyProjectRow(onCreateEmpty = onCreateEmpty)

        if (catalog.isEmpty()) {
            Text(
                text = "No registry projects available. Add them in SPK Editor on your computer, or create a new empty project above.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        } else {
            for (project in catalog) {
                val checked = project.catalogId in selected
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggle(project.catalogId) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = { onToggle(project.catalogId) },
                    )
                    Text(
                        text = project.name,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CreateEmptyProjectRow(onCreateEmpty: (String) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }

    if (!expanded) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text(
                text = "Create new project",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        return
    }

    val confirm: () -> Unit = {
        val trimmed = name.trim()
        if (trimmed.isNotEmpty()) {
            onCreateEmpty(trimmed)
            name = ""
            expanded = false
        }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("New project name") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { confirm() }),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = { name = ""; expanded = false }) { Text("Cancel") }
            TextButton(onClick = confirm, enabled = name.isNotBlank()) { Text("Add") }
        }
    }
}
