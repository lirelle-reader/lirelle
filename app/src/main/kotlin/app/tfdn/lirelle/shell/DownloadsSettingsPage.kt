package app.tfdn.lirelle.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tfdn.lirelle.R
import app.tfdn.lirelle.core.designsystem.SingleLineLabel
import app.tfdn.lirelle.core.ui.SettingsPageFrame
import app.tfdn.lirelle.core.ui.fileSize

/** @param onBack null inside a pane: the page then shows its title and no arrow. */
@Composable
fun DownloadsSettingsRoute(onBack: (() -> Unit)?, modifier: Modifier = Modifier) {
    val viewModel = hiltViewModel<DownloadsSettingsViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()
    DownloadsSettingsPage(state, viewModel::onAllowMetered, viewModel::onDeleteAll, onBack, modifier)
}

/**
 * The Downloads page of the settings: which networks downloads may use, the room they take, and deleting them
 * all, which asks first.
 *
 * @param onBack null inside a pane: the page then shows its title and no arrow.
 */
@Composable
fun DownloadsSettingsPage(
    state: DownloadsSettingsState,
    onAllowMetered: (Boolean) -> Unit,
    onDeleteAll: () -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    SettingsPageFrame(title = stringResource(R.string.more_downloads), onBack = onBack, modifier = modifier) {
        DownloadsSettingsRows(state, onAllowMetered, onDeleteAll)
    }
}

/** The rows of the page, and the question deleting everything asks. */
@Composable
private fun DownloadsSettingsRows(
    state: DownloadsSettingsState,
    onAllowMetered: (Boolean) -> Unit,
    onDeleteAll: () -> Unit,
) {
    var asking by rememberSaveable { mutableStateOf(false) }
    val error = MaterialTheme.colorScheme.error
    Column {
        MeteredRow(state.allowMetered, onAllowMetered)
        SpaceRow(state)
        if (state.books > 0) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.more_downloads_delete_all), color = error) },
                // A row, which grows with its text, not a button: its label may wrap.
                modifier = Modifier.clickable { asking = true },
            )
        }
    }
    // Gone with the downloads: deleted from elsewhere while it was asking.
    if (asking && state.books > 0) {
        DeleteAllDialog(
            state = state,
            onConfirm = {
                asking = false
                onDeleteAll()
            },
            onDismiss = { asking = false },
        )
    }
}

/**
 * One switch with its label and what its state means: the whole row toggles it. The sentence goes under both,
 * on the whole width: beside the switch, a long word of it would not fit a narrow screen in large text.
 */
@Composable
private fun MeteredRow(allowed: Boolean, onAllowMetered: (Boolean) -> Unit) {
    val meaning = if (allowed) R.string.more_downloads_metered_on else R.string.more_downloads_metered_off
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = allowed, role = Role.Switch, onValueChange = onAllowMetered)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                text = stringResource(R.string.more_downloads_metered),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = allowed, onCheckedChange = null)
        }
        Text(
            text = stringResource(meaning),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SpaceRow(state: DownloadsSettingsState) {
    val detail = pluralStringResource(
        R.plurals.more_downloads_space_detail,
        state.books,
        state.books,
        fileSize(state.freeBytes),
    )
    ListItem(
        headlineContent = { Text(stringResource(R.string.more_downloads_space)) },
        supportingContent = { Text(detail) },
        trailingContent = { Text(fileSize(state.usedBytes), style = MaterialTheme.typography.bodyLarge) },
        // Read as one sentence, not as three texts.
        modifier = Modifier.semantics(mergeDescendants = true) {},
    )
}

@Composable
private fun DeleteAllDialog(state: DownloadsSettingsState, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.more_downloads_delete_all_title)) },
        text = {
            Text(
                pluralStringResource(
                    R.plurals.more_downloads_delete_all_message,
                    state.books,
                    state.books,
                    fileSize(state.usedBytes),
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                SingleLineLabel(
                    text = stringResource(R.string.more_downloads_delete_all_confirm),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { SingleLineLabel(stringResource(R.string.more_downloads_cancel)) }
        },
    )
}
