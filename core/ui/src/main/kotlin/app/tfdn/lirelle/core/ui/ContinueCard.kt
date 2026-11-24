package app.tfdn.lirelle.core.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import app.tfdn.lirelle.core.designsystem.LocalWindowWidthClass
import app.tfdn.lirelle.core.designsystem.WindowWidthClass
import app.tfdn.lirelle.core.library.model.BookSummary
import app.tfdn.lirelle.core.library.model.CoverShape
import java.time.Instant

const val ContinueWhenTag = "continue-when"

private val CardShape = RoundedCornerShape(12.dp)
private val CoverZoneShape = RoundedCornerShape(8.dp)
private val CardCoverWidth = 68.dp
private val WideCardCoverWidth = 110.dp
private val CardPadding = 10.dp
private val CardGap = 12.dp
private val LineGap = 2.dp
private val MarkGap = 8.dp
private val MarkInset = 5.dp

/** The way a book is gone on with: by reading its text, or by listening to it. */
enum class BookMedium { Read, Listen }

/**
 * A book being read or listened to: its cover, what it is and when the user last read it; under it,
 * one button per medium the book has, each with how far the user is: the reading percentage, the
 * listening time left ([BookSummary.timeLeftSeconds]; the headphones alone while it is not known).
 *
 * Every text in it is a label on one line: cards sit side by side at one width and one height,
 * whatever the titles. A screen reader reads the cover and the texts as one item, which opens the
 * book's page, then each button by what it does ("Read *Title*").
 *
 * On a wide window the cover is about as large as those of the rows under it, and the texts
 * are one step larger.
 *
 * @param now the moment the date of the last reading is counted back from.
 * @param lastMedium the medium the user went on with last: its button stands out a little. Null when
 *   it is not known.
 */
@Composable
fun ContinueCard(
    book: BookSummary,
    onOpenBook: () -> Unit,
    onRead: () -> Unit,
    onListen: () -> Unit,
    modifier: Modifier = Modifier,
    now: Instant = Instant.now(),
    lastMedium: BookMedium? = null,
) {
    val wide = LocalWindowWidthClass.current == WindowWidthClass.Expanded
    val justNow = stringResource(R.string.relative_just_now)
    val lastRead = book.lastActivityAt?.let { relativeTime(it, now, justNow) }
    val mark = downloadMarkOf(book.id)
    val markWords = downloadMarkWords(mark)
    val description = buildList {
        add(book.title)
        addAll(book.authors)
        book.media.descriptionRes()?.let { add(stringResource(it)) }
        lastRead?.let(::add)
        markWords?.let(::add)
    }.joinToString(", ")
    Surface(
        modifier = modifier.testTag("continue-${book.id}"),
        shape = CardShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        // As tall as its content, or as the row makes it: then the buttons go to its bottom, in line with
        // those of the other cards.
        Column(modifier = Modifier.padding(CardPadding), verticalArrangement = Arrangement.SpaceBetween) {
            Row(
                modifier = Modifier
                    // Outside the zone that opens the page: the least space above the buttons.
                    .padding(bottom = CardGap)
                    .testTag("continue-open-${book.id}")
                    .clip(CoverZoneShape)
                    .clickable(onClickLabel = stringResource(R.string.book_open_page), onClick = onOpenBook)
                    // After the click: the click stays, the texts inside are said as one description.
                    .clearAndSetSemantics {
                        contentDescription = description
                        role = Role.Button
                    }
                    .height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(CardGap),
            ) {
                // The server's cover of a card is its preferred medium's: an audiobook's own, square mostly. The
                // room is a portrait cover's, and a square one sits in its middle.
                Box(
                    modifier = Modifier
                        .width(if (wide) WideCardCoverWidth else CardCoverWidth)
                        .aspectRatio(CoverShape.Portrait.widthOverHeight),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    // As large as the cover: the mark is at the corner of a square one too.
                    Box {
                        BookCover(
                            url = book.coverUrl,
                            title = book.title,
                            shape = book.coverShape,
                            modifier = Modifier
                                .testTag("continue-cover-${book.id}")
                                .fillMaxWidth(),
                            showTitleWhenMissing = false,
                        )
                        if (mark != null) {
                            DownloadMarkBadge(
                                mark = mark,
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(MarkInset),
                            )
                        }
                    }
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(LineGap)) {
                    CardLines(book, lastRead, wide)
                }
            }
            MediumButtons(book, onRead, onListen, lastMedium)
        }
    }
}

/** The title, the authors, then the format beside the date of the last reading. */
@Composable
private fun CardLines(book: BookSummary, lastRead: String?, wide: Boolean) {
    val typography = MaterialTheme.typography
    val small = if (wide) typography.bodyMedium else typography.bodySmall
    Text(
        text = book.title,
        style = if (wide) typography.titleMedium else typography.titleSmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    Text(
        text = book.authors.joinToString(", "),
        style = small,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(MarkGap), verticalAlignment = Alignment.CenterVertically) {
        // The badge has a fixed size and is measured first; the date has the rest of the line.
        FormatBadge(book.media, onPage = true)
        if (lastRead != null) {
            Text(
                text = lastRead,
                modifier = Modifier.testTag(ContinueWhenTag),
                style = small,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
