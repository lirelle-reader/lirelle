package app.tfdn.lirelle.core.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.Presentation
import app.tfdn.lirelle.core.library.model.BookSummary
import app.tfdn.lirelle.core.network.ApiError

/**
 * A paged list of books with every state it can be in.
 *
 * @param errorOf turns a failed load into the error to show.
 * @param emptyText shown when the list loaded and is empty.
 * @param onRefresh what else a pull to refresh does, once the books were asked again.
 * @param header items placed before the books. They are kept, with what they remember, in
 *   every state: a failed first load is shown under them, so the sort or the filters that led
 *   to it can still be changed.
 */
@Composable
fun PagedBookCollection(
    books: LazyPagingItems<BookSummary>,
    presentation: Presentation,
    onOpenBook: (BookId) -> Unit,
    errorOf: (Throwable) -> ApiError,
    emptyText: String,
    modifier: Modifier = Modifier,
    emptyActionLabel: String? = null,
    onEmptyAction: () -> Unit = {},
    onRefresh: () -> Unit = {},
    header: (LazyListScope.() -> Unit)? = null,
) {
    PagedListScaffold(items = books, errorOf = errorOf, modifier = modifier, onRefresh = onRefresh) { phase ->
        // In a lazy list the height is unbounded, so a message is as tall as its content.
        val message = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp)
        // One call for every phase: the header stays the same composition when the phase changes.
        BookCollection(
            items = when (phase) {
                PagedPhase.Loaded -> books.asBookItems()
                PagedPhase.Loading -> placeholderBookItems()
                else -> bookItemsOf(emptyList())
            },
            presentation = presentation,
            onOpenBook = if (phase == PagedPhase.Loaded) onOpenBook else NoBook,
            header = header,
            footer = when (phase) {
                is PagedPhase.Failed -> {
                    { FailedState(error = phase.error, onRetry = books::retry, modifier = message) }
                }
                PagedPhase.Empty -> {
                    { EmptyMessage(emptyText, message, actionLabel = emptyActionLabel, onAction = onEmptyAction) }
                }
                else -> null
            },
        )
    }
}

private val NoBook: (BookId) -> Unit = {}
