package app.tfdn.lirelle.feature.library.authors

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import app.tfdn.lirelle.core.library.LibraryChoice
import app.tfdn.lirelle.core.library.LibraryPreferences
import app.tfdn.lirelle.core.library.LibraryRepository
import app.tfdn.lirelle.core.library.model.Author
import app.tfdn.lirelle.core.library.model.AuthorSort
import app.tfdn.lirelle.core.library.model.ServerLibrary
import app.tfdn.lirelle.core.library.model.SortDirection
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.feature.library.LibraryList
import app.tfdn.lirelle.feature.library.LibraryPages
import app.tfdn.lirelle.feature.library.pagesOf
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AuthorsUiState(
    val sort: AuthorSort = AuthorSort.Name,
    val direction: SortDirection = SortDirection.Ascending,
    val total: Int? = null,
)

internal val AuthorSort.defaultDirection: SortDirection
    get() = if (this == AuthorSort.Name) SortDirection.Ascending else SortDirection.Descending

private const val PreferencesKey = "authors"

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AuthorsViewModel @Inject constructor(
    private val repository: LibraryRepository,
    private val preferences: LibraryPreferences,
    choice: LibraryChoice,
) : ViewModel() {

    private val order: Flow<Pair<AuthorSort, SortDirection>> = preferences.view(PreferencesKey).map { prefs ->
        val sort = prefs.sort?.let { name -> AuthorSort.entries.firstOrNull { it.name == name } } ?: AuthorSort.Name
        val direction = when (prefs.descending) {
            null -> sort.defaultDirection
            true -> SortDirection.Descending
            false -> SortDirection.Ascending
        }
        sort to direction
    }.distinctUntilChanged()

    /** The library the profile chose, null for all. */
    private val library: Flow<ServerLibrary?> = choice.state.map { it.chosen }.distinctUntilChanged()

    // Another library, or the same one under a new name, is a new list, from its top.
    private val lists = combine(order, library) { (sort, direction), library ->
        LibraryList(library, repository.authors(sort, direction, library = library))
    }
        .shareIn(viewModelScope, SharingStarted.Eagerly, replay = 1)

    /** The authors of the library shown, null until the choice is read: other pages for each other library. */
    val pages: StateFlow<LibraryPages<Author>?> =
        library.pagesOf(lists).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The authors of whatever library is shown. The tab shows [pages], which says when the library changes. */
    val authors: Flow<PagingData<Author>> = pages.filterNotNull().flatMapLatest { it.items }

    val state: StateFlow<AuthorsUiState> = combine(order, lists.flatMapLatest { it.list.total }) { order, total ->
        AuthorsUiState(order.first, order.second, total)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AuthorsUiState())

    fun onSort(sort: AuthorSort) {
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

    fun errorOf(throwable: Throwable): ApiError = repository.errorOf(throwable)
}
