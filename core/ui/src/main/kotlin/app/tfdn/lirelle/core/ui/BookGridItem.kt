package app.tfdn.lirelle.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
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
import app.tfdn.lirelle.core.library.model.CoverShape
import app.tfdn.lirelle.core.library.offline.DownloadMark

const val BookPlaceholderTag = "book-placeholder"

private const val PercentScale = 100f
private const val PlaceholderTitleWidth = 0.8f
private const val PlaceholderAuthorWidth = 0.5f

/**
 * What a screen reader says for a book: title, authors, format state, the marks of its cover, progress.
 *
 * @param seriesIndex the volume number shown on the cover, null when it is not shown there.
 * @param mark the mark of the book on this device, said last; null for none, or where it is said elsewhere.
 */
@Composable
internal fun BookSummary.spokenDescription(
    seriesIndex: String? = null,
    mark: DownloadMark? = null,
): String = buildList {
    add(title)
    addAll(authors)
    media.descriptionRes()?.let { add(stringResource(it)) }
    addAll(coverMarkWords(isRead, seriesIndex))
    if (isInProgress) add(stringResource(R.string.book_progress_description, progressPercent ?: 0))
    downloadMarkWords(mark)?.let(::add)
}.joinToString(", ")

/**
 * Cover with, for a book in progress, a progress bar along its bottom edge, and for a book that
 * was read a check in its bottom corner: never both, a read book is not in progress.
 *
 * @param badged whether the format badge is drawn over the top corner of the cover. A cover too
 *   small to carry it without being hidden goes without, and the badge is shown beside it.
 * @param seriesIndex the volume number to show over the cover, null for none.
 * @param mark the mark of the book on this device, drawn over the other top corner; null for none, or
 *   where it is shown beside the cover as the format badge is.
 */
@Composable
internal fun DecoratedCover(
    book: BookSummary,
    modifier: Modifier = Modifier,
    badged: Boolean = true,
    seriesIndex: String? = null,
    mark: DownloadMark? = null,
) {
    Box(modifier = modifier) {
        BookCover(book.coverUrl, book.title, book.coverShape, Modifier.testTag("cover-${book.id}"))
        if (badged) {
            FormatBadge(
                media = book.media,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(5.dp),
            )
        }
        if (mark != null) {
            DownloadMarkBadge(
                mark = mark,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(5.dp),
            )
        }
        CoverMarks(book.isRead, seriesIndex, Modifier.align(Alignment.BottomCenter))
        if (book.isInProgress) {
            LinearProgressIndicator(
                progress = { (book.progressPercent ?: 0) / PercentScale },
                modifier = Modifier
                    .testTag(BookProgressTag)
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(4.dp),
                drawStopIndicator = {},
            )
        }
    }
}

/**
 * @param showSeriesIndex whether the cover carries the volume number of the book, as on the screen of a series.
 * @param secondLine the line under the title: the authors, unless the place calls for something else.
 */
@Composable
fun BookGridItem(
    book: BookSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showSeriesIndex: Boolean = false,
    secondLine: String = book.authors.joinToString(", "),
) {
    val seriesIndex = book.series?.index.takeIf { showSeriesIndex }
    val mark = downloadMarkOf(book.id)
    val description = book.spokenDescription(seriesIndex, mark)
    Column(
        modifier = modifier
            .testTag("book-${book.id}")
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
            },
    ) {
        DecoratedCover(book, seriesIndex = seriesIndex, mark = mark)
        Text(
            text = book.title,
            modifier = Modifier.padding(top = 6.dp),
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = secondLine,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Stands for a book that is not loaded yet. Always portrait: the real shape is not known. */
@Composable
fun BookPlaceholder(modifier: Modifier = Modifier) {
    Column(modifier = modifier.testTag(BookPlaceholderTag)) {
        BookCover(null, "", CoverShape.Portrait, showTitleWhenMissing = false)
        Box(
            Modifier
                .padding(top = 8.dp)
                .fillMaxWidth(fraction = PlaceholderTitleWidth)
                .height(12.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(4.dp)),
        )
        Box(
            Modifier
                .padding(top = 6.dp)
                .fillMaxWidth(fraction = PlaceholderAuthorWidth)
                .height(10.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(4.dp)),
        )
    }
}
