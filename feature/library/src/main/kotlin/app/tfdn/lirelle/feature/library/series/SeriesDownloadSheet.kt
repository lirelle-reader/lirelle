package app.tfdn.lirelle.feature.library.series

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.designsystem.SingleLineLabel
import app.tfdn.lirelle.core.designsystem.WordsLabel
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.ui.FailedState
import app.tfdn.lirelle.core.ui.GripSheet
import app.tfdn.lirelle.core.ui.Loadable
import app.tfdn.lirelle.core.ui.fileSize
import app.tfdn.lirelle.feature.library.R

const val SeriesSheetTag = "series-sheet"
const val SeriesSheetConfirmTag = "series-sheet-confirm"
const val SeriesSheetCancelTag = "series-sheet-cancel"

private val ChoiceSaver = listSaver<Set<DownloadFormat>, String>(
    save = { chosen -> chosen.map { it.name } },
    restore = { names -> names.mapTo(mutableSetOf()) { DownloadFormat.valueOf(it) } },
)

/**
 * The sheet of the series page: which formats of its books to download.
 *
 * @param onRetry reads the pages of the books again, after one could not be read.
 * @param onConfirm asks for the formats chosen, finished books left out or not; the caller closes the sheet.
 */
@Composable
fun SeriesDownloadSheet(
    sheet: SeriesSheetState,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onConfirm: (chosen: Set<DownloadFormat>, skipFinished: Boolean) -> Unit,
) {
    GripSheet(onDismissRequest = onDismiss) {
        SeriesDownloadSheetContent(sheet, onCancel = onDismiss, onRetry = onRetry, onConfirm = onConfirm)
    }
}

/**
 * What the sheet holds: while the pages of the books are read, rows that wait; then a row to check for each
 * format, the finished books to skip, the space left, and a button that says what it will do.
 */
@Composable
fun SeriesDownloadSheetContent(
    sheet: SeriesSheetState,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onConfirm: (chosen: Set<DownloadFormat>, skipFinished: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var chosen by rememberSaveable(stateSaver = ChoiceSaver) { mutableStateOf(sheet.chosen) }
    var skipFinished by rememberSaveable { mutableStateOf(true) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(SeriesSheetTag)
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Heading(sheet)
        when (val offers = sheet.offers) {
            Loadable.Loading -> SeriesSheetPlaceholders()
            is Loadable.Failed -> FailedState(offers.error, onRetry)
            is Loadable.Ready -> {
                val plan = seriesPlan(offers.value, chosen, skipFinished)
                plan.rows.forEach { row ->
                    SeriesFormatRowItem(row, onToggle = { chosen = chosen.seriesToggled(row.format, row.locked) })
                }
                SeriesSkipRow(offers.value.count { it.finished }, skipFinished, onToggle = { skipFinished = it })
                Surroundings(sheet, plan)
                ConfirmAction(plan, onClick = { onConfirm(chosen, skipFinished) })
            }
        }
        TextButton(
            onClick = onCancel,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(SeriesSheetCancelTag),
        ) { SingleLineLabel(stringResource(R.string.series_download_cancel)) }
    }
}

/** "Download the series", then its name, how many books it has and how many the device has already. */
@Composable
private fun Heading(sheet: SeriesSheetState) {
    Text(
        text = stringResource(R.string.series_download_button),
        modifier = Modifier.semantics { heading() },
        style = MaterialTheme.typography.titleLarge,
    )
    val books = pluralStringResource(R.plurals.books_count, sheet.books, sheet.books)
    Note(
        if (sheet.onDevice > 0) {
            stringResource(R.string.series_download_subtitle_kept, sheet.name, books, sheet.onDevice)
        } else {
            stringResource(R.string.series_download_subtitle, sheet.name, books)
        },
    )
}

/**
 * The space left, the network that will make the downloads wait, and how much space is missing when the sum is
 * larger than what is left: the button stays, each format then failing or succeeding on its own.
 */
@Composable
private fun Surroundings(sheet: SeriesSheetState, plan: SeriesPlan) {
    Note(stringResource(R.string.series_download_free, fileSize(sheet.freeBytes)))
    if (sheet.waitsForWifi) Note(stringResource(R.string.series_download_metered))
    if (plan.bytes > sheet.freeBytes) {
        Note(
            text = stringResource(R.string.series_download_missing, fileSize(plan.bytes - sheet.freeBytes)),
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun Note(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, color = color, style = MaterialTheme.typography.bodyMedium)
}

/** Says what it will do: how many books receive something, and what they take; disabled when none does. */
@Composable
private fun ConfirmAction(plan: SeriesPlan, onClick: () -> Unit) {
    val label = when {
        plan.books == 0 -> stringResource(R.string.series_download_nothing)
        plan.bytes > 0 ->
            pluralStringResource(R.plurals.series_download_confirm_size, plan.books, plan.books, fileSize(plan.bytes))
        else -> pluralStringResource(R.plurals.books_count, plan.books, plan.books)
    }
    Button(
        onClick = onClick,
        enabled = plan.books > 0,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(SeriesSheetConfirmTag)
            .semantics { contentDescription = label },
    ) { WordsLabel(label) }
}
