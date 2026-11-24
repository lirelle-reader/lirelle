package app.tfdn.lirelle.feature.book.download

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.designsystem.SingleLineLabel
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.TextName
import app.tfdn.lirelle.core.downloads.nameRes
import app.tfdn.lirelle.feature.book.R
import app.tfdn.lirelle.feature.book.SecondaryActionPadding

const val DownloadTileTag = "download-tile"
const val UpdateBarTag = "download-tile-update-bar"

/** The thin bar of a download asked and not whole. */
const val DownloadBarTag = "download-tile-bar"

private const val FullPercent = 100f

/** What the page does with the downloads of its book. */
class DownloadActions(
    val onTile: () -> Unit = {},
    val onLineAction: () -> Unit = {},
    val onCloseSheet: () -> Unit = {},
    val onApply: (SheetPlan) -> Unit = {},
    val onInUseShown: () -> Unit = {},
)

/**
 * The name of a format on the book page. The text of a book read by pages is named by its file's format,
 * which [text] says: "CBZ", "PDF".
 */
@Composable
internal fun DownloadFormat.label(text: TextName = TextName.Text): String = stringResource(
    when (this) {
        DownloadFormat.Text -> if (text == TextName.Text) R.string.download_format_text else nameRes(this, text)
        DownloadFormat.Audiobook -> R.string.download_format_audiobook
        DownloadFormat.Narration -> R.string.download_format_narration
    },
)

/**
 * The download tile of the book page: as wide as the label that offers to download, in every state but
 * downloaded, where it is as wide as its own; a label is one line. While a download is asked, a thin bar under
 * the label, within that width, says how far it is, greyed while it waits; once downloaded, the tile is
 * outlined in the accent colour, with the same thin bar under its label while an update of the book is asked.
 * The detail and its action are on the [DownloadLine], the tile being narrow.
 *
 * @param enabled whether a tap does something; an account that may not download has it disabled anyway.
 * @param update where the update of a downloaded book is.
 */
@Composable
fun DownloadTile(
    state: TileState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    update: UpdateState? = null,
) {
    val active = enabled && state != TileState.NotAllowed
    val done = state == TileState.Downloaded
    val percent = (state as? TileState.Downloading)?.percent
    val description = percent?.let { stringResource(R.string.download_tile_downloading_description, it) }
    OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .testTag(DownloadTileTag)
            .then(if (description == null) Modifier else Modifier.semantics { contentDescription = description }),
        enabled = active,
        border = if (done && active) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
        } else {
            ButtonDefaults.outlinedButtonBorder(active)
        },
        contentPadding = SecondaryActionPadding,
    ) {
        if (done) Done(state.label(), update) else TileSlot { Progress(state) }
    }
}

/**
 * The label of a downloaded book and, while an [update] is asked, the thin bar under it, as wide as the label
 * with its mark: the tile keeps its width. The bar is greyed while the update waits.
 */
@Composable
private fun Done(label: String, update: UpdateState?) {
    val percent = update?.barPercent
    Layout(
        content = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                SingleLineLabel(label)
            }
            if (percent != null) {
                ThinBar(percent, moving = update is UpdateState.Updating, Modifier.testTag(UpdateBarTag))
            }
        },
    ) { measurables, constraints ->
        val shown = measurables.first().measure(constraints.copy(minWidth = 0, minHeight = 0))
        val bar = measurables.getOrNull(1)
            ?.measure(constraints.copy(minWidth = shown.width, maxWidth = shown.width, minHeight = 0))
        layout(shown.width, shown.height + (bar?.height ?: 0)) {
            shown.place(0, 0)
            bar?.place(0, shown.height)
        }
    }
}

/**
 * The room of the label of a tile that offers to download, whatever [content] says in it: the tile keeps its
 * width, and so its place beside the others, while the download goes on.
 */
@Composable
private fun TileSlot(content: @Composable () -> Unit) {
    Layout(
        content = {
            // Measured, never placed: it only gives its width.
            SingleLineLabel(stringResource(R.string.action_download), Modifier.clearAndSetSemantics {})
            Column(horizontalAlignment = Alignment.CenterHorizontally) { content() }
        },
    ) { measurables, constraints ->
        val room = measurables.first().measure(constraints)
        val shown = measurables.last().measure(constraints.copy(minWidth = room.width, maxWidth = room.width))
        val height = maxOf(room.height, shown.height)
        layout(room.width, height) { shown.place(0, (height - shown.height) / 2) }
    }
}

/** The label of the state and, while a download is asked, the thin bar under it, as wide as the slot. */
@Composable
private fun Progress(state: TileState) {
    SingleLineLabel(state.label())
    val progress = state.progress() ?: return
    ThinBar(progress, moving = state is TileState.Downloading, Modifier.testTag(DownloadBarTag))
}

/** How far a download or an update is, out of 100, across the width it is given; greyed unless [moving]. */
@Composable
private fun ThinBar(percent: Int, moving: Boolean, modifier: Modifier = Modifier) {
    LinearProgressIndicator(
        progress = { percent / FullPercent },
        modifier = modifier
            .fillMaxWidth()
            .height(3.dp),
        color = MaterialTheme.colorScheme.run { if (moving) primary else outline },
        gapSize = 0.dp,
        drawStopIndicator = {},
    )
}

/** How far the download is, out of 100, for the states that have a bar; nought while it is not known. */
private fun TileState.progress(): Int? = when (this) {
    is TileState.Downloading -> percent ?: 0
    is TileState.WaitingForWifi -> percent ?: 0
    is TileState.Queued -> percent ?: 0
    else -> null
}

@Composable
private fun TileState.label(): String = when (this) {
    TileState.NotDownloaded, TileState.NotAllowed -> stringResource(R.string.action_download)
    is TileState.Downloading ->
        percent?.let { stringResource(R.string.download_tile_percent, it) } ?: format.label(textName)
    is TileState.WaitingForWifi, is TileState.Queued -> stringResource(R.string.download_tile_waiting)
    is TileState.Failed -> stringResource(R.string.download_tile_failed)
    TileState.Downloaded -> stringResource(R.string.download_tile_downloaded)
}
