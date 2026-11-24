package app.tfdn.lirelle.feature.library.books

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ui.PagedBookCollection
import app.tfdn.lirelle.feature.library.R

/**
 * A sortable, filterable list of the books of [scope].
 *
 * @param emptyText what to say when [scope] has no book at all (with filters the user set, the
 *   screen says so itself and offers to clear them).
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
    viewModel: BookListViewModel = hiltViewModel<BookListViewModel, BookListViewModel.Factory>(
        key = "books-$scope",
        creationCallback = { it.create(scope) },
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val books = viewModel.books.collectAsLazyPagingItems()
    val filtered = state.isFiltered
    PagedBookCollection(
        books = books,
        presentation = state.presentation,
        onOpenBook = onOpenBook,
        errorOf = viewModel::errorOf,
        emptyText = if (filtered) stringResource(R.string.books_empty_filtered) else emptyText,
        modifier = modifier,
        emptyActionLabel = if (filtered) stringResource(R.string.books_clear_filters) else null,
        onEmptyAction = viewModel::onClearFilters,
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
