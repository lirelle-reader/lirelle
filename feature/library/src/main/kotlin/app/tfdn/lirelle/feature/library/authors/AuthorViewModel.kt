package app.tfdn.lirelle.feature.library.authors

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tfdn.lirelle.core.ids.AuthorId
import app.tfdn.lirelle.core.library.LibraryPreferences
import app.tfdn.lirelle.core.library.LibraryRepository
import app.tfdn.lirelle.core.library.Presentation
import app.tfdn.lirelle.core.library.SourceCapabilities
import app.tfdn.lirelle.core.library.model.AuthorDetail
import app.tfdn.lirelle.core.library.model.BookSummary
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.ui.Loadable
import app.tfdn.lirelle.feature.library.series.DetailPreferencesKey
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AuthorContent(val detail: AuthorDetail, val books: List<BookSummary>)

data class AuthorUiState(
    val content: Loadable<AuthorContent> = Loadable.Loading,
    val presentation: Presentation = Presentation.Grid,
)

@HiltViewModel(assistedFactory = AuthorViewModel.Factory::class)
class AuthorViewModel @AssistedInject constructor(
    @Assisted authorIdText: String,
    private val repository: LibraryRepository,
    private val preferences: LibraryPreferences,
    capabilities: SourceCapabilities,
) : ViewModel() {

    /**
     * Given as its text: Hilt's generated factory is Java, which cannot pass a value class (the Kotlin name of a
     * function taking one is mangled).
     */
    @AssistedFactory
    interface Factory {
        fun create(authorIdText: String): AuthorViewModel
    }

    private val authorId = AuthorId(authorIdText)

    /** False when the source has no authors: the page, reached by an old link, goes back and asks nothing. */
    val offered: Boolean = capabilities.authors

    private val content = MutableStateFlow<Loadable<AuthorContent>>(Loadable.Loading)

    val state: StateFlow<AuthorUiState> = combine(content, preferences.view(DetailPreferencesKey)) { content, prefs ->
        AuthorUiState(content, prefs.presentation)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AuthorUiState())

    init {
        if (offered) load()
    }

    /** The author and their books are asked for together; the screen needs both. */
    fun load() {
        viewModelScope.launch {
            content.value = Loadable.Loading
            val detail = async { repository.author(authorId) }
            val books = async { repository.authorBooks(authorId) }
            content.value = when (val author = detail.await()) {
                is ApiResult.Failure -> Loadable.Failed(author.error)
                is ApiResult.Success -> when (val list = books.await()) {
                    is ApiResult.Failure -> Loadable.Failed(list.error)
                    is ApiResult.Success -> Loadable.Ready(AuthorContent(author.value, list.value))
                }
            }
        }
    }

    fun onPresentation(presentation: Presentation) {
        viewModelScope.launch { preferences.update(DetailPreferencesKey) { it.copy(presentation = presentation) } }
    }
}
