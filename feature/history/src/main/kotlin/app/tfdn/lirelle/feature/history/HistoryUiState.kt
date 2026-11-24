package app.tfdn.lirelle.feature.history

import app.tfdn.lirelle.core.history.model.HistoryCapabilities
import app.tfdn.lirelle.core.history.model.HistoryPeriod
import app.tfdn.lirelle.core.history.model.MonthBucket
import app.tfdn.lirelle.core.history.model.ReadOutcome
import app.tfdn.lirelle.core.history.model.ReadRecord
import app.tfdn.lirelle.core.history.model.ReadingSummary
import java.time.Duration
import java.time.YearMonth

/** One month of the log: its totals and its rows. A null [month] groups the reads that have no end date. */
data class MonthSection(val month: YearMonth?, val totals: MonthBucket, val rows: List<ReadRecord>)

sealed interface SummaryUi {
    data object Loading : SummaryUi

    /** The source has no summary. */
    data object Hidden : SummaryUi
    data class Shown(val summary: ReadingSummary) : SummaryUi
    data object Error : SummaryUi
}

sealed interface LogUi {
    data object Loading : LogUi

    /** Nothing under the current filter and period. */
    data object Empty : LogUi
    data class Loaded(val sections: List<MonthSection>, val canLoadMore: Boolean) : LogUi
    data object Error : LogUi
}

/**
 * @param years the period chips that follow "All time".
 * @param outcomes the filter: never empty.
 * @param loadMoreError the last request for a further page failed; the loaded rows stay.
 */
data class HistoryUiState(
    val capabilities: HistoryCapabilities,
    val years: List<Int>,
    val period: HistoryPeriod,
    val outcomes: Set<ReadOutcome>,
    val summary: SummaryUi,
    val log: LogUi,
    val loadingMore: Boolean,
    val loadMoreError: Boolean = false,
)

/**
 * Groups [records] into one section per run of the same end month, keeping their order. A month's totals come
 * from [months]; a month it does not list (one that only a later page reaches) gets totals made from its rows,
 * with no page count.
 */
fun sections(records: List<ReadRecord>, months: List<MonthBucket>?): List<MonthSection> {
    val known = months.orEmpty().associateBy { it.month }
    val runs = mutableListOf<Pair<YearMonth?, MutableList<ReadRecord>>>()
    for (record in records) {
        val month = record.endedOn?.let(YearMonth::from)
        val last = runs.lastOrNull()
        if (last != null && last.first == month) {
            last.second += record
        } else {
            runs += month to mutableListOf(record)
        }
    }
    return runs.map { (month, rows) ->
        val totals = known[month] ?: MonthBucket(
            month = month,
            reads = rows.size,
            pages = 0,
            duration = rows.fold(Duration.ZERO) { sum, row -> sum + (row.duration ?: Duration.ZERO) },
        )
        MonthSection(month, totals, rows)
    }
}
