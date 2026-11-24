package app.tfdn.lirelle.feature.history

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tfdn.lirelle.core.designsystem.SingleLineLabel
import app.tfdn.lirelle.core.history.model.HistoryPeriod
import app.tfdn.lirelle.core.history.model.ReadOutcome
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.ReadId

private val ContentMaxWidth = 600.dp

@Composable
fun HistoryRoute(
    onOpenRead: (readId: ReadId, bookId: BookId?) -> Unit,
    onBack: () -> Unit,
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    HistoryScreen(
        state = state,
        onSelectPeriod = viewModel::selectPeriod,
        onToggleOutcome = viewModel::toggleOutcome,
        onLoadMore = viewModel::loadMore,
        onOpenRead = onOpenRead,
        onBack = onBack,
        onRetrySummary = viewModel::retrySummary,
        onRetryLog = viewModel::retryLog,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    state: HistoryUiState,
    onSelectPeriod: (HistoryPeriod) -> Unit,
    onToggleOutcome: (ReadOutcome) -> Unit,
    onLoadMore: () -> Unit,
    onOpenRead: (readId: ReadId, bookId: BookId?) -> Unit,
    onBack: () -> Unit,
    onRetrySummary: () -> Unit,
    onRetryLog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.history_title),
                        modifier = Modifier.semantics { heading() },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.history_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                modifier = Modifier.widthIn(max = ContentMaxWidth),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                item(key = "periods") { PeriodChips(state, onSelectPeriod) }
                summaryItems(state, onRetrySummary)
                if (state.capabilities.outcomes) {
                    item(key = "outcomes") { OutcomeChips(state.outcomes, onToggleOutcome) }
                }
                logItems(state, onLoadMore, onOpenRead, onRetryLog)
            }
        }
    }
}

@Composable
private fun PeriodChips(state: HistoryUiState, onSelect: (HistoryPeriod) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val periods = listOf<Int?>(null) + state.years
        periods.forEach { year ->
            FilterChip(
                selected = state.period.year == year,
                onClick = { onSelect(HistoryPeriod(year)) },
                label = {
                    SingleLineLabel(
                        text = year?.toString() ?: stringResource(R.string.history_period_all),
                    )
                },
            )
        }
    }
}

@Composable
private fun OutcomeChips(selected: Set<ReadOutcome>, onToggle: (ReadOutcome) -> Unit) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ReadOutcome.entries.forEach { outcome ->
            FilterChip(
                selected = outcome in selected,
                onClick = { onToggle(outcome) },
                label = { SingleLineLabel(outcomeLabel(outcome)) },
            )
        }
    }
}

private fun LazyListScope.summaryItems(state: HistoryUiState, onRetry: () -> Unit) {
    when (val summary = state.summary) {
        SummaryUi.Hidden -> Unit
        SummaryUi.Loading -> item(key = "summary") { LoadingBlock() }
        SummaryUi.Error -> item(key = "summary") { InlineError(onRetry) }
        is SummaryUi.Shown -> item(key = "summary") { SummaryCard(summary.summary) }
    }
}

private fun LazyListScope.logItems(
    state: HistoryUiState,
    onLoadMore: () -> Unit,
    onOpenRead: (ReadId, BookId?) -> Unit,
    onRetryLog: () -> Unit,
) {
    when (val log = state.log) {
        LogUi.Loading -> item(key = "log") { LoadingBlock() }
        LogUi.Error -> item(key = "log") { InlineError(onRetryLog) }
        LogUi.Empty -> item(key = "log") {
            val narrowed = state.outcomes != setOf(ReadOutcome.Completed) || state.period.year != null
            EmptyBlock(stringResource(if (narrowed) R.string.history_empty_filtered else R.string.history_empty))
        }
        is LogUi.Loaded -> {
            log.sections.forEachIndexed { index, section ->
                item(key = "month-$index-${section.month}") { MonthHeader(section, state.capabilities) }
                // A key a Bundle can hold, apart from the other items' keys whatever the id's text.
                items(section.rows.size, key = { "read-${section.rows[it].id.value}" }) { index ->
                    ReadRow(section.rows[index], state.capabilities, onOpenRead)
                }
            }
            if (log.canLoadMore) {
                item(key = "more") { MoreItem(state, log, onLoadMore) }
            }
        }
    }
}

@Composable
internal fun outcomeLabel(outcome: ReadOutcome): String = stringResource(
    when (outcome) {
        ReadOutcome.Completed -> R.string.history_outcome_completed
        ReadOutcome.Skimmed -> R.string.history_outcome_skimmed
        ReadOutcome.Abandoned -> R.string.history_outcome_abandoned
    },
)
