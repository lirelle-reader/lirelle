package app.tfdn.lirelle.feature.book.download

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.ui.fileSize
import app.tfdn.lirelle.feature.book.R

fun formatRowTag(format: DownloadFormat) = "format-row-${format.name}"

/**
 * One format of the sheet: a checkbox, the whole row being what is tapped and read; its name with its size,
 * which goes under the name when the two do not fit on a line; that it has an update, when it has one; and
 * what it is for, or how far its download is.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FormatRow(row: SheetRow, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(formatRowTag(row.format))
            .toggleable(value = row.checked, enabled = !row.locked, role = Role.Checkbox) { onToggle() }
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = row.checked, onCheckedChange = null, enabled = !row.locked)
        Column(modifier = Modifier.weight(1f)) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.Start),
            ) {
                Text(row.format.label(row.textName), style = MaterialTheme.typography.bodyLarge)
                row.bytes?.let {
                    Text(
                        text = fileSize(it).let { size ->
                            if (row.estimated) stringResource(R.string.download_sheet_size_estimated, size) else size
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
            if (row.updateAvailable) {
                Text(
                    text = stringResource(R.string.download_sheet_update_available),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                text = row.description(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** How far a format being fetched is; else what the format is for. */
@Composable
private fun SheetRow.description(): String = when {
    fetching -> progress?.let { stringResource(R.string.download_sheet_fetching_percent, it) }
        ?: stringResource(R.string.download_sheet_fetching)
    format == DownloadFormat.Text -> stringResource(
        when {
            locked && downloaded -> R.string.download_sheet_text_locked
            inNarration -> R.string.download_sheet_text_in_narration
            else -> R.string.download_sheet_text
        },
    )
    format == DownloadFormat.Audiobook -> chapterCount
        ?.let { pluralStringResource(R.plurals.download_sheet_audiobook_chapters, it, it) }
        ?: stringResource(R.string.download_sheet_audiobook)
    else -> stringResource(R.string.download_sheet_narration)
}
