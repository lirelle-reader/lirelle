package app.tfdn.lirelle.feature.library.books

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.model.BookSummary
import app.tfdn.lirelle.core.ui.PagedBookCollection
import app.tfdn.lirelle.feature.library.R
import kotlinx.coroutines.flow.emptyFlow

/**
 * A sortable, filterable list of the books of [scope].
 *
 * @param emptyText what to say when [scope] has no book at all (with filters the user set, the
 *   screen says so itself and offers to clear them).
 * @param onRefresh what else a pull to refresh does, once the books were asked again.
 * @param heading a title shown above the sort and the filters, scrolling with the books. It
 *   wraps, however long it is.
 */
@Composable
fun BookListScreen(
    scope: BookListScope,
    onOpenBook: (BookId) -> Unit,
    emptyText: String,
    modifier: Modifier = Modifier,
    heading: String? = null,
    onRefresh: () -> Unit = {},
    viewModel: BookListViewModel = hiltViewModel<BookListViewModel, BookListViewModel.Factory>(
        key = "books-$scope",
        creationCallback = { it.create(scope) },
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pages by viewModel.pages.collectAsStateWithLifecycle()
    val filtered = state.isFiltered
    // Another library is another list: from its top, with nothing of the library left while it loads or fails.
    key(pages?.library) {
        val books = (pages?.items ?: NoBooksYet).collectAsLazyPagingItems()
        PagedBookCollection(
            books = books,
            presentation = state.presentation,
            onOpenBook = onOpenBook,
            errorOf = viewModel::errorOf,
            emptyText = if (filtered) stringResource(R.string.books_empty_filtered) else emptyText,
            modifier = modifier,
            emptyActionLabel = if (filtered) stringResource(R.string.books_clear_filters) else null,
            onEmptyAction = viewModel::onClearFilters,
            onRefresh = onRefresh,
            header = {
                if (heading != null) item(key = "heading") { ListHeading(heading) }
                item(key = "bar") {
                    SortFilterBar(
                        state = state,
                        onSort = viewModel::onSort,
                        onToggleFormat = viewModel::onToggleFormat,
                        onToggleStatus = viewModel::onToggleStatus,
                        onReadAlongOnly = viewModel::onReadAlongOnly,
                        onPresentation = viewModel::onPresentation,
                    )
                }
            },
        )
    }
}

/** What is shown until the choice of a library is read: a list that loads. */
private val NoBooksYet = emptyFlow<PagingData<BookSummary>>()

@Composable
private fun ListHeading(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .semantics { heading() },
        style = MaterialTheme.typography.headlineSmall,
    )
}
