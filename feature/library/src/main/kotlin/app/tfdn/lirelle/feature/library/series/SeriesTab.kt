package app.tfdn.lirelle.feature.library.series

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tfdn.lirelle.core.designsystem.LocalWindowWidthClass
import app.tfdn.lirelle.core.designsystem.WindowWidthClass
import app.tfdn.lirelle.core.ids.SeriesId
import app.tfdn.lirelle.core.library.model.SeriesProgressFilter
import app.tfdn.lirelle.core.library.model.SeriesSort
import app.tfdn.lirelle.core.ui.CollectionEdgePadding
import app.tfdn.lirelle.core.ui.EmptyMessage
import app.tfdn.lirelle.core.ui.LoadableContent
import app.tfdn.lirelle.feature.library.R
import app.tfdn.lirelle.feature.library.books.ChipRow
import app.tfdn.lirelle.feature.library.books.SortChip
import app.tfdn.lirelle.feature.library.books.TickedFilterChip

private val TileMinWidth = 150.dp

// A wide window gets larger tiles, not more of them: each tile already stacks three covers.
private val WideTileMinWidth = 200.dp
private val TileSpacing = 12.dp
private val WideTileSpacing = 16.dp
private const val PlaceholderTiles = 6

@StringRes
internal fun SeriesSort.labelRes(): Int = when (this) {
    SeriesSort.Name -> R.string.sort_name
    SeriesSort.BookCount -> R.string.sort_book_count
    SeriesSort.UnreadCount -> R.string.sort_unread_count
    SeriesSort.RecentlyAdded -> R.string.sort_recent
    SeriesSort.Progress -> R.string.sort_progress
}

@StringRes
internal fun SeriesProgressFilter?.labelRes(): Int = when (this) {
    null -> R.string.progress_all
    SeriesProgressFilter.NotStarted -> R.string.progress_not_started
    SeriesProgressFilter.InProgress -> R.string.progress_in_progress
    SeriesProgressFilter.Complete -> R.string.progress_complete
}

@Composable
fun SeriesTab(
    onOpenSeries: (SeriesId) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SeriesListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SeriesTabContent(
        state = state,
        onOpenSeries = onOpenSeries,
        onSort = viewModel::onSort,
        onFilter = viewModel::onFilter,
        onRetry = viewModel::load,
        onRefresh = viewModel::refresh,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeriesTabContent(
    state: SeriesListUiState,
    onOpenSeries: (SeriesId) -> Unit,
    onSort: (SeriesSort) -> Unit,
    onFilter: (SeriesProgressFilter?) -> Unit,
    onRetry: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = onRefresh, modifier = modifier) {
        LoadableContent(
            state = state.series,
            onRetry = onRetry,
            loading = { SeriesGrid { items(PlaceholderTiles) { SeriesPlaceholder() } } },
        ) { series ->
            SeriesGrid {
                item(key = "bar", span = { GridItemSpan(maxLineSpan) }) {
                    SeriesBar(state, count = series.size, onSort = onSort, onFilter = onFilter)
                }
                if (series.isEmpty()) {
                    item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                        NoSeries(filtered = state.filter != null, onClearFilter = { onFilter(null) })
                    }
                }
                // A list's key must go in a Bundle: the id's text, not the id.
                items(series, key = { it.id.value }) { SeriesTile(it, onClick = { onOpenSeries(it.id) }) }
            }
        }
    }
}

/** Why there is nothing: no series at all, or none for the filter, which can then be cleared from here. */
@Composable
private fun NoSeries(filtered: Boolean, onClearFilter: () -> Unit) {
    EmptyMessage(
        text = stringResource(if (filtered) R.string.series_empty_filtered else R.string.series_empty),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        actionLabel = if (filtered) stringResource(R.string.series_clear_filter) else null,
        onAction = onClearFilter,
    )
}

@Composable
private fun SeriesGrid(content: LazyGridScope.() -> Unit) {
    val wide = LocalWindowWidthClass.current == WindowWidthClass.Expanded
    val spacing = if (wide) WideTileSpacing else TileSpacing
    LazyVerticalGrid(
        columns = GridCells.Adaptive(if (wide) WideTileMinWidth else TileMinWidth),
        contentPadding = PaddingValues(CollectionEdgePadding),
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}

@Composable
private fun SeriesBar(
    state: SeriesListUiState,
    count: Int,
    onSort: (SeriesSort) -> Unit,
    onFilter: (SeriesProgressFilter?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ChipRow(CollectionEdgePadding) {
            SortChip(
                label = stringResource(state.sort.labelRes()),
                direction = state.direction,
                options = SeriesSort.entries.map { it to stringResource(it.labelRes()) },
                selected = state.sort,
                onSelect = onSort,
            )
            ProgressFilterChip(state.filter, onFilter)
        }
        Text(
            text = pluralStringResource(R.plurals.series_count, count, count),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One choice among "all" and the three progress states; ticked when not "all". */
@Composable
private fun ProgressFilterChip(current: SeriesProgressFilter?, onFilter: (SeriesProgressFilter?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val options: List<SeriesProgressFilter?> = listOf(null) + SeriesProgressFilter.entries
    Box {
        TickedFilterChip(
            label = stringResource(R.string.filter_progress),
            selected = current != null,
            onClick = { expanded = true },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(stringResource(option.labelRes())) },
                    onClick = {
                        expanded = false
                        onFilter(option)
                    },
                    trailingIcon = { if (option == current) Icon(Icons.Outlined.Check, contentDescription = null) },
                )
            }
        }
    }
}
