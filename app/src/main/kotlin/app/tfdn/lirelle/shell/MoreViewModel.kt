package app.tfdn.lirelle.shell

import androidx.lifecycle.ViewModel
import app.tfdn.lirelle.core.library.SourceCapabilities
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** What More offers beside its fixed rows: the reading history, when the profile's source keeps one (spec 2c). */
@HiltViewModel
class MoreViewModel @Inject constructor(capabilities: SourceCapabilities) : ViewModel() {
    val history: Boolean = capabilities.history
}
