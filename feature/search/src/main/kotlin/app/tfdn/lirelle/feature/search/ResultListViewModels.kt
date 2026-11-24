package app.tfdn.lirelle.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import app.tfdn.lirelle.core.library.LibraryRepository
import app.tfdn.lirelle.core.library.SourceCapabilities
import app.tfdn.lirelle.core.library.model.Author
import app.tfdn.lirelle.core.library.model.AuthorSort
import app.tfdn.lirelle.core.library.model.Series
import app.tfdn.lirelle.core.library.model.SortDirection
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.ui.Loadable
import app.tfdn.lirelle.core.ui.toLoadable
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

/** Every series matching a text. */
@HiltViewModel(assistedFactory = SeriesResultsViewModel.Factory::class)
class SeriesResultsViewModel @AssistedInject constructor(
    @Assisted private val text: String,
    private val repository: LibraryRepository,
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(text: String): SeriesResultsViewModel
    }

    private val mutableState = MutableStateFlow<Loadable<List<Series>>>(Loadable.Loading)
    val state: StateFlow<Loadable<List<Series>>> = mutableState.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            mutableState.value = Loadable.Loading
            mutableState.value = repository.series(text).toLoadable()
        }
    }
}

/** Every author matching a text, by name. */
@HiltViewModel(assistedFactory = AuthorResultsViewModel.Factory::class)
class AuthorResultsViewModel @AssistedInject constructor(
    @Assisted text: String,
    private val repository: LibraryRepository,
    capabilities: SourceCapabilities,
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(text: String): AuthorResultsViewModel
    }

    /** False when the source has no author results: the list, reached by an old link, goes back and asks nothing. */
    val offered: Boolean = capabilities.authorResults

    val authors: Flow<PagingData<Author>> = if (offered) {
        repository.authors(AuthorSort.Name, SortDirection.Ascending, text).items.cachedIn(viewModelScope)
    } else {
        emptyFlow()
    }

    fun errorOf(throwable: Throwable): ApiError = repository.errorOf(throwable)
}
