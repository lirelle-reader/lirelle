package app.tfdn.lirelle.feature.book

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.model.RelatedBook
import app.tfdn.lirelle.core.ui.BookCover
import app.tfdn.lirelle.core.ui.CoverMarks
import app.tfdn.lirelle.core.ui.DownloadMarkBadge
import app.tfdn.lirelle.core.ui.coverMarkWords
import app.tfdn.lirelle.core.ui.downloadMarkOf
import app.tfdn.lirelle.core.ui.downloadMarkWords

private val RelatedCoverWidth = 104.dp

// ---------- 8. Related books ----------

/**
 * @param showSeriesIndex whether covers carry the volume number of their book: in the row of the series.
 * @param onOpenAll opens the series the row shows a part of; its heading is then a line that takes the tap.
 */
@Composable
internal fun RelatedBlock(
    @StringRes title: Int,
    tag: String,
    books: List<RelatedBook>,
    onOpenBook: (BookId) -> Unit,
    showSeriesIndex: Boolean = false,
    onOpenAll: (() -> Unit)? = null,
) {
    if (books.isEmpty()) return
    Section(title, tag, onOpenAll, R.string.action_open_series) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(end = 4.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            // A key must go in a bundle: the id's text, not the id.
            items(books, key = { it.id.value }) { related ->
                RelatedItem(related, related.seriesIndex.takeIf { showSeriesIndex }, onOpenBook)
            }
        }
    }
}

@Composable
private fun RelatedItem(related: RelatedBook, seriesIndex: String?, onOpenBook: (BookId) -> Unit) {
    // The marks of the cover are not announced themselves: the item says them in words after the
    // title. A book without marks is announced by its title, with no description of its own.
    val mark = downloadMarkOf(related.id)
    val words = coverMarkWords(related.isRead, seriesIndex) + listOfNotNull(downloadMarkWords(mark))
    val spoken = if (words.isEmpty()) null else (listOf(related.title) + words).joinToString(", ")
    Column(
        modifier = Modifier
            .width(RelatedCoverWidth)
            .testTag("related-${related.id}")
            .clickable(role = Role.Button) { onOpenBook(related.id) }
            .then(if (spoken == null) Modifier else Modifier.semantics { contentDescription = spoken }),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box {
            BookCover(related.coverUrl, related.title, related.coverShape, showTitleWhenMissing = false)
            if (mark != null) {
                DownloadMarkBadge(
                    mark = mark,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(5.dp),
                )
            }
            CoverMarks(related.isRead, seriesIndex, Modifier.align(Alignment.BottomCenter))
        }
        Text(
            text = related.title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
