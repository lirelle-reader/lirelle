package app.tfdn.lirelle.feature.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.designsystem.LocalWindowWidthClass
import app.tfdn.lirelle.core.designsystem.WindowWidthClass
import app.tfdn.lirelle.core.library.model.Author
import app.tfdn.lirelle.core.library.model.BookSummary
import app.tfdn.lirelle.core.library.model.CoverShape
import app.tfdn.lirelle.core.library.model.Series
import app.tfdn.lirelle.core.ui.AuthorPortrait
import app.tfdn.lirelle.core.ui.BookCover
import app.tfdn.lirelle.core.ui.BookListRow

private val RowMinHeight = 56.dp
private val RowShape = RoundedCornerShape(12.dp)

/** As wide as the cover of a book row, so the names of the three kinds of rows line up. */
private val LeadingWidth = 56.dp
private val SeriesCoverWidth = 40.dp
private val PortraitSize = 44.dp

/** On a wide window the rows have room: their picture is larger. */
private val WideLeadingWidth = 72.dp
private val WideSeriesCoverWidth = 52.dp
private val WidePortraitSize = 64.dp

/** A book found: the row of the book lists. */
@Composable
internal fun BookResultRow(book: BookSummary, onClick: () -> Unit, modifier: Modifier = Modifier) {
    // The row carries the tag of the book lists too; the one given from outside is the one found.
    BookListRow(book, onClick, modifier.testTag("result-book-${book.id}"))
}

/**
 * A series found: its first cover, its name and its number of books.
 *
 * @param showCount false when the source gives no number of books for a series in a list: the name stands alone.
 */
@Composable
internal fun SeriesResultRow(
    series: Series,
    showCount: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ResultRow(
        name = series.name,
        bookCount = series.bookCount.takeIf { showCount },
        onClick = onClick,
        modifier = modifier.testTag("result-series-${series.id}"),
    ) {
        // No title on the tile of a series without a cover: the name is next to it.
        BookCover(
            series.coverUrls.firstOrNull(),
            series.name,
            CoverShape.Portrait,
            Modifier.width(if (isWide()) WideSeriesCoverWidth else SeriesCoverWidth),
            showTitleWhenMissing = false,
        )
    }
}

/** An author found: the portrait, the name and the number of books. */
@Composable
internal fun AuthorResultRow(author: Author, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ResultRow(
        name = author.name,
        bookCount = author.bookCount,
        onClick = onClick,
        modifier = modifier.testTag("result-author-${author.id}"),
    ) {
        val size = if (isWide()) WidePortraitSize else PortraitSize
        AuthorPortrait(author.portraitUrl, author.name, Modifier.size(size))
    }
}

/** @param bookCount the number written under the name; none when null. */
@Composable
private fun ResultRow(
    name: String,
    bookCount: Int?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: @Composable () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = RowMinHeight)
            .clip(RowShape)
            // A button: its texts are one-line labels, and are read as one item.
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.width(if (isWide()) WideLeadingWidth else LeadingWidth),
            contentAlignment = Alignment.Center,
        ) { leading() }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = name,
                // The size of the large title, in the font of the texts around it rather than the serif.
                style = MaterialTheme.typography.run {
                    if (isWide()) titleLarge.copy(fontFamily = titleMedium.fontFamily) else titleMedium
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (bookCount != null) {
                Text(
                    text = pluralStringResource(R.plurals.search_book_count, bookCount, bookCount),
                    style = MaterialTheme.typography.run { if (isWide()) bodyLarge else bodyMedium },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun isWide(): Boolean = LocalWindowWidthClass.current == WindowWidthClass.Expanded
