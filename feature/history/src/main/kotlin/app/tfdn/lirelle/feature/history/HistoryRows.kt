package app.tfdn.lirelle.feature.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.history.model.HistoryCapabilities
import app.tfdn.lirelle.core.history.model.ReadOutcome
import app.tfdn.lirelle.core.history.model.ReadRecord
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.ReadId
import app.tfdn.lirelle.core.library.model.CoverShape
import app.tfdn.lirelle.core.ui.BookCover
import java.time.Duration
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

private val CoverWidth = 56.dp
internal const val MaxStars = 5
private const val Separator = " · "

@Composable
internal fun MonthHeader(section: MonthSection, capabilities: HistoryCapabilities) {
    val locale = LocalConfiguration.current.locales[0]
    val name = section.month?.let { monthName(it, locale) } ?: stringResource(R.string.history_month_undated)
    val totals = section.totals
    val parts = buildList {
        add(pluralStringResource(R.plurals.history_reads_count, totals.reads, totals.reads))
        if (totals.pages > 0) add(pluralStringResource(R.plurals.history_pages_count, totals.pages, totals.pages))
        if (capabilities.durations && totals.duration > Duration.ZERO) add(durationText(totals.duration))
    }
    Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp)) {
        Text(
            text = name,
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = parts.joinToString(Separator),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun monthName(month: YearMonth, locale: Locale): String =
    month.format(DateTimeFormatter.ofPattern("LLLL yyyy", locale)).replaceFirstChar { it.titlecase(locale) }

@Composable
internal fun ReadRow(record: ReadRecord, capabilities: HistoryCapabilities, onOpen: (ReadId, BookId?) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen(record.id, record.bookId) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BookCover(
            url = record.coverUrl,
            title = record.title,
            shape = CoverShape.Portrait,
            modifier = Modifier.width(CoverWidth),
            showTitleWhenMissing = false,
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = record.title, style = MaterialTheme.typography.titleSmall)
            authorLine(record)?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            RowFacts(record, capabilities)
        }
    }
}

@Composable
private fun authorLine(record: ReadRecord): String? {
    val parts = buildList {
        if (record.authors.isNotEmpty()) add(record.authors.joinToString(", "))
        val number = record.readNumber
        if (number != null && number > 1) add(pluralStringResource(R.plurals.history_read_number, number, number))
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(Separator)
}

@Composable
private fun RowFacts(record: ReadRecord, capabilities: HistoryCapabilities) {
    FlowRow(
        modifier = Modifier.padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        OutcomePill(record.outcome)
        if (record.removed) Fact(stringResource(R.string.history_removed_short))
        record.rating?.takeIf { capabilities.rating }?.let { Stars(it) }
        record.duration?.takeIf { capabilities.durations }?.let { Fact(durationText(it)) }
        record.sessions?.takeIf { capabilities.sessions }?.let {
            Fact(pluralStringResource(R.plurals.history_session_count, it, it))
        }
        if (capabilities.marks) {
            record.highlightCount.takeIf { it > 0 }?.let {
                Fact(pluralStringResource(R.plurals.history_highlight_count, it, it))
            }
            record.journalCount.takeIf { it > 0 }?.let {
                Fact(pluralStringResource(R.plurals.history_journal_count, it, it))
            }
        }
    }
}

@Composable
private fun Fact(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun OutcomePill(outcome: ReadOutcome) {
    val scheme = MaterialTheme.colorScheme
    val (container, content) = remember(scheme, outcome) {
        when (outcome) {
            ReadOutcome.Completed -> scheme.tertiaryContainer to scheme.onTertiaryContainer
            ReadOutcome.Skimmed -> scheme.secondaryContainer to scheme.onSecondaryContainer
            ReadOutcome.Abandoned -> scheme.errorContainer to scheme.onErrorContainer
        }
    }
    Surface(shape = RoundedCornerShape(50), color = container, contentColor = content) {
        Text(
            text = outcomeLabel(outcome),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = Color.Unspecified,
        )
    }
}

@Composable
private fun Stars(rating: Int) {
    val description = stringResource(R.string.history_rating_description, rating)
    Row(modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = description }) {
        repeat(MaxStars) { index ->
            Icon(
                imageVector = if (index < rating) Icons.Filled.Star else Icons.Filled.StarBorder,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
