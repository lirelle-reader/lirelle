package app.tfdn.lirelle.feature.library.books

import app.tfdn.lirelle.core.library.model.BookListSource
import app.tfdn.lirelle.core.library.model.StatusFilter

/**
 * What a list of books is about.
 *
 * @property lockedStatus the reading status the list is about: always asked for, and not a filter the user can change.
 * @property preferencesKey the lists that share a key share their sort and their grid or list choice.
 * @property followsLibrary whether the list shows the one library the profile chose, when it chose one: the Library's
 *   own list only. Any other list is about the whole server.
 */
data class BookListScope(
    val source: BookListSource,
    val preferencesKey: String,
    val text: String? = null,
    val genre: String? = null,
    val lockedStatus: StatusFilter? = null,
    val followsLibrary: Boolean = false,
) {
    companion object {
        fun library() = BookListScope(BookListSource.AllBooks, "books", followsLibrary = true)
        fun shelf(source: BookListSource) = BookListScope(source, "shelf")
        fun genre(name: String) = BookListScope(BookListSource.AllBooks, "genre", genre = name)
        fun search(text: String) = BookListScope(BookListSource.AllBooks, "search", text = text)
        fun status(status: StatusFilter, key: String) =
            BookListScope(BookListSource.AllBooks, key, lockedStatus = status)
        fun recentlyAdded() = BookListScope(BookListSource.AllBooks, "recently-added")
    }
}
