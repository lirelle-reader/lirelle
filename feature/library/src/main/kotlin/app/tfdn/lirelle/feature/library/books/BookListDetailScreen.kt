package app.tfdn.lirelle.feature.library.books

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ui.DetailScaffold
import app.tfdn.lirelle.feature.library.R

/** A list of books on its own screen: the books of a genre, of a search, of a row of Home. */
@Composable
fun BookListDetailScreen(
    title: String,
    scope: BookListScope,
    onBack: () -> Unit,
    onOpenBook: (BookId) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BookListViewModel = hiltViewModel<BookListViewModel, BookListViewModel.Factory>(
        key = "books-$scope",
        creationCallback = { it.create(scope) },
    ),
) {
    // No title in the bar, where it would be cut: a genre or a searched text can be long, so it
    // is the first thing of the list, where it wraps.
    DetailScaffold(title = null, onBack = onBack, modifier = modifier, compactBar = true) {
        BookListScreen(
            scope = scope,
            onOpenBook = onOpenBook,
            emptyText = stringResource(R.string.books_empty_scope),
            heading = title,
            viewModel = viewModel,
        )
    }
}
