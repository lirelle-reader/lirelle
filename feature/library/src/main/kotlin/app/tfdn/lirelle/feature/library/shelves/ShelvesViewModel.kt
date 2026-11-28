package app.tfdn.lirelle.feature.library.shelves

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tfdn.lirelle.core.library.LibraryRepository
import app.tfdn.lirelle.core.library.SourceCapabilities
import app.tfdn.lirelle.core.library.model.ShelfKind
import app.tfdn.lirelle.core.library.model.Shelves
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.ui.Loadable
import app.tfdn.lirelle.core.ui.toLoadable
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ShelvesUiState(val shelves: Loadable<Shelves> = Loadable.Loading, val refreshing: Boolean = false)

/** The shelves of the kinds the source offers (spec 2c): a kind it does not offer is not shown, even if listed. */
@HiltViewModel
class ShelvesViewModel @Inject constructor(
    private val repository: LibraryRepository,
    capabilities: SourceCapabilities,
) : ViewModel() {

    private val kinds = capabilities.shelfKinds

    private val mutableState = MutableStateFlow(ShelvesUiState())
    val state: StateFlow<ShelvesUiState> = mutableState.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            mutableState.value = ShelvesUiState()
            mutableState.value = ShelvesUiState(offered().toLoadable())
        }
    }

    /** Pull to refresh: the list stays; a failed refresh leaves it as it was. */
    fun refresh() {
        viewModelScope.launch {
            mutableState.update { it.copy(refreshing = true) }
            val result = offered()
            mutableState.update { current ->
                val keep = result is ApiResult.Failure && current.shelves is Loadable.Ready
                ShelvesUiState(if (keep) current.shelves else result.toLoadable())
            }
        }
    }

    private suspend fun offered(): ApiResult<Shelves> = when (val result = repository.shelves()) {
        is ApiResult.Success -> ApiResult.Success(
            result.value.copy(
                smartLists = result.value.smartLists.takeIf { ShelfKind.SmartList in kinds }.orEmpty(),
                collections = result.value.collections.takeIf { ShelfKind.Collection in kinds }.orEmpty(),
                playlists = result.value.playlists.takeIf { ShelfKind.Playlist in kinds }.orEmpty(),
            ),
        )
        is ApiResult.Failure -> result
    }
}
