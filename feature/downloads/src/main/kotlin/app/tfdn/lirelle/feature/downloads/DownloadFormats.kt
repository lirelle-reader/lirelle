package app.tfdn.lirelle.feature.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.TextName
import app.tfdn.lirelle.core.downloads.nameRes
import app.tfdn.lirelle.core.library.model.CoverShape
import app.tfdn.lirelle.core.library.model.PagedKind
import app.tfdn.lirelle.core.ui.BookCover
import app.tfdn.lirelle.core.ui.glyph
import app.tfdn.lirelle.core.ui.R as UiR

/** The bar of a row: how far the user is in a book, or how far its download is. */
const val RowProgressTag = "downloads-row-progress"

private const val PercentMax = 100f

/**
 * The glyphs the rest of the app uses for a text, an audiobook and a narration. A text read by pages, which
 * [text] says, has the glyph of the page reader's chip on its book's page: a comic's, a PDF's, a DjVu's.
 */
internal fun formatIcon(format: DownloadFormat, text: TextName): ImageVector = when (format) {
    DownloadFormat.Text -> text.pagedKind()?.glyph() ?: Icons.AutoMirrored.Outlined.MenuBook
    DownloadFormat.Audiobook -> Icons.Outlined.Headphones
    DownloadFormat.Narration -> Icons.Outlined.GraphicEq
}

/** What the pages of a text named so are; null for a text that is not read by pages. */
private fun TextName.pagedKind(): PagedKind? = when (this) {
    TextName.Text -> null
    TextName.Pdf -> PagedKind.Pdf
    TextName.Cbz, TextName.Cbr, TextName.Cb7 -> PagedKind.Comic
    TextName.Djvu -> PagedKind.Djvu
}

/** One glyph per format kept, each named for a screen reader: the text by what [text] says, "CBZ", "PDF". */
@Composable
internal fun FormatIcons(formats: List<DownloadFormat>, text: TextName, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        formats.forEach { format ->
            Icon(
                imageVector = formatIcon(format, text),
                contentDescription = stringResource(nameRes(format, text)),
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The cover the download kept, in the room of a portrait one: a square cover sits in its middle. Without a
 * kept cover, the plain tile of a book that has none.
 */
@Composable
internal fun KeptCover(address: String?, shape: CoverShape, modifier: Modifier = Modifier) {
    Box(modifier = modifier.aspectRatio(CoverShape.Portrait.widthOverHeight), contentAlignment = Alignment.Center) {
        // No title: it is beside the cover.
        BookCover(
            address,
            "",
            shape,
            modifier = Modifier.fillMaxWidth(),
            showTitleWhenMissing = false,
        )
    }
}

/** How far the user is in a book: the bar and the percentage of the other screens. */
@Composable
internal fun ReadingProgress(percent: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.testTag(RowProgressTag),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LinearProgressIndicator(
            progress = { percent / PercentMax },
            modifier = Modifier.weight(1f),
            // No dot at the end of the track: it reads as progress.
            drawStopIndicator = {},
        )
        Text(
            text = stringResource(UiR.string.progress_percent, percent),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
