package app.tfdn.lirelle.feature.library.series

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tfdn.lirelle.core.library.LibraryChoice
import app.tfdn.lirelle.core.library.LibraryPreferences
import app.tfdn.lirelle.core.library.LibraryRepository
import app.tfdn.lirelle.core.library.model.Series
import app.tfdn.lirelle.core.library.model.SeriesProgressFilter
import app.tfdn.lirelle.core.library.model.SeriesSort
import app.tfdn.lirelle.core.library.model.ServerLibrary
import app.tfdn.lirelle.core.library.model.SortDirection
import app.tfdn.lirelle.core.library.model.arranged
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.ui.Loadable
import app.tfdn.lirelle.core.ui.toLoadable
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** [series] is already sorted and filtered; they are those of [library], null for every library. */
data class SeriesListUiState(
    val series: Loadable<List<Series>> = Loadable.Loading,
    val sort: SeriesSort = SeriesSort.Name,
    val direction: SortDirection = SortDirection.Ascending,
    val filter: SeriesProgressFilter? = null,
    val refreshing: Boolean = false,
    val library: ServerLibrary? = null,
)

/** A name reads best from A; the counts and dates are looked at from the largest. */
internal val SeriesSort.defaultDirection: SortDirection
    get() = if (this == SeriesSort.Name) SortDirection.Ascending else SortDirection.Descending

private const val PreferencesKey = "series"

@HiltViewModel
class SeriesListViewModel @Inject constructor(
    private val repository: LibraryRepository,
    private val preferences: LibraryPreferences,
    choice: LibraryChoice,
) : ViewModel() {

    private val loaded = MutableStateFlow<Loadable<List<Series>>>(Loadable.Loading)

    /** The library [loaded] is of. */
    private val shown = MutableStateFlow<ServerLibrary?>(null)
    private val filter = MutableStateFlow<SeriesProgressFilter?>(null)
    private val refreshing = MutableStateFlow(false)

    /** The library the profile chose, null for all. */
    private val library: Flow<ServerLibrary?> = choice.state.map { it.chosen }.distinctUntilChanged()

    val state: StateFlow<SeriesListUiState> = combine(
        loaded,
        shown,
        preferences.view(PreferencesKey),
        filter,
        refreshing,
    ) { loaded, shown, prefs, filter, refreshing ->
        val sort = prefs.sort?.let { name -> SeriesSort.entries.firstOrNull { it.name == name } } ?: SeriesSort.Name
        val direction = when (prefs.descending) {
            null -> sort.defaultDirection
            true -> SortDirection.Descending
            false -> SortDirection.Ascending
        }
        val arranged = when (loaded) {
            is Loadable.Ready -> Loadable.Ready(loaded.value.arranged(sort, direction, filter))
            else -> loaded
        }
        SeriesListUiState(arranged, sort, direction, filter, refreshing, shown)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SeriesListUiState())

    /** One more for each retry. */
    private val retries = MutableStateFlow(0)
    private var refresh: Job? = null

    init {
        // The first load, and one more for each retry and each other library chosen, or the same one under a
        // new name. What was under way is dropped, a refresh too: its answer would be about the list left, and
        // it is not shown over the load.
        viewModelScope.launch {
            combine(library, retries) { library, _ -> library }.collectLatest { library ->
                refresh?.cancel()
                refreshing.value = false
                loaded.value = Loadable.Loading
                shown.value = library
                loaded.value = repository.series(library = library).toLoadable()
            }
        }
    }

    /** A retry: the list is replaced by the loading state. */
    fun load() = retries.update { it + 1 }

    /** Pull to refresh: the list stays; a failed refresh leaves it as it was. */
    fun refresh() {
        refresh?.cancel()
        refresh = viewModelScope.launch {
            refreshing.value = true
            val result = repository.series(library = library.first())
            if (result is ApiResult.Success || loaded.value !is Loadable.Ready) loaded.value = result.toLoadable()
            refreshing.value = false
        }
    }

    fun onSort(sort: SeriesSort) {
        val current = state.value
        val descending = if (sort == current.sort) {
            current.direction == SortDirection.Ascending
        } else {
            sort.defaultDirection == SortDirection.Descending
        }
        viewModelScope.launch {
            preferences.update(PreferencesKey) { it.copy(sort = sort.name, descending = descending) }
        }
    }

    fun onFilter(filter: SeriesProgressFilter?) {
        this.filter.value = filter
    }
}
