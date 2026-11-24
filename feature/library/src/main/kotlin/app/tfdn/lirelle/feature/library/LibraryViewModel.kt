package app.tfdn.lirelle.feature.library

import androidx.lifecycle.ViewModel
import app.tfdn.lirelle.core.library.SourceCapabilities
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * What the Library shows around its tabs' own content: the tabs the open profile's source offers (spec 2c), and the
 * one it opens on (spec 3a2).
 */
@HiltViewModel
class LibraryViewModel @Inject constructor(capabilities: SourceCapabilities) : ViewModel() {
    val tabs: List<LibraryTab> = capabilities.libraryTabs()
    val startTab: LibraryTab = capabilities.libraryStartTab()
}
