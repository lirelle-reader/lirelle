package app.tfdn.lirelle.feature.library.series

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tfdn.lirelle.core.ids.SeriesId
import app.tfdn.lirelle.core.library.LibraryPreferences
import app.tfdn.lirelle.core.library.LibraryRepository
import app.tfdn.lirelle.core.library.Presentation
import app.tfdn.lirelle.core.library.model.SeriesBooks
import app.tfdn.lirelle.core.ui.Loadable
import app.tfdn.lirelle.core.ui.toLoadable
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SeriesUiState(
    val content: Loadable<SeriesBooks> = Loadable.Loading,
    val presentation: Presentation = Presentation.Grid,
)

/** Series and author screens share one remembered presentation. */
internal const val DetailPreferencesKey = "detail"

@HiltViewModel(assistedFactory = SeriesViewModel.Factory::class)
class SeriesViewModel @AssistedInject constructor(
    @Assisted seriesIdText: String,
    private val repository: LibraryRepository,
    private val preferences: LibraryPreferences,
) : ViewModel() {

    /**
     * Given as its text: Hilt's generated factory is Java, which cannot pass a value class (the Kotlin name of a
     * function taking one is mangled).
     */
    @AssistedFactory
    interface Factory {
        fun create(seriesIdText: String): SeriesViewModel
    }

    private val seriesId = SeriesId(seriesIdText)

    private val content = MutableStateFlow<Loadable<SeriesBooks>>(Loadable.Loading)

    val state: StateFlow<SeriesUiState> = combine(content, preferences.view(DetailPreferencesKey)) { content, prefs ->
        SeriesUiState(content, prefs.presentation)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SeriesUiState())

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            content.value = Loadable.Loading
            content.value = repository.seriesBooks(seriesId).toLoadable()
        }
    }

    fun onPresentation(presentation: Presentation) {
        viewModelScope.launch { preferences.update(DetailPreferencesKey) { it.copy(presentation = presentation) } }
    }
}
