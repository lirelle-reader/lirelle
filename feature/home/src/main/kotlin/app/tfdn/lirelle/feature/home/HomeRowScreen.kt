package app.tfdn.lirelle.feature.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.Presentation
import app.tfdn.lirelle.core.library.model.HomeRow
import app.tfdn.lirelle.core.library.model.HomeRowKind
import app.tfdn.lirelle.core.ui.BookCollection
import app.tfdn.lirelle.core.ui.DetailScaffold
import app.tfdn.lirelle.core.ui.Loadable
import app.tfdn.lirelle.core.ui.LoadableContent
import app.tfdn.lirelle.core.ui.bookItemsOf

/** Every book of a row of Home, as a grid. */
@Composable
fun HomeRowScreen(
    kind: HomeRowKind,
    onBack: () -> Unit,
    onOpenBook: (BookId) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeRowViewModel = hiltViewModel<HomeRowViewModel, HomeRowViewModel.Factory>(
        key = "home-row-$kind",
        creationCallback = { it.create(kind) },
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    HomeRowContent(kind, state, onBack, viewModel::load, onOpenBook, modifier)
}

@Composable
internal fun HomeRowContent(
    kind: HomeRowKind,
    state: Loadable<HomeRow>,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onOpenBook: (BookId) -> Unit,
    modifier: Modifier = Modifier,
) {
    DetailScaffold(title = stringResource(kind.titleRes()), onBack = onBack, modifier = modifier) {
        LoadableContent(state = state, onRetry = onRetry) { row ->
            BookCollection(
                items = bookItemsOf(row.books),
                presentation = Presentation.Grid,
                onOpenBook = onOpenBook,
                showSeriesIndex = kind == HomeRowKind.UpNext,
            )
        }
    }
}
