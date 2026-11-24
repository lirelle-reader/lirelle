package app.tfdn.lirelle.feature.search

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tfdn.lirelle.core.ids.SeriesId
import app.tfdn.lirelle.core.ui.CollectionEdgePadding
import app.tfdn.lirelle.core.ui.DetailScaffold
import app.tfdn.lirelle.core.ui.EmptyMessage
import app.tfdn.lirelle.core.ui.LoadableContent

/** Every series matching [text], opened from "See all" on the search screen. */
@Composable
fun SeriesResultsScreen(
    text: String,
    onBack: () -> Unit,
    onOpenSeries: (SeriesId) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SeriesResultsViewModel = hiltViewModel<SeriesResultsViewModel, SeriesResultsViewModel.Factory>(
        key = "series-results-$text",
        creationCallback = { it.create(text) },
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    DetailScaffold(title = stringResource(R.string.search_series_title, text), onBack = onBack, modifier = modifier) {
        LoadableContent(state = state, onRetry = viewModel::load) { found ->
            if (found.isEmpty()) {
                EmptyMessage(stringResource(R.string.search_nothing, text))
            } else {
                LazyColumn(contentPadding = PaddingValues(CollectionEdgePadding)) {
                    // A list's key must go in a Bundle: the id's text, not the id.
                    items(found, key = { it.id.value }) { series ->
                        SeriesResultRow(series, onClick = { onOpenSeries(series.id) })
                    }
                }
            }
        }
    }
}
