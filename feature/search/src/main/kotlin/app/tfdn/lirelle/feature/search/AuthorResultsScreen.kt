package app.tfdn.lirelle.feature.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import app.tfdn.lirelle.core.ids.AuthorId
import app.tfdn.lirelle.core.library.model.Author
import app.tfdn.lirelle.core.ui.CollectionEdgePadding
import app.tfdn.lirelle.core.ui.DetailScaffold
import app.tfdn.lirelle.core.ui.EmptyMessage
import app.tfdn.lirelle.core.ui.FailedState
import app.tfdn.lirelle.core.ui.LoadingTag
import app.tfdn.lirelle.core.ui.NotOffered
import app.tfdn.lirelle.core.ui.PagedListScaffold
import app.tfdn.lirelle.core.ui.PagedPhase

/** The height kept for an author that is not loaded yet, so the list does not jump. */
private val AuthorRowHeight = 56.dp

/** Every author matching [text], opened from "See all" on the search screen. Without author results, it goes back. */
@Composable
fun AuthorResultsScreen(
    text: String,
    onBack: () -> Unit,
    onOpenAuthor: (AuthorId) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AuthorResultsViewModel = hiltViewModel<AuthorResultsViewModel, AuthorResultsViewModel.Factory>(
        key = "author-results-$text",
        creationCallback = { it.create(text) },
    ),
) {
    if (!viewModel.offered) {
        NotOffered(onBack, modifier)
        return
    }
    val authors = viewModel.authors.collectAsLazyPagingItems()
    DetailScaffold(title = stringResource(R.string.search_authors_title, text), onBack = onBack, modifier = modifier) {
        PagedListScaffold(items = authors, errorOf = viewModel::errorOf) { phase ->
            when (phase) {
                PagedPhase.Loading -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag(LoadingTag),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
                // As tall as the list's place: what is taller than it at a large text size scrolls.
                is PagedPhase.Failed -> Message {
                    FailedState(phase.error, onRetry = authors::retry, modifier = it, scrolls = true)
                }
                PagedPhase.Empty -> Message { EmptyMessage(stringResource(R.string.search_nothing, text), it) }
                PagedPhase.Loaded -> AuthorRows(authors, onOpenAuthor)
            }
        }
    }
}

/** A message in the place of the list. It scrolls, so that pulling down to refresh works on it too. */
@Composable
private fun Message(content: @Composable (Modifier) -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item { content(Modifier.fillParentMaxSize()) }
    }
}

/**
 * Rows are keyed by position, not by author: with offset paging an author added on the server
 * between two pages comes twice, and a lazy list throws on a key used twice.
 */
@Composable
private fun AuthorRows(authors: LazyPagingItems<Author>, onOpenAuthor: (AuthorId) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(CollectionEdgePadding)) {
        items(count = authors.itemCount, key = { index -> "author-$index" }) { index ->
            val author = authors[index]
            if (author == null) {
                Box(modifier = Modifier.heightIn(min = AuthorRowHeight))
            } else {
                AuthorResultRow(author, onClick = { onOpenAuthor(author.id) })
            }
        }
    }
}
