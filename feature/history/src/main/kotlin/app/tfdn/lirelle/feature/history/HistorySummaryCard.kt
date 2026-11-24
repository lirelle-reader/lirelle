package app.tfdn.lirelle.feature.history

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.history.model.ReadingGoal
import app.tfdn.lirelle.core.history.model.ReadingRecord
import app.tfdn.lirelle.core.history.model.ReadingSummary
import app.tfdn.lirelle.core.history.model.ReadingTotals
import app.tfdn.lirelle.core.history.model.RecordKind
import java.time.Duration

private val ThreeColumnThreshold = 260.dp
private val RingSize = 56.dp
private const val NoValue = "—"
private const val SecondsPerMinute = 60L
private const val ThreeColumns = 3
private const val TwoColumns = 2
private const val MaxFontScaleForThree = 1.3f

@Composable
internal fun SummaryCard(summary: ReadingSummary) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        val goal = summary.goal
        val sideBySide = goal != null && LocalConfiguration.current.fontScale <= MaxFontScaleForThree
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (sideBySide) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GoalRing(goal)
                    TotalsGrid(summary.totals, Modifier.weight(1f))
                }
            } else {
                goal?.let { GoalRing(it) }
                TotalsGrid(summary.totals)
            }
            goal?.let {
                Text(
                    text = stringResource(R.string.history_goal, it.reached, it.target),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (summary.records.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                summary.records.forEach { RecordLine(it) }
            }
        }
    }
}

@Composable
private fun GoalRing(goal: ReadingGoal) {
    val fraction = if (goal.target > 0) (goal.reached.toFloat() / goal.target).coerceIn(0f, 1f) else 0f
    CircularProgressIndicator(
        progress = { fraction },
        modifier = Modifier.size(RingSize),
        color = MaterialTheme.colorScheme.primary,
        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    )
}

@Composable
private fun TotalsGrid(totals: ReadingTotals, modifier: Modifier = Modifier) {
    val configuration = LocalConfiguration.current
    val locale = configuration.locales[0]
    val cells = listOf(
        R.string.history_total_reads to totals.reads.toString(),
        R.string.history_total_books to totals.books.toString(),
        R.string.history_total_pages to totals.pages.toString(),
        R.string.history_total_time to durationText(totals.duration),
        R.string.history_total_days to totals.daysRead.toString(),
        R.string.history_total_rating to (totals.averageRating?.let { String.format(locale, "%.1f", it) } ?: NoValue),
    )
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val roomy = maxWidth >= ThreeColumnThreshold && configuration.fontScale <= MaxFontScaleForThree
        val columns = if (roomy) ThreeColumns else TwoColumns
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            cells.chunked(columns).forEach { line ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    line.forEach { (label, value) ->
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = value, style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = stringResource(label),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    repeat(columns - line.size) { Spacer(modifier = Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun RecordLine(record: ReadingRecord) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Text(
            text = stringResource(recordLabel(record.kind)),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.4f),
        )
        FlowRow(modifier = Modifier.weight(0.6f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text = record.title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = recordValue(record),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.End,
            )
        }
    }
}

private fun recordLabel(kind: RecordKind): Int = when (kind) {
    RecordKind.LongestBook -> R.string.history_record_longest_book
    RecordKind.MostReread -> R.string.history_record_most_reread
    RecordKind.LongestRead -> R.string.history_record_longest_read
    RecordKind.Fastest -> R.string.history_record_fastest
}

/** The record's value in its unit: pages, a count of reads, calendar days spanned, or pages per day. */
@Composable
private fun recordValue(record: ReadingRecord): String {
    val count = Math.round(record.value).toInt()
    return when (record.kind) {
        RecordKind.LongestBook -> pluralStringResource(R.plurals.history_record_pages, count, count)
        RecordKind.MostReread -> pluralStringResource(R.plurals.history_record_times, count, count)
        RecordKind.LongestRead -> pluralStringResource(R.plurals.history_record_days, count, count)
        RecordKind.Fastest -> stringResource(R.string.history_record_rate, count)
    }
}

/** Whole hours, or minutes below the hour. */
@Composable
internal fun durationText(duration: Duration): String {
    val hours = duration.toHours().toInt()
    return if (hours > 0 || duration.seconds < SecondsPerMinute) {
        stringResource(R.string.history_duration_hours, hours)
    } else {
        stringResource(R.string.history_duration_minutes, duration.toMinutes().toInt())
    }
}
