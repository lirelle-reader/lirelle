package app.tfdn.lirelle.feature.library.shelves

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tfdn.lirelle.core.library.model.BookListSource
import app.tfdn.lirelle.core.library.model.Shelf
import app.tfdn.lirelle.core.library.model.ShelfKind
import app.tfdn.lirelle.core.ui.EmptyMessage
import app.tfdn.lirelle.core.ui.LoadableContent
import app.tfdn.lirelle.feature.library.R

/** A playlist opens as a collection does: the source tells them apart by the shelf's id. */
fun Shelf.source(): BookListSource = when (kind) {
    ShelfKind.SmartList -> BookListSource.SmartList(id)
    ShelfKind.Collection, ShelfKind.Playlist -> BookListSource.Collection(id)
}

/** Smart lists, then collections, then playlists, in the order the server gives them. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShelvesTab(
    onOpenShelf: (Shelf) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ShelvesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = viewModel::refresh, modifier = modifier) {
        LoadableContent(state = state.shelves, onRetry = viewModel::load) { shelves ->
            if (shelves.isEmpty) {
                EmptyMessage(stringResource(R.string.shelves_empty))
            } else {
                LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                    group(R.string.shelves_smart_lists, shelves.smartLists, onOpenShelf)
                    group(R.string.shelves_collections, shelves.collections, onOpenShelf)
                    group(R.string.shelves_playlists, shelves.playlists, onOpenShelf)
                }
            }
        }
    }
}

private fun LazyListScope.group(@StringRes title: Int, shelves: List<Shelf>, onOpenShelf: (Shelf) -> Unit) {
    if (shelves.isEmpty()) return
    item(key = "title-$title") { GroupTitle(title) }
    items(shelves, key = { "${it.kind}-${it.id}" }) { shelf -> ShelfRow(shelf, onClick = { onOpenShelf(shelf) }) }
}

@Composable
private fun GroupTitle(@StringRes title: Int) {
    Text(
        text = stringResource(title),
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics { heading() },
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun ShelfRow(shelf: Shelf, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(shelf.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = shelf.bookCount?.let { count ->
            {
                Text(
                    text = pluralStringResource(R.plurals.books_count, count, count),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        leadingContent = {
            Icon(shelf.kind.icon(), contentDescription = null)
        },
        modifier = Modifier
            .testTag("shelf-${shelf.kind}-${shelf.id}")
            // A button: the row keeps its height, so its texts are one-line labels.
            .clickable(role = Role.Button, onClick = onClick),
    )
}

private fun ShelfKind.icon(): ImageVector = when (this) {
    ShelfKind.SmartList -> Icons.Outlined.AutoAwesome
    ShelfKind.Collection -> Icons.Outlined.CollectionsBookmark
    ShelfKind.Playlist -> Icons.AutoMirrored.Outlined.PlaylistPlay
}
