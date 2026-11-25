package app.tfdn.lirelle.feature.library.books

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import app.tfdn.lirelle.core.library.LibraryChoice
import app.tfdn.lirelle.core.library.LibraryPreferences
import app.tfdn.lirelle.core.library.LibraryRepository
import app.tfdn.lirelle.core.library.Presentation
import app.tfdn.lirelle.core.library.SourceCapabilities
import app.tfdn.lirelle.core.library.ViewPreferences
import app.tfdn.lirelle.core.library.model.BookFilters
import app.tfdn.lirelle.core.library.model.BookListRequest
import app.tfdn.lirelle.core.library.model.BookSort
import app.tfdn.lirelle.core.library.model.BookSummary
import app.tfdn.lirelle.core.library.model.FormatFilter
import app.tfdn.lirelle.core.library.model.ServerLibrary
import app.tfdn.lirelle.core.library.model.SortDirection
import app.tfdn.lirelle.core.library.model.StatusFilter
import app.tfdn.lirelle.core.network.ApiError
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BookListUiState(
    val request: BookListRequest = BookListRequest(),
    val presentation: Presentation = Presentation.Grid,
    val total: Int? = null,
    /** Whether the user set a filter, so there is something to clear. The status a list is about is not one. */
    val isFiltered: Boolean = false,
    /** Whether the list is about one reading status, which is then not offered as a filter. */
    val statusLocked: Boolean = false,
    /** What the source offers to sort and filter by. */
    val choices: BookListChoices = BookListChoices(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = BookListViewModel.Factory::class)
class BookListViewModel @AssistedInject constructor(
    @Assisted private val scope: BookListScope,
    private val repository: LibraryRepository,
    private val preferences: LibraryPreferences,
    capabilities: SourceCapabilities,
    choice: LibraryChoice,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(scope: BookListScope): BookListViewModel
    }

    /** What the source offers: what it does not is never asked for, whatever was stored or tapped. */
    private val choices = BookListChoices.of(capabilities)

    /** Each kind of list remembers its presentation and sort separately. */
    private val preferencesKey = scope.preferencesKey

    /** The filters the user set. The status the list is about is not among them: it is added to every request. */
    private val filters = MutableStateFlow(BookFilters())
    private val stored: Flow<ViewPreferences> = preferences.view(preferencesKey)

    /** The library the list is narrowed to: the profile's choice for a list that follows it, none for any other. */
    private val library: Flow<ServerLibrary?> =
        if (scope.followsLibrary) choice.state.map { it.chosen }.distinctUntilChanged() else flowOf(null)

    // Another library, or the same one under a new name, is another request: a new list, from its top.
    private val request: Flow<BookListRequest> = combine(stored, filters, library) { prefs, filters, library ->
        // A stored sort the source does not offer reads back as the default, in the default's own direction.
        val sort = prefs.sort?.let { name -> choices.sorts.firstOrNull { it.name == name } } ?: BookSort.Added
        val direction = when (prefs.descending.takeIf { sort.name == prefs.sort }) {
            null -> sort.defaultDirection
            true -> SortDirection.Descending
            false -> SortDirection.Ascending
        }
        BookListRequest(sort, direction, filters.withLockedStatus(), scope.text, scope.genre, library)
    }.distinctUntilChanged()

    private val lists = request
        .map { repository.books(scope.source, it) }
        .shareIn(viewModelScope, SharingStarted.Eagerly, replay = 1)

    val books: Flow<PagingData<BookSummary>> = lists.flatMapLatest { it.items }.cachedIn(viewModelScope)

    val state: StateFlow<BookListUiState> = combine(
        request,
        stored.map { it.presentation },
        lists.flatMapLatest { it.total },
    ) { request, presentation, total ->
        BookListUiState(
            request = request,
            presentation = presentation,
            total = total,
            isFiltered = request.filters != BookFilters().withLockedStatus(),
            statusLocked = scope.lockedStatus != null,
            choices = choices,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, BookListUiState(choices = choices))

    /** Choosing the current sort again reverses it; another sort starts in its natural direction. */
    fun onSort(sort: BookSort) {
        if (sort !in choices.sorts) return
        val current = state.value.request
        val descending = if (sort == current.sort) {
            current.direction == SortDirection.Ascending
        } else {
            sort.defaultDirection == SortDirection.Descending
        }
        viewModelScope.launch {
            preferences.update(preferencesKey) { it.copy(sort = sort.name, descending = descending) }
        }
    }

    fun onPresentation(presentation: Presentation) {
        viewModelScope.launch { preferences.update(preferencesKey) { it.copy(presentation = presentation) } }
    }

    fun onToggleFormat(format: FormatFilter) {
        if (format !in choices.formats) return
        filters.update { it.copy(formats = it.formats.toggled(format)) }
    }

    /** Does nothing for a list about one status, which does not offer this filter. */
    fun onToggleStatus(status: StatusFilter) {
        if (scope.lockedStatus != null || status !in choices.statuses) return
        filters.update { it.copy(statuses = it.statuses.toggled(status)) }
    }

    fun onReadAlongOnly(only: Boolean) {
        if (!choices.readAlong) return
        filters.update { it.copy(readAlongOnly = only) }
    }

    fun onClearFilters() {
        filters.value = BookFilters()
    }

    fun errorOf(throwable: Throwable): ApiError = repository.errorOf(throwable)

    private fun BookFilters.withLockedStatus(): BookFilters =
        scope.lockedStatus?.let { copy(statuses = setOf(it)) } ?: this

    private fun <T> Set<T>.toggled(item: T): Set<T> = if (item in this) this - item else this + item
}
