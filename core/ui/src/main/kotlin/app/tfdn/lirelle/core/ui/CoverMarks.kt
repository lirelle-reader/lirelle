package app.tfdn.lirelle.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

const val ReadMarkTag = "read-mark"
const val SeriesIndexTag = "series-index"
const val BookProgressTag = "book-progress"

/** The size of the format badge: the marks over a cover are one family. */
private val MarkSize = 22.dp
private val MarkInset = 5.dp

private fun String?.orNullIfBlank(): String? = this?.takeIf { it.isNotBlank() }

/**
 * What the marks of a cover say, in words, for the description of the item that holds the cover:
 * the marks themselves are not announced.
 */
@Composable
fun coverMarkWords(isRead: Boolean, seriesIndex: String?): List<String> = listOfNotNull(
    seriesIndex.orNullIfBlank()?.let { stringResource(R.string.book_volume_description, it) },
    if (isRead) stringResource(R.string.book_read_description) else null,
)

/**
 * The marks along the bottom edge of a cover: a check at the start for a book that was read, the
 * volume number at the end. To be aligned at the bottom of the box that holds the cover.
 *
 * @param seriesIndex the volume number to show, null where the context is not a series.
 */
@Composable
fun CoverMarks(isRead: Boolean, seriesIndex: String?, modifier: Modifier = Modifier) {
    val index = seriesIndex.orNullIfBlank()
    if (!isRead && index == null) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(MarkInset),
        verticalAlignment = Alignment.Bottom,
    ) {
        if (isRead) ReadMark()
        // The number is measured before this gap: it has all the width the check leaves, and is
        // cut short with an ellipsis rather than drawn over the check.
        Spacer(Modifier.weight(1f))
        if (index != null) SeriesIndexPill(index, Modifier.padding(start = 4.dp))
    }
}

/** A check, not a colour: the shape of the glyph carries the state. */
@Composable
private fun ReadMark() {
    Box(
        modifier = Modifier
            .testTag(ReadMarkTag)
            .size(MarkSize)
            .background(MaterialTheme.colorScheme.surface, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.Check,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(14.dp),
        )
    }
}

/** A disc for one digit, a pill for more. It grows with the text size; it is never smaller than the check. */
@Composable
private fun SeriesIndexPill(index: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .testTag(SeriesIndexTag)
            .defaultMinSize(minWidth = MarkSize, minHeight = MarkSize)
            .background(MaterialTheme.colorScheme.surface, CircleShape)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = index,
            modifier = Modifier.semantics { hideFromAccessibility() },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
