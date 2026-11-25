package app.tfdn.lirelle.feature.book

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.model.BookDetail
import app.tfdn.lirelle.core.library.model.BookEdition
import app.tfdn.lirelle.core.library.model.CoverShape
import app.tfdn.lirelle.core.library.model.EditionMedia
import app.tfdn.lirelle.core.library.model.EditionProgress
import app.tfdn.lirelle.core.ui.BookCover

const val EditionsTag = "book-editions"

internal fun editionTag(id: BookId) = "edition-${id.value}"

/** The bar of an edition being read, on its line. */
internal const val EditionBarTag = "edition-bar"

private val EditionMinHeight = 72.dp
private val EditionCoverWidth = 36.dp
private val EditionGap = 12.dp
private val EditionBarHeight = 4.dp
private const val EditionPercentMax = 100f

// ---------- 3b. Editions ----------

/**
 * The editions of the work, this book among them: the list as its source orders it, [columns] lines per row.
 * Nothing for a book that has no other edition.
 */
@Composable
internal fun EditionsBlock(book: BookDetail, onOpenBook: (BookId) -> Unit, columns: Int = 1) {
    if (book.editions.size < 2) return
    Section(R.string.section_editions, EditionsTag) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            book.editions.chunked(columns).forEach { row ->
                // The lines of a row are as tall as the tallest of them.
                Row(
                    modifier = Modifier.height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(EditionGap),
                ) {
                    row.forEach { edition ->
                        EditionLine(
                            edition = edition,
                            openTitle = book.title,
                            onOpenBook = onOpenBook,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                        )
                    }
                    // A last row that is not full keeps the width of its lines.
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/**
 * One edition: its cover, what names it, what it is made of and where the reader is in it.
 *
 * In this module a text under a `Role.Button` node must stay on one line (the text-fit rule of `LaidOutText`),
 * and these texts wrap as they need. So what takes the tap and is read is not around the texts: it is a sibling
 * layer, as large as the line, which says the line in one sentence; the texts themselves are hidden from
 * accessibility, so that they are not read a second time.
 *
 * The layer is over the texts, not under them: a finger exploring the screen with TalkBack finds the topmost
 * sibling only, and under the texts it would find a hidden text, and say nothing. The ripple is drawn over
 * the line for the same reason.
 *
 * @param openTitle the title of the book whose page this is.
 */
@Composable
private fun EditionLine(edition: BookEdition, openTitle: String, onOpenBook: (BookId) -> Unit, modifier: Modifier) {
    val firstLine = edition.label ?: edition.title
    // The title is said once: not under a line that already is the title, nor on the page that carries it.
    val titleLine = edition.title.takeIf { edition.label != null && it.isNotBlank() && it != openTitle }
    val details = editionDetails(edition)
    val mark = if (edition.isOpen) stringResource(R.string.edition_this) else null
    // Said with commas: the dot that joins the media and the progress is for the eye.
    val spokenDetails = editionDetails(edition, spoken = true)
    val spoken = listOfNotNull(firstLine, titleLine, spokenDetails, mark).joinToString(", ")
    val openLabel = stringResource(R.string.edition_open)
    // The open edition stands out, and leads nowhere: its page is the one shown.
    val ground = if (edition.isOpen) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent
    Box(
        modifier = modifier
            .heightIn(min = EditionMinHeight)
            .clip(MaterialTheme.shapes.medium)
            .background(ground),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(EditionGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BookCover(
                url = edition.coverUrl,
                title = edition.title,
                shape = CoverShape.Portrait,
                modifier = Modifier.width(EditionCoverWidth),
                showTitleWhenMissing = false,
            )
            Column(modifier = Modifier.weight(1f)) {
                val secondary = MaterialTheme.colorScheme.onSurfaceVariant
                val named = MaterialTheme.typography.bodyLarge
                EditionText(firstLine, if (edition.isOpen) named.copy(fontWeight = FontWeight.Medium) else named)
                titleLine?.let { EditionText(it, MaterialTheme.typography.bodyMedium, secondary) }
                EditionText(details, MaterialTheme.typography.bodyMedium, secondary)
                (edition.progress as? EditionProgress.InProgress)?.let { EditionBar(it.percent) }
                // The last line of the texts, not the end of the row: there, large text would squeeze the label.
                mark?.let { EditionText(it, MaterialTheme.typography.labelMedium, MaterialTheme.colorScheme.primary) }
            }
            if (!edition.isOpen) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null)
        }
        // The last of the siblings, so over the texts: the one a finger exploring the screen finds.
        Box(
            modifier = Modifier
                .matchParentSize()
                .testTag(editionTag(edition.bookId))
                .then(
                    if (edition.isOpen) {
                        Modifier
                    } else {
                        Modifier.clickable(onClickLabel = openLabel, role = Role.Button) { onOpenBook(edition.bookId) }
                    },
                )
                .semantics { contentDescription = spoken },
        )
    }
}

/** A text of a line, which wraps rather than being cut. Its line says it: it is not read by itself. */
@Composable
private fun EditionText(text: String, style: TextStyle, color: Color = Color.Unspecified) {
    Text(text, modifier = Modifier.semantics { hideFromAccessibility() }, style = style, color = color)
}

/** How far the reader is, drawn. It says nothing: the percentage is in the text above it. */
@Composable
private fun EditionBar(percent: Int) {
    LinearProgressIndicator(
        progress = { percent / EditionPercentMax },
        modifier = Modifier
            .padding(vertical = 4.dp)
            .fillMaxWidth()
            .height(EditionBarHeight)
            .clearAndSetSemantics { testTag = EditionBarTag },
        // No dot at the end of the track: it reads as progress.
        drawStopIndicator = {},
    )
}

/** "Book and audio · 42% read"; the media alone when the progress is not known. */
@Composable
private fun editionDetails(edition: BookEdition, spoken: Boolean = false): String {
    val media = stringResource(
        when (edition.media) {
            EditionMedia.Text -> R.string.edition_media_text
            EditionMedia.Audio -> R.string.edition_media_audio
            EditionMedia.TextAndAudio -> R.string.edition_media_text_and_audio
            EditionMedia.Comic -> R.string.edition_media_comic
        },
    )
    val progress = when (val at = edition.progress) {
        EditionProgress.NotStarted -> stringResource(R.string.edition_progress_not_started)
        is EditionProgress.InProgress -> stringResource(R.string.edition_progress_percent, at.percent)
        EditionProgress.Finished -> stringResource(R.string.edition_progress_finished)
        EditionProgress.Unknown -> null
    }
    val joined = if (spoken) R.string.edition_details_spoken else R.string.edition_details
    return if (progress == null) media else stringResource(joined, media, progress)
}
