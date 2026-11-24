package app.tfdn.lirelle.feature.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.library.model.MarkOrigin
import app.tfdn.lirelle.core.reader.marks.Bookmark
import app.tfdn.lirelle.core.reader.marks.Highlight
import app.tfdn.lirelle.core.reader.marks.HighlightColour
import app.tfdn.lirelle.core.reader.marks.spineIndexOf

/** Between the details of a row: chapter, date, origin. */
private const val DetailSeparator = " · "

private const val QuoteLines = 3

/** A bookmark's title is the label of its chapter, which an older bookmark may hold as a whole paragraph. */
private const val TitleLines = 2

private val RowMinHeight = 56.dp
private val RowGutter = 24.dp
private val RowVerticalPadding = 12.dp
private val ColourBarWidth = 4.dp
private val BarGap = 12.dp

/** As large as its touch target. */
private val DeleteButtonSize = 48.dp

/**
 * One bookmark, [day] being the day it was made: its title on two lines at most, its note whole. A tap
 * goes there, the button deletes it. One that has no place in this file cannot be gone to.
 */
@Composable
internal fun BookmarkRow(
    bookmark: Bookmark,
    chapter: String?,
    day: String,
    onOpen: (String) -> Unit,
    onDelete: () -> Unit,
) {
    val cfi = bookmark.cfi
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = cfi != null) { cfi?.let(onOpen) }
                .heightIn(min = RowMinHeight)
                .padding(start = 24.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = bookmark.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = TitleLines,
                    overflow = TextOverflow.Ellipsis,
                )
                bookmark.note?.takeIf { it.isNotBlank() }?.let {
                    Text(text = it, style = MaterialTheme.typography.bodyMedium)
                }
                Details(
                    chapter = chapter?.takeUnless { it == bookmark.title },
                    day = day,
                    origin = bookmark.origin,
                    placement = R.string.reader_mark_not_found.takeIf { cfi == null },
                )
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(DeleteButtonSize)) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.reader_bookmark_delete, bookmark.title),
                )
            }
        }
        HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp))
    }
}

/**
 * One highlight, [day] being the day it was made: a bar of its colour, the text on three lines at
 * most, the note whole, then the details. A tap goes there; an unplaced highlight goes to its
 * chapter, or nowhere when that is not known either.
 */
@Composable
internal fun NoteRow(highlight: Highlight, chapter: String?, day: String, onOpen: () -> Unit) {
    val reachable = highlight.cfi != null || highlight.chapterIndex != null
    val colour = highlightColour(highlight.color)
    Column {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = reachable, onClick = onOpen)
                .drawBehind { drawColourBar(colour) }
                .padding(start = RowGutter + ColourBarWidth + BarGap, end = RowGutter)
                .padding(vertical = RowVerticalPadding),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = highlight.text,
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = FontFamily.Serif,
                fontStyle = FontStyle.Italic,
                maxLines = QuoteLines,
                overflow = TextOverflow.Ellipsis,
            )
            highlight.note?.takeIf { it.isNotBlank() }?.let {
                Text(text = it, style = MaterialTheme.typography.bodyMedium)
            }
            Details(
                chapter = chapter,
                day = day,
                origin = highlight.origin,
                placement = R.string.reader_mark_approximate.takeIf { highlight.cfi == null },
            )
        }
        HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp))
    }
}

/**
 * The chapter, the day, "from an e-reader" and what is known of the place ([placement], a string),
 * those that apply, on as many lines as needed. All but the chapter in lower case.
 */
@Composable
private fun Details(chapter: String?, day: String, origin: MarkOrigin, placement: Int?) {
    val parts = listOfNotNull(
        chapter?.takeIf { it.isNotBlank() },
        day,
        stringResource(R.string.reader_mark_from_ereader).takeIf { origin != MarkOrigin.Web },
        placement?.let { stringResource(it) },
    )
    Text(
        text = parts.joinToString(DetailSeparator),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The chapter of a place: the label of the contents entry its section belongs to. */
internal fun sectionLabel(cfi: String?, sectionLabels: List<String?>): String? =
    cfi?.let(::spineIndexOf)?.let(sectionLabels::getOrNull)

/** The chapter of a highlight: the server's when it has it, else from the contents. */
internal fun Highlight.chapter(sectionLabels: List<String?>): String? =
    chapterTitle?.takeIf { it.isNotBlank() }
        ?: sectionLabel(cfi, sectionLabels)
        ?: chapterIndex?.let(sectionLabels::getOrNull)

/** The bar at the start of a highlight's row, the height of its texts. */
private fun DrawScope.drawColourBar(colour: Color) {
    val width = ColourBarWidth.toPx()
    val inset = RowGutter.toPx()
    val left = if (layoutDirection == LayoutDirection.Rtl) size.width - inset - width else inset
    val top = RowVerticalPadding.toPx()
    drawRoundRect(
        color = colour,
        topLeft = Offset(left, top),
        size = Size(width, (size.height - 2 * top).coerceAtLeast(0f)),
        cornerRadius = CornerRadius(width / 2),
    )
}

/** A stored colour as drawn: [HighlightColour.display] makes any value a `#RRGGBB`. */
private fun highlightColour(stored: String): Color =
    Color(android.graphics.Color.parseColor(HighlightColour.display(stored)))
