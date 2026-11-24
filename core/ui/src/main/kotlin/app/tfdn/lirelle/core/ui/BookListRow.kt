package app.tfdn.lirelle.core.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.library.model.BookSummary
import app.tfdn.lirelle.core.library.model.SeriesRef

private val ListCoverWidth = 56.dp

/** "Name #4", or the name alone for a book without a volume number. */
@Composable
private fun SeriesRef.volumeLabel(): String =
    index?.let { stringResource(R.string.book_series_volume, name, it) } ?: name

@Composable
fun BookListRow(book: BookSummary, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val seriesLabel = book.series?.volumeLabel()
    val mark = downloadMarkOf(book.id)
    val description = listOfNotNull(book.spokenDescription(), seriesLabel, downloadMarkWords(mark))
        .joinToString(", ")
    Row(
        modifier = modifier
            .testTag("book-${book.id}")
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
            }
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DecoratedCover(book, Modifier.width(ListCoverWidth), badged = false)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = book.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = book.authors.joinToString(", "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (seriesLabel != null) {
                Text(
                    text = seriesLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // After the text, not over the cover, which is too small here. The mark and the badge have a fixed
        // size and the text has the rest of the row; without a mark, or for a text-only book, which has no
        // badge, nothing is placed.
        if (mark != null) DownloadMarkBadge(mark)
        FormatBadge(book.media, onPage = true)
    }
}
