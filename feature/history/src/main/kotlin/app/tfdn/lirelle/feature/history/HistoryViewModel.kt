package app.tfdn.lirelle.feature.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tfdn.lirelle.core.history.HistoryRepository
import app.tfdn.lirelle.core.history.RatingUpdate
import app.tfdn.lirelle.core.history.ReadRecordCache
import app.tfdn.lirelle.core.history.model.HistoryPeriod
import app.tfdn.lirelle.core.history.model.MonthBucket
import app.tfdn.lirelle.core.history.model.ReadOutcome
import app.tfdn.lirelle.core.history.model.ReadRecord
import app.tfdn.lirelle.core.network.ApiResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val PageLimit = 30

/** The reading history: a period, an outcome filter, a summary and a log read page by page. */
@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val repo: HistoryRepository,
    private val cache: ReadRecordCache,
) : ViewModel() {

    private val mutableState = MutableStateFlow(
        HistoryUiState(
            capabilities = repo.capabilities,
            years = emptyList(),
            period = HistoryPeriod(null),
            outcomes = setOf(ReadOutcome.Completed),
            summary = if (repo.capabilities.summary) SummaryUi.Loading else SummaryUi.Hidden,
            log = LogUi.Loading,
            loadingMore = false,
            loadMoreError = false,
        ),
    )
    val state: StateFlow<HistoryUiState> = mutableState.asStateFlow()

    private var records = emptyList<ReadRecord>()

    /** How many rows the server sent so far, a repeated one included: where the next page starts. */
    private var fetched = 0
    private var months: List<MonthBucket>? = null
    private var total = 0
    private var logJob: Job? = null

    init {
        loadSummary()
        loadLog()
        viewModelScope.launch { cache.ratingUpdates.collect(::applyRating) }
    }

    fun selectPeriod(period: HistoryPeriod) {
        mutableState.update { it.copy(period = period) }
        loadSummary()
        loadLog()
    }

    /** Flips [outcome] in the filter. The last one stays: the filter is never empty. */
    fun toggleOutcome(outcome: ReadOutcome) {
        mutableState.update {
            val next = if (outcome in it.outcomes) it.outcomes - outcome else it.outcomes + outcome
            it.copy(outcomes = next.ifEmpty { setOf(ReadOutcome.Completed) })
        }
        loadLog()
    }

    fun loadMore() {
        val current = state.value
        val log = current.log
        if (log !is LogUi.Loaded || !log.canLoadMore || current.loadingMore) return
        mutableState.update { it.copy(loadingMore = true, loadMoreError = false) }
        logJob = viewModelScope.launch {
            val result = fetch(current, fetched)
            if (result is ApiResult.Success) {
                fetched += result.value.records.size
                // A read finished elsewhere meanwhile shifts the rows: one already shown may come again.
                val shown = records.mapTo(HashSet()) { it.id }
                records = records + result.value.records.filterNot { it.id in shown }
                mutableState.update { it.copy(log = loaded(), loadingMore = false, loadMoreError = false) }
            } else {
                mutableState.update { it.copy(loadingMore = false, loadMoreError = true) }
            }
        }
    }

    fun retrySummary() = loadSummary()

    fun retryLog() = loadLog()

    private fun loadSummary() {
        if (!repo.capabilities.summary) return
        mutableState.update { it.copy(summary = SummaryUi.Loading) }
        val period = state.value.period
        viewModelScope.launch {
            val result = repo.summary(period)
            if (state.value.period != period) return@launch
            mutableState.update {
                when (result) {
                    is ApiResult.Success -> it.copy(years = result.value.years, summary = SummaryUi.Shown(result.value))
                    is ApiResult.Failure -> it.copy(summary = SummaryUi.Error)
                }
            }
        }
    }

    private fun loadLog() {
        logJob?.cancel()
        mutableState.update { it.copy(log = LogUi.Loading, loadingMore = false, loadMoreError = false) }
        val request = state.value
        logJob = viewModelScope.launch {
            val result = fetch(request, 0)
            if (result is ApiResult.Success) {
                records = result.value.records
                fetched = records.size
                months = result.value.months
                total = result.value.total
                mutableState.update { it.copy(log = if (records.isEmpty()) LogUi.Empty else loaded()) }
            } else {
                mutableState.update { it.copy(log = LogUi.Error) }
            }
        }
    }

    private fun applyRating(update: RatingUpdate) {
        val index = records.indexOfFirst { it.id == update.id }
        if (index < 0) return
        records = records.toMutableList().also { it[index] = it[index].copy(rating = update.stars) }
        mutableState.update { if (it.log is LogUi.Loaded) it.copy(log = loaded(it.log.canLoadMore)) else it }
    }

    private suspend fun fetch(request: HistoryUiState, offset: Int) =
        repo.log(request.period, request.outcomes, newestFirst = true, offset = offset, limit = PageLimit)

    private fun loaded(canLoadMore: Boolean = fetched < total) =
        LogUi.Loaded(sections(records, months), canLoadMore)
}
