package app.tfdn.lirelle.core.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.library.model.MediaProfile
import app.tfdn.lirelle.core.library.model.PagedKind

const val FormatBadgeTag = "format-badge"
const val PagedLabelTag = "paged-label"

private val LabelMinHeight = 28.dp

/** Half of [LabelMinHeight]: a pill on one line, and corners that stay clear of the text on several. */
private val LabelShape = RoundedCornerShape(LabelMinHeight / 2)

/** The words for a media profile; null for text-only books, which carry no badge. */
@StringRes
fun MediaProfile.descriptionRes(): Int? = when (this) {
    MediaProfile.TextOnly -> null
    MediaProfile.AudioOnly -> R.string.media_audio_only
    MediaProfile.TextAndAudio -> R.string.media_text_and_audio
    MediaProfile.ReadAlong -> R.string.media_read_along
}

private fun MediaProfile.glyphs(): List<ImageVector> = when (this) {
    MediaProfile.TextOnly -> emptyList()
    MediaProfile.AudioOnly -> listOf(Icons.Outlined.Headphones)
    MediaProfile.TextAndAudio -> listOf(Icons.AutoMirrored.Outlined.MenuBook, Icons.Outlined.Headphones)
    MediaProfile.ReadAlong -> listOf(Icons.AutoMirrored.Outlined.MenuBook, Icons.Outlined.GraphicEq)
}

@Composable
private fun Glyphs(media: MediaProfile, tint: Color, size: Dp) {
    media.glyphs().forEach { Icon(it, contentDescription = null, tint = tint, modifier = Modifier.size(size)) }
}

/**
 * The small badge of a book: over its cover in a grid, at the end of its row in a list.
 * Read-along differs from the other states by its glyphs and by its amber ground, so it does
 * not rely on colour alone.
 */
@Composable
fun FormatBadge(media: MediaProfile, modifier: Modifier = Modifier, onPage: Boolean = false) {
    val description = media.descriptionRes()?.let { stringResource(it) } ?: return
    val isReadAlong = media == MediaProfile.ReadAlong
    // Over a cover the dark ground stands out; set on the page itself it would vanish into it.
    val plain = if (onPage) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surface
    val ground = if (isReadAlong) MaterialTheme.colorScheme.primary else plain
    val ink = if (isReadAlong) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = modifier
            .testTag(FormatBadgeTag)
            .clearAndSetSemantics { contentDescription = description }
            .height(22.dp)
            .background(ground, CircleShape)
            .padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyphs(media, ink, 13.dp)
    }
}

/**
 * The same state in words, for the book page. It grows with its text: at a large text size
 * the words wrap onto several lines, they are never cut.
 */
@Composable
fun FormatLabel(media: MediaProfile, modifier: Modifier = Modifier) {
    val description = media.descriptionRes()?.let { stringResource(it) } ?: return
    val isReadAlong = media == MediaProfile.ReadAlong
    val ground = if (isReadAlong) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh
    val ink = if (isReadAlong) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    LabelChip(description, ground, ink, modifier.testTag(FormatBadgeTag)) { Glyphs(media, ink, 14.dp) }
}

/** The words for what the pages of a book read by pages are: "Comic", "PDF", "DjVu". */
@StringRes
fun PagedKind.labelRes(): Int = when (this) {
    PagedKind.Comic -> R.string.media_comic
    PagedKind.Pdf -> R.string.media_pdf
    PagedKind.Djvu -> R.string.media_djvu
}

fun PagedKind.glyph(): ImageVector = when (this) {
    // Panels of unequal sizes, as on a page of a comic.
    PagedKind.Comic -> Icons.Outlined.Dashboard
    PagedKind.Pdf -> Icons.Outlined.PictureAsPdf
    // A sheet with its lines: a document that was scanned.
    PagedKind.Djvu -> Icons.Outlined.Description
}

/**
 * Says, on the book page, what a book read by pages is: a comic, a PDF, a DjVu, read in the page reader. Such a
 * book is a text for its media profile and so has no badge of that kind; this one is its own, in the same shape.
 */
@Composable
fun PagedLabel(kind: PagedKind, modifier: Modifier = Modifier) {
    val ink = MaterialTheme.colorScheme.onSurface
    LabelChip(
        text = stringResource(kind.labelRes()),
        ground = MaterialTheme.colorScheme.surfaceContainerHigh,
        ink = ink,
        modifier = modifier.testTag(PagedLabelTag),
    ) {
        Icon(kind.glyph(), contentDescription = null, tint = ink, modifier = Modifier.size(14.dp))
    }
}

/** A pill with its [glyphs] before its [text], which names it for a screen reader too. */
@Composable
private fun LabelChip(
    text: String,
    ground: Color,
    ink: Color,
    modifier: Modifier = Modifier,
    glyphs: @Composable () -> Unit,
) {
    Row(
        modifier = modifier
            .heightIn(min = LabelMinHeight)
            .background(ground, LabelShape)
            .padding(horizontal = 10.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        glyphs()
        Text(text, color = ink)
    }
}
