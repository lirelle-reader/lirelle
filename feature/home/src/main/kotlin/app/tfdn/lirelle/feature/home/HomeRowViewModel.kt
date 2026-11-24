package app.tfdn.lirelle.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tfdn.lirelle.core.library.LibraryRepository
import app.tfdn.lirelle.core.library.model.HomeRow
import app.tfdn.lirelle.core.library.model.HomeRowExtent
import app.tfdn.lirelle.core.library.model.HomeRowKind
import app.tfdn.lirelle.core.ui.Loadable
import app.tfdn.lirelle.core.ui.toLoadable
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Every book of one row of Home, as many as the server gives at once. */
@HiltViewModel(assistedFactory = HomeRowViewModel.Factory::class)
class HomeRowViewModel @AssistedInject constructor(
    @Assisted private val kind: HomeRowKind,
    private val repository: LibraryRepository,
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(kind: HomeRowKind): HomeRowViewModel
    }

    private val mutableState = MutableStateFlow<Loadable<HomeRow>>(Loadable.Loading)
    val state: StateFlow<Loadable<HomeRow>> = mutableState.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            mutableState.value = Loadable.Loading
            mutableState.value = repository.homeRow(kind, HomeRowExtent.SeeAll).toLoadable()
        }
    }
}
