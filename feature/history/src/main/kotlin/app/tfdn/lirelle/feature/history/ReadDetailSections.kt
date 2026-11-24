package app.tfdn.lirelle.feature.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.designsystem.SingleLineLabel
import app.tfdn.lirelle.core.history.model.HistoryCapabilities
import app.tfdn.lirelle.core.history.model.ReadMarks
import app.tfdn.lirelle.core.history.model.ReadRecord
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import app.tfdn.lirelle.core.ui.R as UiR

private const val MaxHighlights = 3
private const val MaxNotes = 2

private class Cell(val label: String, val value: String)

@Composable
internal fun MetadataGrid(record: ReadRecord, capabilities: HistoryCapabilities) {
    val locale = LocalConfiguration.current.locales[0]
    val formatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
    val cells = buildList {
        dateRange(record.startedOn, record.endedOn, formatter)?.let {
            add(Cell(stringResource(R.string.read_detail_dates), it))
        }
        record.duration?.takeIf { capabilities.durations }?.let {
            add(Cell(stringResource(R.string.read_detail_time), durationText(it)))
        }
        record.readNumber?.takeIf { capabilities.rereads }?.let {
            add(Cell(stringResource(R.string.read_detail_read_number), it.toString()))
        }
        record.sessions?.takeIf { capabilities.sessions }?.let {
            add(Cell(stringResource(R.string.read_detail_sessions), it.toString()))
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        cells.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { CellView(it, Modifier.weight(1f)) }
                if (pair.size == 1) Box(modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun dateRange(start: LocalDate?, end: LocalDate?, formatter: DateTimeFormatter): String? = when {
    start != null && end != null -> stringResource(
        R.string.read_detail_dates_range,
        formatter.format(start),
        formatter.format(end),
    )
    start != null -> formatter.format(start)
    end != null -> formatter.format(end)
    else -> null
}

@Composable
private fun CellView(cell: Cell, modifier: Modifier) {
    Column(modifier = modifier) {
        Text(
            text = cell.label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = cell.value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
internal fun MarksSections(marks: MarksUi, onRetry: () -> Unit) {
    when (marks) {
        MarksUi.Hidden -> Unit
        MarksUi.Loading -> Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        MarksUi.Error -> Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(UiR.string.library_error_unreachable),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyLarge,
            )
            OutlinedButton(onClick = onRetry) { SingleLineLabel(stringResource(UiR.string.state_retry)) }
        }
        is MarksUi.Loaded -> LoadedMarks(marks.marks)
    }
}

@Composable
private fun LoadedMarks(marks: ReadMarks) {
    if (marks.highlightCount > 0) {
        MarkSection(
            title = stringResource(R.string.read_detail_highlights),
            count = pluralStringResource(R.plurals.history_highlight_count, marks.highlightCount, marks.highlightCount),
            entries = marks.highlights.take(MaxHighlights).map { it.text to it.chapterTitle },
        )
    }
    if (marks.journalCount > 0) {
        MarkSection(
            title = stringResource(R.string.read_detail_journal),
            count = pluralStringResource(R.plurals.history_journal_count, marks.journalCount, marks.journalCount),
            entries = marks.journal.take(MaxNotes).map { it.body to it.chapterTitle },
        )
    }
}

@Composable
private fun MarkSection(title: String, count: String, entries: List<Pair<String, String?>>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = count,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        entries.forEach { (text, chapter) ->
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(text = text, style = MaterialTheme.typography.bodyMedium)
                chapter?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
