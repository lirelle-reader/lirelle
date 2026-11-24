package app.tfdn.lirelle.core.ui

import androidx.compose.runtime.Stable
import androidx.paging.compose.LazyPagingItems
import app.tfdn.lirelle.core.library.model.BookSummary

private const val PlaceholderCount = 12

/**
 * Books by position, whether they come from a paged list or a plain one, so one grid serves
 * both. A null item is not loaded yet and is drawn as a placeholder.
 */
@Stable
interface BookItems {
    val count: Int

    /** Reads the item and, for a paged list, tells it this position is being shown. */
    operator fun get(index: Int): BookSummary?
}

fun bookItemsOf(books: List<BookSummary?>): BookItems = object : BookItems {
    override val count: Int get() = books.size

    override fun get(index: Int): BookSummary? = books[index]
}

fun LazyPagingItems<BookSummary>.asBookItems(): BookItems = object : BookItems {
    override val count: Int get() = itemCount

    override fun get(index: Int): BookSummary? = this@asBookItems[index]
}

/** What is shown while the first page loads. */
fun placeholderBookItems(count: Int = PlaceholderCount): BookItems = bookItemsOf(List(count) { null })
