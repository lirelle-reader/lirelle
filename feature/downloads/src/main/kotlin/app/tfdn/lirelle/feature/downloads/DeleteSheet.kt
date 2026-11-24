package app.tfdn.lirelle.feature.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.designsystem.SingleLineLabel
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.nameRes
import app.tfdn.lirelle.core.ui.GripSheet
import app.tfdn.lirelle.core.ui.fileSize

const val DeleteSheetTag = "downloads-delete-sheet"
const val DeleteConfirmTag = "downloads-delete-confirm"
const val DeleteCancelTag = "downloads-delete-cancel"

fun deleteOptionTag(format: DownloadFormat): String = "downloads-delete-option-${format.name}"

/** With or without [format]; the text does not go while the narration, which needs it, stays. */
internal fun Set<DownloadFormat>.toggled(format: DownloadFormat): Set<DownloadFormat> = when {
    format !in this -> this + format
    format == DownloadFormat.Narration -> this - DownloadFormat.Narration - DownloadFormat.Text
    else -> this - format
}

/**
 * Which formats of a book to delete, each with its size. Nothing is checked at first. The text is kept while
 * the narration stays. The button says the space freed.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DeleteSheet(sheet: DeleteSheetUi, onConfirm: (Set<DownloadFormat>) -> Unit, onDismiss: () -> Unit) {
    var checked by remember(sheet.bookId) { mutableStateOf(emptySet<DownloadFormat>()) }
    val textKept = sheet.options.any { it.format == DownloadFormat.Narration } && DownloadFormat.Narration !in checked
    GripSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag(DeleteSheetTag)) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
        ) {
            Text(
                text = stringResource(R.string.downloads_delete),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = stringResource(R.string.downloads_sheet_subtitle, sheet.title, fileSize(sheet.bytes)),
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            sheet.options.forEach { option ->
                val locked = option.format == DownloadFormat.Text && textKept
                DeleteOptionRow(
                    option = option,
                    checked = option.format in checked,
                    locked = locked,
                    onToggle = { checked = checked.toggled(option.format) },
                )
            }
            Text(
                text = stringResource(R.string.downloads_sheet_note),
                modifier = Modifier.padding(vertical = 12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Side by side, or one under the other when the text is too large for it.
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = onDismiss, modifier = Modifier.testTag(DeleteCancelTag)) {
                    SingleLineLabel(stringResource(R.string.downloads_sheet_cancel))
                }
                val freed = sheet.options.filter { it.format in checked }.sumOf { it.bytes }
                Button(
                    onClick = { onConfirm(checked) },
                    modifier = Modifier.testTag(DeleteConfirmTag),
                    enabled = checked.isNotEmpty(),
                ) {
                    SingleLineLabel(
                        if (checked.isEmpty()) {
                            stringResource(R.string.downloads_sheet_confirm_nothing)
                        } else {
                            stringResource(R.string.downloads_sheet_confirm, fileSize(freed))
                        },
                    )
                }
            }
        }
    }
}

/** @param locked the text, while the narration stays: it cannot be checked, and says why. */
@Composable
private fun DeleteOptionRow(option: DeleteOption, checked: Boolean, locked: Boolean, onToggle: () -> Unit) {
    val ink = MaterialTheme.colorScheme.onSurface.copy(alpha = if (locked) DisabledAlpha else 1f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(deleteOptionTag(option.format))
            .toggleable(value = checked, enabled = !locked, role = Role.Checkbox, onValueChange = { onToggle() })
            .heightIn(min = 56.dp)
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = !locked)
        Column(modifier = Modifier.weight(1f)) {
            // The size beside the name, or under it when the text is too large for both on one line.
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                val name = stringResource(nameRes(option.format, option.textName))
                Text(name, color = ink, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = fileSize(option.bytes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (locked) {
                Text(
                    text = stringResource(R.string.downloads_sheet_text_kept),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private const val DisabledAlpha = 0.38f
