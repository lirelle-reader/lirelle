package app.tfdn.lirelle.feature.library.authors

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.PagingData
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import app.tfdn.lirelle.core.designsystem.LocalWindowWidthClass
import app.tfdn.lirelle.core.designsystem.WindowWidthClass
import app.tfdn.lirelle.core.ids.AuthorId
import app.tfdn.lirelle.core.library.model.Author
import app.tfdn.lirelle.core.library.model.AuthorSort
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.ui.AuthorPortrait
import app.tfdn.lirelle.core.ui.EmptyMessage
import app.tfdn.lirelle.core.ui.FailedState
import app.tfdn.lirelle.core.ui.PagedListScaffold
import app.tfdn.lirelle.core.ui.PagedPhase
import app.tfdn.lirelle.feature.library.R
import app.tfdn.lirelle.feature.library.books.SortChip
import kotlinx.coroutines.flow.emptyFlow

// The same minimum width as a book in the grid: three across on a phone.
private val AuthorMinWidth = 104.dp

// Larger portraits on a wide window rather than a dozen small ones per row.
private val WideAuthorMinWidth = 150.dp
private const val PlaceholderAuthors = 12

@StringRes
internal fun AuthorSort.labelRes(): Int = when (this) {
    AuthorSort.Name -> R.string.sort_name
    AuthorSort.BookCount -> R.string.sort_book_count
}

@Composable
fun AuthorsTab(
    onOpenAuthor: (AuthorId) -> Unit,
    modifier: Modifier = Modifier,
    onRefresh: () -> Unit = {},
    viewModel: AuthorsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pages by viewModel.pages.collectAsStateWithLifecycle()
    // Another library is another list: from its top, with nothing of the library left while it loads or fails.
    key(pages?.library) {
        AuthorsList(
            authors = (pages?.items ?: NoAuthorsYet).collectAsLazyPagingItems(),
            state = state,
            errorOf = viewModel::errorOf,
            onSort = viewModel::onSort,
            onOpenAuthor = onOpenAuthor,
            onRefresh = onRefresh,
            modifier = modifier,
        )
    }
}

/** What is shown until the choice of a library is read: a list that loads. */
private val NoAuthorsYet = emptyFlow<PagingData<Author>>()

@Composable
private fun AuthorsList(
    authors: LazyPagingItems<Author>,
    state: AuthorsUiState,
    errorOf: (Throwable) -> ApiError,
    onSort: (AuthorSort) -> Unit,
    onOpenAuthor: (AuthorId) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PagedListScaffold(items = authors, errorOf = errorOf, modifier = modifier, onRefresh = onRefresh) { phase ->
        // One grid for every phase: the bar stays the same composition, and above a failed load.
        AuthorsGrid {
            item(key = "bar", span = { GridItemSpan(maxLineSpan) }) { AuthorsBar(state, onSort) }
            when (phase) {
                PagedPhase.Loading -> items(PlaceholderAuthors) { AuthorPlaceholder() }
                is PagedPhase.Failed -> item(key = "error", span = { GridItemSpan(maxLineSpan) }) {
                    FailedState(phase.error, onRetry = authors::retry, modifier = Modifier.padding(vertical = 48.dp))
                }
                PagedPhase.Empty -> item(key = "empty", span = { GridItemSpan(maxLineSpan) }) { NoAuthors() }
                PagedPhase.Loaded -> authorItems(authors, onOpenAuthor)
            }
        }
    }
}

/**
 * Items are keyed by position, not by author: with offset paging an author added on the server
 * between two pages comes twice, and a lazy grid throws on a key used twice. An author not
 * loaded yet is drawn as a placeholder.
 */
private fun LazyGridScope.authorItems(authors: LazyPagingItems<Author>, onOpenAuthor: (AuthorId) -> Unit) {
    items(count = authors.itemCount, key = { index -> "author-$index" }) { index ->
        val author = authors[index]
        if (author == null) AuthorPlaceholder() else AuthorItem(author) { onOpenAuthor(author.id) }
    }
}

@Composable
private fun NoAuthors() {
    EmptyMessage(
        text = stringResource(R.string.authors_empty),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
    )
}

@Composable
private fun AuthorsGrid(content: LazyGridScope.() -> Unit) {
    val wide = LocalWindowWidthClass.current == WindowWidthClass.Expanded
    LazyVerticalGrid(
        columns = GridCells.Adaptive(if (wide) WideAuthorMinWidth else AuthorMinWidth),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content,
    )
}

@Composable
private fun AuthorsBar(state: AuthorsUiState, onSort: (AuthorSort) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SortChip(
            label = stringResource(state.sort.labelRes()),
            direction = state.direction,
            options = AuthorSort.entries.map { it to stringResource(it.labelRes()) },
            selected = state.sort,
            onSelect = onSort,
        )
        state.total?.let { total ->
            Text(
                text = pluralStringResource(R.plurals.authors_count, total, total),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AuthorItem(author: Author, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .testTag("author-${author.id}")
            // A button: the texts under the portrait are one-line labels, and are read as one item.
            .clickable(role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        AuthorPortrait(author.portraitUrl, author.name, Modifier.fillMaxWidth())
        Text(
            text = author.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = pluralStringResource(R.plurals.books_count, author.bookCount, author.bookCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AuthorPlaceholder() {
    // The shape of a portrait, with no initial on it.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    )
}
