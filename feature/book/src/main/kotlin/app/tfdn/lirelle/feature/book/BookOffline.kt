package app.tfdn.lirelle.feature.book

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.downloads.engine.offeredFormats
import app.tfdn.lirelle.core.downloads.engine.textFile
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.TextName
import app.tfdn.lirelle.core.downloads.nameRes
import app.tfdn.lirelle.core.library.model.BookDetail
import app.tfdn.lirelle.core.library.model.MediaProfile

// ---------- The page of a downloaded book, built from what the device keeps ----------

const val OfflineChipTag = "book-offline-chip"
const val GoneChipTag = "book-gone-chip"

/** The colours of the chip: its [ground] and what is written on it. */
internal class OfflineChipColors(val ground: Color, val ink: Color)

/**
 * The secondary container: of the tonal containers of the theme, the one that stands out most from the page
 * in both the dark and the light scheme (the tertiary one is close to the light page), with its own ink.
 */
@Composable
internal fun offlineChipColors() =
    OfflineChipColors(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)

/**
 * Says, at the top of the page, why it is built from what the device keeps: there is no network, or the book
 * is no longer on the server. It is in the middle of the page, on a ground that stands out from it, and is not
 * a button. What it says is one line; where that does not fit, it wraps, centred, rather than be cut.
 */
@Composable
internal fun OfflineChip(noNetwork: Boolean) {
    val colors = offlineChipColors()
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier.testTag(if (noNetwork) OfflineChipTag else GoneChipTag),
            shape = RoundedCornerShape(8.dp),
            color = colors.ground,
            contentColor = colors.ink,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (noNetwork) Icons.Outlined.WifiOff else Icons.Outlined.CloudOff,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = stringResource(if (noNetwork) R.string.book_offline_chip else R.string.book_gone_chip),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

/**
 * Which actions of the page open something: those the book has a file for, and, on a page built without the
 * server, only those whose format the device has whole.
 *
 * @param kept the formats the device has whole, on a page built without the server; null with it.
 * @param readAlongOpens whether the reader can have a read-along file: the account may download, or the device
 *   keeps the text.
 */
internal class Openings(book: BookDetail, private val kept: Set<DownloadFormat>?, readAlongOpens: Boolean = true) {
    private fun has(format: DownloadFormat) = kept == null || format in kept

    /**
     * The book has a narration the reader can play. It has none when the reader opens its plain EPUB in the
     * place of the read-along one, for an account that may not download; a book without a plain EPUB keeps
     * its narration, and the reader says why it cannot open.
     */
    val narrated = book.media == MediaProfile.ReadAlong &&
        (readAlongOpens || book.readerFile(canDownload = false)?.hasReadAlong != false)

    /**
     * A reader opens the book: the book reader its text, or the page reader its pages. Without the server it
     * opens what the device keeps as the book's text: its file, a PDF among them, or the pages of a comic or
     * of a DjVu, which the server serves page by page.
     */
    val read = book.media != MediaProfile.AudioOnly && when {
        kept == null -> book.isPaged || book.readerFile != null
        else -> book.textFile != null && has(DownloadFormat.Text)
    }

    /** The reader narrates the book, from the narration inside its read-along EPUB. */
    val narrate = read && has(DownloadFormat.Narration)

    /** The player plays the audiobook. */
    val listen = book.hasAudioFiles && has(DownloadFormat.Audiobook)
}

/**
 * Under the rings of the reading actions and the download tile: each format of the book the device does not
 * have whole. It waits for the network, unless the book is [gone] from the server: nothing is then promised.
 * The text of a book read by pages is named by its file's format, as everywhere on the page: "The CBZ is not
 * downloaded".
 */
@Composable
internal fun MissingFormats(book: BookDetail, kept: Set<DownloadFormat>, gone: Boolean) {
    book.offeredFormats().filterNot { it.format in kept }.forEach { missing ->
        val file = missing.textName.takeIf { missing.format == DownloadFormat.Text && it != TextName.Text }
        Text(
            text = if (file == null) {
                stringResource(if (gone) missing.format.goneRes() else missing.format.waitingRes())
            } else {
                stringResource(
                    if (gone) R.string.book_gone_missing_file else R.string.book_offline_missing_file,
                    stringResource(nameRes(DownloadFormat.Text, file)),
                )
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private fun DownloadFormat.waitingRes(): Int = when (this) {
    DownloadFormat.Text -> R.string.book_offline_missing_text
    DownloadFormat.Audiobook -> R.string.book_offline_missing_audiobook
    DownloadFormat.Narration -> R.string.book_offline_missing_narration
}

private fun DownloadFormat.goneRes(): Int = when (this) {
    DownloadFormat.Text -> R.string.book_gone_missing_text
    DownloadFormat.Audiobook -> R.string.book_gone_missing_audiobook
    DownloadFormat.Narration -> R.string.book_gone_missing_narration
}
