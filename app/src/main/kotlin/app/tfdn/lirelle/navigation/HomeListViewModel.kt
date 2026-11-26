package app.tfdn.lirelle.navigation

import androidx.lifecycle.ViewModel
import app.tfdn.lirelle.core.library.SourceCapabilities
import app.tfdn.lirelle.core.library.model.HomeRowKind
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** What "See all" of a Home row opens, as the open profile's source allows. */
@HiltViewModel
class HomeListViewModel @Inject constructor(private val capabilities: SourceCapabilities) : ViewModel() {
    /** True when [kind]'s full list is the row's own screen, false when it is a list of the library's books. */
    fun seesAllAsRow(kind: HomeRowKind): Boolean = kind.seeAllAsRow(capabilities)
}
