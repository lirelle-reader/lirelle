package app.tfdn.lirelle.feature.book

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.ids.AuthorId
import app.tfdn.lirelle.core.library.model.BookDetail
import app.tfdn.lirelle.core.ui.BookCover
import app.tfdn.lirelle.core.ui.FormatLabel
import app.tfdn.lirelle.core.ui.PagedLabel

internal const val BookCoverTag = "book-cover"
internal const val BookTitleTag = "book-title"
internal const val BookEditionNameTag = "book-edition-name"

private val StackedCoverMaxWidth = 220.dp
private val CoverGap = 16.dp

/** Beside the texts, the cover takes at most one third of the width. */
private const val SideCoverMaxShare = 3

/**
 * The narrowest column the texts are set in beside the cover, at the default text size. It grows
 * with the text size of the system: words twice as large need a column twice as wide.
 */
private val MinTextColumnWidth = 140.dp

// ---------- 1. Header ----------

/**
 * The cover and what names the book.
 *
 * @param onOpenAuthor null when the source has no author pages: the names are plain text, opening nothing.
 * @param sideCoverWidth the width of the cover when the texts are set beside it, which they are
 *   whenever the width left for them allows it; null to always set them under a large cover.
 */
@Composable
internal fun HeaderBlock(book: BookDetail, onOpenAuthor: ((AuthorId) -> Unit)?, sideCoverWidth: Dp? = null) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("book-header"),
    ) {
        // From the room there is and the text size, not from the kind of device: a narrow screen
        // with very large text gets the cover above the texts, so that no word is squeezed.
        val neededForTexts = MinTextColumnWidth * LocalDensity.current.fontScale
        // On a narrow screen the cover gives way: it never takes more than its share of the width.
        val coverWidth = sideCoverWidth?.coerceAtMost(maxWidth / SideCoverMaxShare)
        if (coverWidth != null && maxWidth - coverWidth - CoverGap >= neededForTexts) {
            SideBySideHeader(book, onOpenAuthor, coverWidth)
        } else {
            StackedHeader(book, onOpenAuthor)
        }
    }
}

@Composable
private fun SideBySideHeader(book: BookDetail, onOpenAuthor: ((AuthorId) -> Unit)?, coverWidth: Dp) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(CoverGap),
        verticalAlignment = Alignment.Top,
    ) {
        HeaderCover(book, Modifier.width(coverWidth))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            HeaderTexts(book, onOpenAuthor, MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun StackedHeader(book: BookDetail, onOpenAuthor: ((AuthorId) -> Unit)?) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HeaderCover(book, Modifier.widthIn(max = StackedCoverMaxWidth))
        HeaderTexts(book, onOpenAuthor, MaterialTheme.typography.headlineSmall)
    }
}

/** The title is right next to the cover: a book without one gets a plain tile. */
@Composable
private fun HeaderCover(book: BookDetail, modifier: Modifier) {
    BookCover(
        url = book.coverUrl,
        title = book.title,
        shape = book.coverShape,
        modifier = modifier.testTag(BookCoverTag),
        showTitleWhenMissing = false,
    )
}

/** The texts of the header, one under the other in the column they are called from. */
@Composable
private fun HeaderTexts(book: BookDetail, onOpenAuthor: ((AuthorId) -> Unit)?, titleStyle: TextStyle) {
    // The title wraps between words; it is never cut.
    Text(
        text = book.title,
        modifier = Modifier
            .testTag(BookTitleTag)
            .semantics { heading() },
        style = titleStyle,
    )
    book.subtitle?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
    book.authors.forEach { author ->
        // Without author pages the name is plain text: no button, nor the colour of a link.
        Text(
            text = author.name,
            modifier = Modifier
                .testTag("author-${author.id}")
                .then(
                    if (onOpenAuthor == null) {
                        Modifier
                    } else {
                        // No taller than its text, so that it sits right under the title: Compose itself extends
                        // the touch target of a smaller clickable to the 48 dp minimum, around it, without layout.
                        Modifier.clickable(role = Role.Button) { onOpenAuthor(author.id) }
                    },
                ),
            style = MaterialTheme.typography.titleMedium,
            color = if (onOpenAuthor == null) Color.Unspecified else MaterialTheme.colorScheme.primary,
        )
    }
    book.series?.let { series ->
        Text(
            text = series.index?.let { stringResource(R.string.book_series_line, series.name, it) } ?: series.name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    // What sets this edition apart from the others of its work, as the block of the editions names it.
    book.editionName?.let {
        Text(
            text = it,
            modifier = Modifier.testTag(BookEditionNameTag),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    // A book read by pages says what its pages are; one that also has an audiobook carries both chips.
    book.pagedKind?.let { PagedLabel(it) }
    FormatLabel(book.media)
}
