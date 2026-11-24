package app.tfdn.lirelle.feature.book.download

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.tfdn.lirelle.core.ui.fileSize
import app.tfdn.lirelle.feature.book.R

/**
 * What the line under the tiles says of the update of a downloaded book, and what can be done about it:
 * update, cancel, download anyway, retry; nothing for one that only waits for the book to close. An update
 * made of pages says its pages where another says its bytes.
 */
@Composable
internal fun UpdateState.line(): Line = when (this) {
    is UpdateState.Available -> Line(
        text = stringResource(R.string.download_update_available, fileSize(bytes)),
        action = stringResource(R.string.download_action_update),
        accent = true,
    )
    is UpdateState.Updating -> Line(
        text = pages?.let { stringResource(R.string.download_update_pages, it.said()) }
            ?: stringResource(R.string.download_update_progress, fileSize(receivedBytes), fileSize(totalBytes)),
        action = stringResource(R.string.download_action_cancel),
    )
    is UpdateState.WaitingForWifi -> Line(
        text = pages?.let { stringResource(R.string.download_update_wifi_pages, it.said()) }
            ?: stringResource(R.string.download_update_wifi_left, fileSize(leftBytes)),
        action = stringResource(R.string.download_action_anyway),
    )
    is UpdateState.Queued -> Line(
        text = when {
            !noNetwork -> stringResource(R.string.download_update_queued)
            pages != null -> stringResource(R.string.download_update_network_pages, pages.said())
            else -> stringResource(R.string.download_update_network_left, fileSize(leftBytes))
        },
        action = stringResource(R.string.download_action_cancel),
    )
    is UpdateState.Failed -> Line(
        // The reason is a sentence of its own elsewhere: here it follows a colon.
        text = stringResource(
            R.string.download_update_failed,
            reason.sentence().replaceFirstChar { it.lowercase() },
        ),
        action = stringResource(R.string.download_action_retry),
        failed = true,
    )
    UpdateState.Ready -> Line(stringResource(R.string.download_update_ready))
}
