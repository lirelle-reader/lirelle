package app.tfdn.lirelle.feature.settings.about

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the app ships and its licences, read once for the licences' screens. */
@HiltViewModel
class LicencesViewModel @Inject constructor(source: LicencesSource) : ViewModel() {

    private val read = MutableStateFlow<Licences?>(null)

    /** Null while the list is read. */
    val licences: StateFlow<Licences?> = read.asStateFlow()

    init {
        viewModelScope.launch { read.value = source.load() }
    }
}
