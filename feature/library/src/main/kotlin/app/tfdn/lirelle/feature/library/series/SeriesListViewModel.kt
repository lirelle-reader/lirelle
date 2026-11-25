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
import kotlinx.coroutines.launch

/** [series] is already sorted and filtered. */
data class SeriesListUiState(
    val series: Loadable<List<Series>> = Loadable.Loading,
    val sort: SeriesSort = SeriesSort.Name,
    val direction: SortDirection = SortDirection.Ascending,
    val filter: SeriesProgressFilter? = null,
    val refreshing: Boolean = false,
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
    private val filter = MutableStateFlow<SeriesProgressFilter?>(null)
    private val refreshing = MutableStateFlow(false)

    /** The library the profile chose, null for all. */
    private val library: Flow<ServerLibrary?> = choice.state.map { it.chosen }.distinctUntilChanged()

    val state: StateFlow<SeriesListUiState> = combine(
        loaded,
        preferences.view(PreferencesKey),
        filter,
        refreshing,
    ) { loaded, prefs, filter, refreshing ->
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
        SeriesListUiState(arranged, sort, direction, filter, refreshing)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SeriesListUiState())

    init {
        // The first load, and one more for each other library chosen, or the same one under a new name. A load
        // still under way for the library left is dropped.
        viewModelScope.launch { library.collectLatest(::read) }
    }

    /** A retry: the list is replaced by the loading state. */
    fun load() {
        viewModelScope.launch { read(library.first()) }
    }

    /** The series of [library] from their start: the list of another library does not stay on screen. */
    private suspend fun read(library: ServerLibrary?) {
        loaded.value = Loadable.Loading
        loaded.value = repository.series(library = library).toLoadable()
    }

    /**
     * Pull to refresh: the list stays; a failed refresh leaves it as it was. An answer that comes after another
     * library was chosen is dropped: it is about the library left.
     */
    fun refresh() {
        viewModelScope.launch {
            refreshing.value = true
            val asked = library.first()
            val result = repository.series(library = asked)
            val current = asked == library.first()
            if (current && (result is ApiResult.Success || loaded.value !is Loadable.Ready)) {
                loaded.value = result.toLoadable()
            }
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
