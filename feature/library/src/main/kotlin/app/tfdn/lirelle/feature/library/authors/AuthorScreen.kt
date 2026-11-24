package app.tfdn.lirelle.feature.library.authors

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tfdn.lirelle.core.ids.AuthorId
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ui.AuthorPortrait
import app.tfdn.lirelle.core.ui.BookCollection
import app.tfdn.lirelle.core.ui.CollapsibleText
import app.tfdn.lirelle.core.ui.DetailScaffold
import app.tfdn.lirelle.core.ui.Loadable
import app.tfdn.lirelle.core.ui.LoadableContent
import app.tfdn.lirelle.core.ui.NotOffered
import app.tfdn.lirelle.core.ui.bookItemsOf
import app.tfdn.lirelle.core.ui.placeholderBookItems
import app.tfdn.lirelle.feature.library.R
import app.tfdn.lirelle.feature.library.books.PresentationButton

/** An author: portrait, collapsible biography, then their books. Without authors at the source, it goes back. */
@Composable
fun AuthorScreen(
    authorId: AuthorId,
    onBack: () -> Unit,
    onOpenBook: (BookId) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AuthorViewModel = hiltViewModel<AuthorViewModel, AuthorViewModel.Factory>(
        key = "author-$authorId",
        creationCallback = { it.create(authorId.value) },
    ),
) {
    if (!viewModel.offered) {
        NotOffered(onBack, modifier)
        return
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    DetailScaffold(
        // No title until the name is known: an empty one would be an empty heading.
        title = (state.content as? Loadable.Ready)?.value?.detail?.author?.name,
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
                header = { item(key = "heading") { AuthorHeading(content) } },
            )
        }
    }
}

@Composable
private fun AuthorHeading(content: AuthorContent) {
    val author = content.detail.author
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            AuthorPortrait(author.portraitUrl, author.name, Modifier.size(88.dp))
            Text(
                text = pluralStringResource(R.plurals.books_count, content.books.size, content.books.size),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        content.detail.biography?.takeIf { it.isNotBlank() }?.let { CollapsibleText(html = it) }
    }
}
