package app.tfdn.lirelle.feature.downloads

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.designsystem.SingleLineLabel
import app.tfdn.lirelle.core.downloads.formatsName
import app.tfdn.lirelle.core.downloads.model.PageCount
import app.tfdn.lirelle.core.downloads.model.WaitReason
import app.tfdn.lirelle.core.downloads.pagesOf
import app.tfdn.lirelle.core.ui.fileSize

/** The bar of a download stopped by a wait: what it received, not in the colour of what runs. */
const val WaitingProgressTag = "downloads-row-progress-waiting"

/** Under the title of a download in progress: where it is, in words, and its bar or its "Retry". */
@Composable
internal fun ProgressStatusLines(row: ProgressRow, onRetry: () -> Unit) {
    // An update is said as one, whatever the formats it is of.
    val formats = if (row.update) {
        stringResource(R.string.downloads_progress_update)
    } else {
        LocalResources.current.formatsName(row.formats, row.textName)
    }
    when (val status = row.status) {
        is ProgressStatus.Active -> {
            StatusText(progressText(formats, status.receivedBytes, status.totalBytes, row.pages))
            ActiveBar(fraction(row.pages, status.receivedBytes, status.totalBytes))
        }
        is ProgressStatus.Waiting -> WaitingLines(formats, status, row.pages)
        is ProgressStatus.Queued -> StatusText(
            status.totalBytes
                ?.let { stringResource(R.string.downloads_progress_queued_size, formats, fileSize(it)) }
                ?: stringResource(R.string.downloads_progress_queued, formats),
        )
        is ProgressStatus.Failed -> {
            val reason = status.reason.message()
            StatusText(
                text = if (row.update) stringResource(R.string.downloads_update_failed, reason) else reason,
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(
                onClick = onRetry,
                modifier = Modifier.testTag(retryTag(row.bookId)),
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) { SingleLineLabel(stringResource(R.string.downloads_retry)) }
        }
    }
}

/**
 * "Narration · 11 MB of 98 MB", or what is known of it. A download made of pages says its [pages], whose bytes
 * are counted against an estimate: "CBZ · 12 pages of 48".
 */
@Composable
private fun progressText(formats: String, received: Long, total: Long?, pages: PageCount?): String = when {
    pages != null ->
        stringResource(R.string.downloads_progress_received, formats, LocalResources.current.pagesOf(pages))
    total != null && received > 0 ->
        stringResource(R.string.downloads_progress_of, formats, fileSize(received), fileSize(total))
    total != null -> stringResource(R.string.downloads_progress_received, formats, fileSize(total))
    received > 0 -> stringResource(R.string.downloads_progress_received, formats, fileSize(received))
    else -> formats
}

/**
 * How far a download is, from 0 to 1: by its [pages] when it is made of pages, else by its bytes. Null while
 * neither says.
 */
private fun fraction(pages: PageCount?, received: Long, total: Long?): Float? = when {
    pages != null -> pages.kept.toFloat() / pages.total
    total != null && total > 0 -> (received.toFloat() / total).coerceIn(0f, 1f)
    else -> null
}

/** The bar of what is being fetched: at [fraction], or going round while it is not known. */
@Composable
private fun ActiveBar(fraction: Float?) {
    val bar = Modifier
        .fillMaxWidth()
        .testTag(RowProgressTag)
    if (fraction != null) {
        LinearProgressIndicator(progress = { fraction }, modifier = bar, drawStopIndicator = {})
    } else {
        LinearProgressIndicator(modifier = bar)
    }
}

/**
 * What it received and what it waits for; its bar, greyed, once it received something of a known size, or
 * a page when it is made of [pages].
 */
@Composable
private fun WaitingLines(formats: String, status: ProgressStatus.Waiting, pages: PageCount?) {
    val wait = when (status.reason) {
        WaitReason.UnmeteredOnly -> R.string.downloads_progress_wait_wifi
        WaitReason.NoNetwork -> R.string.downloads_progress_wait_network
    }
    val detail = progressText(formats, status.receivedBytes, status.totalBytes, pages)
    StatusText(stringResource(R.string.downloads_progress_status, detail, stringResource(wait)))
    val fraction = fraction(pages, status.receivedBytes, status.totalBytes)
    if (fraction != null && fraction > 0f) {
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier
                .fillMaxWidth()
                .testTag(WaitingProgressTag),
            color = MaterialTheme.colorScheme.outline,
            drawStopIndicator = {},
        )
    }
}

@Composable
private fun StatusText(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = color)
}
