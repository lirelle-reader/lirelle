package app.tfdn.lirelle.feature.library.series

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import app.tfdn.lirelle.core.downloads.nameRes
import app.tfdn.lirelle.core.ui.fileSize
import app.tfdn.lirelle.feature.library.R

const val SeriesSheetSkipTag = "series-sheet-skip"
const val SeriesSheetLoadingTag = "series-sheet-loading"

fun seriesFormatRowTag(format: DownloadFormat) = "series-format-row-${format.name}"

private const val PlaceholderRows = 3

/**
 * One format of the sheet: its name, how many of the candidates would receive it, and what they take together,
 * as about that much when a size of the sum is an estimate.
 */
@Composable
internal fun SeriesFormatRowItem(row: SeriesFormatRow, onToggle: () -> Unit) {
    val count = booksOf(row.books, row.of)
    val size = fileSize(row.bytes)
    CheckRow(
        tag = seriesFormatRowTag(row.format),
        checked = row.checked,
        enabled = !row.locked,
        onToggle = { onToggle() },
        name = stringResource(nameRes(row.format, row.textName)),
        // Nothing to say of the size of no book, nor of sizes nobody knows.
        size = when {
            row.bytes <= 0 -> null
            row.estimated -> stringResource(R.string.series_download_size_estimated, size)
            else -> size
        },
        note = if (row.format == DownloadFormat.Narration) {
            stringResource(R.string.series_download_includes_text, count)
        } else {
            count
        },
    )
}

/** Whether the finished books are left out; absent when no book of the series is finished. */
@Composable
internal fun SeriesSkipRow(finished: Int, checked: Boolean, onToggle: (Boolean) -> Unit) {
    if (finished == 0) return
    CheckRow(
        tag = SeriesSheetSkipTag,
        checked = checked,
        enabled = true,
        onToggle = onToggle,
        name = stringResource(R.string.series_download_skip),
        size = null,
        note = pluralStringResource(
            if (checked) R.plurals.series_download_skipped else R.plurals.series_download_finished,
            finished,
            finished,
        ),
    )
}

/** Rows that wait, while the page of each book is read. */
@Composable
internal fun SeriesSheetPlaceholders() {
    Column(modifier = Modifier.testTag(SeriesSheetLoadingTag), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(PlaceholderRows) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small),
            )
        }
    }
}

/**
 * A checkbox, the whole row being what is tapped and read; a name with a size, which goes under the name when
 * the two do not fit on a line; and a note under them.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CheckRow(
    tag: String,
    checked: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    name: String,
    size: String?,
    note: String,
) {
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag)
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onToggle)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Column(modifier = Modifier.weight(1f)) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.Start),
            ) {
                Text(name, style = MaterialTheme.typography.bodyLarge)
                size?.let { Text(it, color = quiet, style = MaterialTheme.typography.bodyLarge) }
            }
            Text(note, color = quiet, style = MaterialTheme.typography.bodySmall)
        }
    }
}
