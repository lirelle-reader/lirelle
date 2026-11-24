package app.tfdn.lirelle.feature.library.series

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.SeriesId
import app.tfdn.lirelle.core.library.model.Series
import app.tfdn.lirelle.core.ui.BookCollection
import app.tfdn.lirelle.core.ui.DetailScaffold
import app.tfdn.lirelle.core.ui.Loadable
import app.tfdn.lirelle.core.ui.LoadableContent
import app.tfdn.lirelle.core.ui.NotificationsQuestion
import app.tfdn.lirelle.core.ui.bookItemsOf
import app.tfdn.lirelle.core.ui.placeholderBookItems
import app.tfdn.lirelle.feature.library.R
import app.tfdn.lirelle.feature.library.books.PresentationButton

/**
 * A series: its authors, how much of it is read, the button that downloads it, then its books in volume order.
 */
@Composable
fun SeriesScreen(
    seriesId: SeriesId,
    onBack: () -> Unit,
    onOpenBook: (BookId) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SeriesViewModel = hiltViewModel<SeriesViewModel, SeriesViewModel.Factory>(
        key = "series-$seriesId",
        creationCallback = { it.create(seriesId.value) },
    ),
    downloads: SeriesDownloadViewModel = hiltViewModel<SeriesDownloadViewModel>(key = "series-download-$seriesId"),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val download by downloads.state.collectAsStateWithLifecycle()
    // The books of the series shown are what its download offers.
    val shown = (state.content as? Loadable.Ready)?.value
    LaunchedEffect(shown) { shown?.let(downloads::show) }
    NotificationsQuestion(download.askNotifications, downloads::notificationsHandled)
    DetailScaffold(
        // No title until the name is known: an empty one would be an empty heading.
        title = (state.content as? Loadable.Ready)?.value?.series?.name,
        onBack = onBack,
        modifier = modifier,
        actions = { PresentationButton(state.presentation, viewModel::onPresentation) },
    ) {
        LoadableContent(
            state = state.content,
            onRetry = viewModel::load,
            loading = { BookCollection(placeholderBookItems(), state.presentation, onOpenBook = {}) },
        ) { content ->
            BookCollection(
                items = bookItemsOf(content.books),
                presentation = state.presentation,
                onOpenBook = onOpenBook,
                showSeriesIndex = true,
                header = {
                    item(key = "heading") {
                        Column(modifier = Modifier.padding(bottom = 8.dp)) {
                            SeriesHeading(content.series)
                            SeriesDownloadBlock(download, onClick = downloads::onButton)
                        }
                    }
                },
            )
        }
    }
    download.sheet?.let { sheet ->
        SeriesDownloadSheet(sheet, onDismiss = downloads::closeSheet, onRetry = downloads::retry, downloads::confirm)
    }
}

@Composable
private fun SeriesHeading(series: Series) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (series.authors.isNotEmpty()) {
            Text(series.authors.joinToString(), style = MaterialTheme.typography.bodyLarge)
        }
        Text(
            text = stringResource(R.string.series_progress, series.readCount, series.bookCount),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LinearProgressIndicator(
            progress = { series.progress },
            modifier = Modifier.fillMaxWidth(),
            // No dot at the end of the track: on an unread series it reads as progress.
            drawStopIndicator = {},
        )
    }
}
