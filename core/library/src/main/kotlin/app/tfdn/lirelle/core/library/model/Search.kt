package app.tfdn.lirelle.core.library.model

/** @property total how many items match on the server; [items] holds the first ones. */
data class SearchSection<T>(val items: List<T> = emptyList(), val total: Int = 0)

data class SearchResults(
    val text: String,
    val books: SearchSection<BookSummary> = SearchSection(),
    val series: SearchSection<Series> = SearchSection(),
    val authors: SearchSection<Author> = SearchSection(),
) {
    val isEmpty: Boolean get() = books.items.isEmpty() && series.items.isEmpty() && authors.items.isEmpty()
}

/** @property bookCount how many books have this genre; null when the server does not count them (Komga). */
data class Genre(val name: String, val bookCount: Int?)

/** The sections of search results a source can fill. */
enum class SearchKind { Books, Series, Authors }
