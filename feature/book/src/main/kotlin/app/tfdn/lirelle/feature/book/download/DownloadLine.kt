package app.tfdn.lirelle.feature.book.download

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.downloads.model.DownloadFailure
import app.tfdn.lirelle.core.downloads.model.PageCount
import app.tfdn.lirelle.core.downloads.pagesOf
import app.tfdn.lirelle.core.ui.fileSize
import app.tfdn.lirelle.core.ui.libraryMessage
import app.tfdn.lirelle.feature.book.R
import app.tfdn.lirelle.core.designsystem.WordsLabel

const val DownloadLineTag = "download-line"
const val DownloadLineActionTag = "download-line-action"

/** What the line says, and the action beside it; [accent] for what the user is offered. */
internal class Line(
    val text: String,
    val action: String? = null,
    val failed: Boolean = false,
    val accent: Boolean = false,
)

/**
 * The line under the tiles of the book page: what the download tile has no room for, and what can be done
 * about it (cancel, download anyway, retry). Its text wraps; its action goes under it when the two do not fit
 * on a line. Nothing is shown for a book that is not downloaded or is whole, unless it has an [update]: the
 * line is then about it.
 *
 * @param allowed false for an account that may not download: the line says so, whatever the device keeps.
 * @param update where the update of a downloaded book is; it is said when the tile itself has nothing to say.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DownloadLine(
    state: TileState,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    allowed: Boolean = true,
    update: UpdateState? = null,
) {
    val line = when {
        !allowed -> Line(stringResource(R.string.download_line_not_allowed))
        state == TileState.Downloaded -> update?.line()
        else -> state.line()
    }
    line ?: return
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = line.text,
            modifier = Modifier.testTag(DownloadLineTag),
            color = MaterialTheme.colorScheme.run {
                when {
                    line.failed -> error
                    line.accent -> primary
                    else -> onSurfaceVariant
                }
            },
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
        )
        line.action?.let { label ->
            TextButton(
                onClick = onAction,
                modifier = Modifier
                    .testTag(DownloadLineActionTag)
                    .semantics { contentDescription = label },
            ) { WordsLabel(label) }
        }
    }
}

@Composable
private fun TileState.line(): Line? = when (this) {
    is TileState.Downloading -> Line(text = detail(), action = stringResource(R.string.download_action_cancel))
    is TileState.WaitingForWifi -> Line(
        text = when {
            pages != null -> stringResource(R.string.download_line_wifi_pages, pages.said())
            leftBytes != null -> stringResource(R.string.download_line_wifi_left, fileSize(leftBytes))
            else -> stringResource(R.string.download_line_wifi)
        },
        action = stringResource(R.string.download_action_anyway),
    )
    is TileState.Queued -> Line(
        text = when {
            !noNetwork -> stringResource(R.string.download_line_queued)
            pages != null -> stringResource(R.string.download_line_network_pages, pages.said())
            leftBytes != null -> stringResource(R.string.download_line_network_left, fileSize(leftBytes))
            else -> stringResource(R.string.download_line_network)
        },
        action = stringResource(R.string.download_action_cancel),
    )
    is TileState.Failed -> Line(reason.sentence(), stringResource(R.string.download_action_retry), failed = true)
    else -> null
}

/**
 * The format being fetched and how far it is: in pages for one made of pages, "CBZ · 12 pages of 48", in bytes
 * for any other, "Audiobook · 124 MB of 412 MB"; its name alone while nothing says how far.
 */
@Composable
private fun TileState.Downloading.detail(): String {
    val name = format.label(textName)
    return when {
        pages != null -> stringResource(R.string.download_line_pages, name, pages.said())
        totalBytes != null ->
            stringResource(R.string.download_line_progress, name, fileSize(receivedBytes), fileSize(totalBytes))
        else -> name
    }
}

/** "12 pages of 48". */
@Composable
internal fun PageCount.said(): String = LocalResources.current.pagesOf(this)

@Composable
internal fun DownloadFailure.sentence(): String = when (this) {
    is DownloadFailure.NoSpace -> stringResource(R.string.download_failure_no_space, fileSize(missingBytes))
    is DownloadFailure.Server -> error.libraryMessage()
    DownloadFailure.NotAllowed -> stringResource(R.string.download_line_not_allowed)
    DownloadFailure.NotAnEpub -> stringResource(R.string.download_failure_not_epub)
    DownloadFailure.Damaged -> stringResource(R.string.download_failure_damaged)
}
