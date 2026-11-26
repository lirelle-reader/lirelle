package app.tfdn.lirelle.feature.library

import androidx.annotation.StringRes
import app.tfdn.lirelle.core.library.LibraryStart
import app.tfdn.lirelle.core.library.SourceCapabilities

/** The tabs of the Library, in the order they are shown. */
enum class LibraryTab(@StringRes val label: Int) {
    Books(R.string.library_tab_books),
    Series(R.string.library_tab_series),
    Authors(R.string.library_tab_authors),
    Shelves(R.string.library_tab_shelves),
}

/**
 * The tab the Library opens on, as the source says: one of the tabs it offers. A source that would start on books it
 * cannot list starts on its series, which every source offers.
 */
fun SourceCapabilities.libraryStartTab(): LibraryTab = when (libraryStart) {
    LibraryStart.Books -> if (libraryBooks) LibraryTab.Books else LibraryTab.Series
    LibraryStart.Series -> LibraryTab.Series
}

/**
 * The tabs the source offers, in their order: Series always, Books when it can list all of its books, Authors and
 * Shelves when it has them.
 */
fun SourceCapabilities.libraryTabs(): List<LibraryTab> = LibraryTab.entries.filter { tab ->
    when (tab) {
        LibraryTab.Books -> libraryBooks
        LibraryTab.Series -> true
        LibraryTab.Authors -> authors
        LibraryTab.Shelves -> shelfKinds.isNotEmpty()
    }
}
