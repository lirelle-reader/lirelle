package app.tfdn.lirelle.feature.library.shelves

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.model.BookListSource
import app.tfdn.lirelle.core.ui.DetailScaffold
import app.tfdn.lirelle.feature.library.R
import app.tfdn.lirelle.feature.library.books.BookListScope
import app.tfdn.lirelle.feature.library.books.BookListScreen
import app.tfdn.lirelle.feature.library.books.BookListViewModel

/** A collection or a smart list: its name, then its books with the sorts of the Books view. */
@Composable
fun ShelfScreen(
    source: BookListSource,
    name: String,
    onBack: () -> Unit,
    onOpenBook: (BookId) -> Unit,
    viewModel: BookListViewModel = hiltViewModel<BookListViewModel, BookListViewModel.Factory>(
        key = "books-${BookListScope.shelf(source)}",
        creationCallback = { it.create(BookListScope.shelf(source)) },
    ),
    modifier: Modifier = Modifier,
) {
    DetailScaffold(title = name, onBack = onBack, modifier = modifier) {
        BookListScreen(
            scope = BookListScope.shelf(source),
            onOpenBook = onOpenBook,
            emptyText = stringResource(R.string.shelf_empty),
            viewModel = viewModel,
        )
    }
}
