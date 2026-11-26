package app.tfdn.lirelle.feature.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tfdn.lirelle.core.library.Freshness
import app.tfdn.lirelle.core.library.LibraryRepository
import app.tfdn.lirelle.core.library.MonotonicClock
import app.tfdn.lirelle.core.library.RecentSearches
import app.tfdn.lirelle.core.library.SourceCapabilities
import app.tfdn.lirelle.core.library.model.Genre
import app.tfdn.lirelle.core.library.model.SearchKind
import app.tfdn.lirelle.core.library.model.SearchResults
import app.tfdn.lirelle.core.library.model.SearchSection
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.ui.Loadable
import app.tfdn.lirelle.core.ui.toLoadable
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TextKey = "text"
private const val MinLength = 2
private const val DebounceMillis = 300L

/**
 * @property text what the field holds, as typed.
 * @property results null while there is nothing to search for.
 * @property showSeriesCounts whether a series found shows its number of books: only when the source gives it.
 */
data class SearchUiState(
    val text: String = "",
    val results: Loadable<SearchResults>? = null,
    val recent: List<String> = emptyList(),
    val genres: List<Genre> = emptyList(),
    val showSeriesCounts: Boolean = true,
)

/**
 * Author results need the source to offer both that kind of result and author pages: a found author opens
 * one, and the full list of authors found is a list of them.
 */
internal val SourceCapabilities.authorResults: Boolean get() = authors && SearchKind.Authors in searchKinds

/**
 * Only the sections the source offers: one it does not is emptied, so the screen leaves it out, and results with
 * nothing else read as nothing found. The server is asked once for everything (one search), so this is a filter.
 */
internal fun SearchResults.offered(capabilities: SourceCapabilities) = copy(
    books = books.takeIf { SearchKind.Books in capabilities.searchKinds } ?: SearchSection(),
    series = series.takeIf { SearchKind.Series in capabilities.searchKinds } ?: SearchSection(),
    authors = authors.takeIf { capabilities.authorResults } ?: SearchSection(),
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val saved: SavedStateHandle,
    private val repository: LibraryRepository,
    private val recent: RecentSearches,
    clock: MonotonicClock,
    private val capabilities: SourceCapabilities,
) : ViewModel() {
    private val attempts = MutableStateFlow(0)

    // One flow written in place, not a combination of several: the field reads back what was
    // typed at once, where a combination would answer a moment later and drop fast keystrokes.
    private val mutableState = MutableStateFlow(
        SearchUiState(text = saved[TextKey] ?: "", showSeriesCounts = capabilities.seriesCounts),
    )
    val state: StateFlow<SearchUiState> = mutableState.asStateFlow()

    private val freshness = Freshness(clock)
    private var genresLoaded = false
    private var genresJob: Job? = null

    init {
        viewModelScope.launch {
            recent.entries.collect { entries -> mutableState.update { it.copy(recent = entries) } }
        }
        // A source without genres is not asked for them, and none is suggested.
        if (capabilities.genres) loadGenres()
        viewModelScope.launch {
            saved.getStateFlow(TextKey, "")
                .map { it.trim() }
                .distinctUntilChanged()
                .combine(attempts) { query, _ -> query }
                // A newer text, or a retry, cancels the wait and the request of the one before.
                .collectLatest(::search)
        }
    }

    private suspend fun search(query: String) {
        if (query.length < MinLength) {
            show(null)
            return
        }
        delay(DebounceMillis)
        // Results already on screen stay until the new ones arrive: no flicker while typing.
        if (state.value.results !is Loadable.Ready) show(Loadable.Loading)
        val answer = when (val found = repository.search(query).toLoadable()) {
            is Loadable.Ready -> Loadable.Ready(found.value.offered(capabilities))
            else -> found
        }
        // An answer that comes back although the search was cancelled is not shown.
        currentCoroutineContext().ensureActive()
        show(answer)
    }

    private fun show(results: Loadable<SearchResults>?) = mutableState.update { it.copy(results = results) }

    fun onText(value: String) {
        saved[TextKey] = value
        mutableState.update { it.copy(text = value) }
    }

    fun onRetry() = attempts.update { it + 1 }

    /** Search is in front again: ask the genres again if they never came, or if they are old. */
    fun onShown() {
        if (!capabilities.genres || genresJob?.isActive == true) return
        if (!genresLoaded || freshness.isStale()) loadGenres()
    }

    private fun loadGenres() {
        genresJob = viewModelScope.launch {
            // A failure is not said: the screen goes without its genres, or keeps those it has.
            val answer = repository.genres()
            if (answer is ApiResult.Success) {
                genresLoaded = true
                freshness.markLoaded()
                mutableState.update { it.copy(genres = answer.value) }
            }
        }
    }

    /**
     * The user followed a result or a "See all": the text was worth searching. It is the text the
     * results on screen are for, which is not the text of the field while a newer search runs.
     */
    fun onResultOpened() {
        val shown = state.value
        val text = (shown.results as? Loadable.Ready)?.value?.text ?: shown.text.trim()
        viewModelScope.launch { recent.record(text) }
    }

    fun onRemoveRecent(entry: String) {
        viewModelScope.launch { recent.remove(entry) }
    }

    fun onClearRecent() {
        viewModelScope.launch { recent.clear() }
    }
}
