package app.tfdn.lirelle.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tfdn.lirelle.core.library.LibraryChoice
import app.tfdn.lirelle.core.library.LibraryChoiceState
import app.tfdn.lirelle.core.library.LibraryRepository
import app.tfdn.lirelle.core.library.SourceCapabilities
import app.tfdn.lirelle.core.library.model.ServerLibrary
import app.tfdn.lirelle.core.network.ApiResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * What the Library shows around its tabs' own content: the tabs the open profile's source offers (spec 2c), the
 * one it opens on (spec 3a2), and the libraries of its server with the one the profile chose to see.
 */
@HiltViewModel
class LibraryViewModel @Inject constructor(
    capabilities: SourceCapabilities,
    private val repository: LibraryRepository,
    private val choice: LibraryChoice,
) : ViewModel() {
    val tabs: List<LibraryTab> = capabilities.libraryTabs()
    val startTab: LibraryTab = capabilities.libraryStartTab()

    /** The libraries as last read, kept on the device, and the one chosen: shown before the server answers. */
    val libraries: StateFlow<LibraryChoiceState> =
        choice.state.stateIn(viewModelScope, SharingStarted.Eagerly, LibraryChoiceState())

    private var reading: Job? = null

    init {
        refresh()
    }

    /**
     * The Library is in front, for the first time or again, or one of its lists is pulled to be read again: its
     * server's libraries are read and kept. A read still under way is enough.
     */
    fun refresh() {
        if (reading?.isActive == true) return
        reading = viewModelScope.launch {
            // A failure is not said: the Library keeps the libraries and the choice it has.
            val answer = repository.libraries()
            if (answer is ApiResult.Success) choice.libraries(answer.value)
        }
    }

    /** Shows [library] alone in the Books, Series and Authors tabs; null shows every library. */
    fun onChoose(library: ServerLibrary?) {
        viewModelScope.launch { choice.choose(library) }
    }
}
