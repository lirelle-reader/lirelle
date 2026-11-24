package app.tfdn.lirelle.feature.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.downloads.model.DownloadFailure
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ui.fileSize
import app.tfdn.lirelle.core.ui.libraryMessage

const val InProgressHeadingTag = "downloads-in-progress"

fun progressRowTag(bookId: BookId): String = "downloads-progress-$bookId"

fun cancelTag(bookId: BookId): String = "downloads-cancel-$bookId"

fun retryTag(bookId: BookId): String = "downloads-retry-$bookId"

private val ProgressCoverWidth = 48.dp
internal val RowShape = RoundedCornerShape(12.dp)

/**
 * A download that is not complete: its formats and how far it is, with a bar, for the one being fetched; the
 * same and what it waits for, for the one a missing network stopped; "queued" for one waiting its turn; why, and
 * "Retry", for one that failed. Each can be cancelled. The update of a downloaded book is said as an update.
 *
 * @param card on a wide window: a card of the grid.
 */
@Composable
internal fun InProgressRow(
    row: ProgressRow,
    card: Boolean,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.testTag(progressRowTag(row.bookId)),
        shape = RowShape,
        color = if (card) MaterialTheme.colorScheme.surfaceContainer else Color.Transparent,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = if (card) 10.dp else 0.dp, top = 6.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val title = rowTitle(row.title)
            KeptCover(row.cover, row.coverShape, Modifier.width(ProgressCoverWidth))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                ProgressStatusLines(row, onRetry)
            }
            IconButton(onClick = onCancel, modifier = Modifier.testTag(cancelTag(row.bookId))) {
                val cancel = if (row.update) R.string.downloads_cancel_update else R.string.downloads_cancel
                Icon(Icons.Outlined.Close, contentDescription = stringResource(cancel, title))
            }
        }
    }
}

/** The title of a row: "Book" for a book whose page the device does not have, never nothing. */
@Composable
internal fun rowTitle(title: String?): String = title ?: stringResource(R.string.downloads_untitled)

/** Why a download failed, in the words of the screen. */
@Composable
internal fun DownloadFailure.message(): String = when (this) {
    is DownloadFailure.NoSpace -> stringResource(R.string.downloads_failure_no_space, fileSize(missingBytes))
    is DownloadFailure.Server -> error.libraryMessage()
    DownloadFailure.NotAllowed -> stringResource(R.string.downloads_failure_not_allowed)
    DownloadFailure.NotAnEpub -> stringResource(R.string.downloads_failure_not_epub)
    DownloadFailure.Damaged -> stringResource(R.string.downloads_failure_damaged)
}
