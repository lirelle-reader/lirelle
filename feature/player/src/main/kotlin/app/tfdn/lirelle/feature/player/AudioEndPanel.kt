package app.tfdn.lirelle.feature.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.designsystem.SingleLineLabel
import app.tfdn.lirelle.core.downloads.DeviceCopyActions
import app.tfdn.lirelle.core.downloads.formatsName
import app.tfdn.lirelle.core.downloads.model.DeviceCopy
import app.tfdn.lirelle.core.library.model.NextVolume
import app.tfdn.lirelle.core.ui.fileSize
import app.tfdn.lirelle.feature.player.controller.FinishedState
import app.tfdn.lirelle.core.downloads.R as DownloadsR

private const val StarCount = 5
private const val MillisPerSecond = 1_000L

/** The smallest side of anything that takes a tap. */
private val TouchTarget = 48.dp
private val StarSize = 30.dp
private val ProgressSize = 24.dp
private val ProgressStroke = 3.dp

/**
 * The panel at the end of an audiobook (spec 10): the book's title, the time listened to it, an
 * optional rating, the series' next volume with "Listen to the next one", and "Close". It closes like
 * the reader's panels: the grip, a swipe down, a tap above and back dismiss it, the book staying
 * finished on the player. A rating that could not be sent says so and offers to try again what was
 * asked. A book the device keeps has a row to delete it from there, under the next volume.
 *
 * @param listenedSeconds the time listened to the book; null leaves the line out.
 * @param ratings false for a source without ratings: no stars (spec 2c).
 */
@Composable
internal fun AudioEndPanel(
    finished: FinishedState,
    title: String,
    listenedSeconds: Long?,
    actions: EndPanelActions,
    modifier: Modifier = Modifier,
    ratings: Boolean = true,
) {
    // What a failed rating was sent for: trying again asks it again.
    var lastAsked by remember { mutableStateOf<() -> Unit>(actions::dismissEndPanel) }
    val ask = { action: () -> Unit ->
        lastAsked = action
        action()
    }
    PanelSheet(onDismiss = { ask(actions::dismissEndPanel) }, modifier = modifier) {
        EndPanelContent(
            finished = finished,
            title = title,
            listenedSeconds = listenedSeconds,
            onRate = actions::setRating.takeIf { ratings },
            onNext = { ask(actions::listenToNext) },
            onClose = { ask(actions::closeEndPanel) },
            onRetry = { lastAsked() },
            device = actions,
        )
    }
}

/** What the panel holds. It scrolls when a large text size makes it taller than the screen. */
@Composable
private fun EndPanelContent(
    finished: FinishedState,
    title: String,
    listenedSeconds: Long?,
    onRate: ((Int?) -> Unit)?,
    onNext: () -> Unit,
    onClose: () -> Unit,
    onRetry: () -> Unit,
    device: DeviceCopyActions,
) {
    val enabled = !finished.sending
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.player_end_finished),
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleLarge,
        )
        Text(text = title, style = MaterialTheme.typography.bodyLarge)
        listenedSeconds?.let {
            Text(
                text = stringResource(R.string.player_end_listened, durationText(it * MillisPerSecond)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (onRate != null) {
            Text(text = stringResource(R.string.player_end_rating), style = MaterialTheme.typography.bodyMedium)
            Stars(rating = finished.rating, enabled = enabled, onRate = onRate)
        }
        if (finished.failed) RatingFailed(enabled, onRetry)
        finished.nextVolume?.let { NextVolumeCard(it, enabled, onNext) }
        finished.device?.let { DeviceCard(it, enabled, device) }
        ClosingRow(sending = finished.sending, onClose = onClose)
    }
}

/** Five stars; the one chosen is the last one filled. A tap on it takes the rating away. */
@Composable
private fun Stars(rating: Int?, enabled: Boolean, onRate: (Int?) -> Unit) {
    Row {
        for (stars in 1..StarCount) {
            val chosen = rating == stars
            val filled = rating != null && stars <= rating
            IconButton(
                onClick = { onRate(if (chosen) null else stars) },
                modifier = Modifier
                    .size(TouchTarget)
                    .semantics { selected = chosen },
                enabled = enabled,
            ) {
                Icon(
                    imageVector = if (filled) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                    contentDescription = pluralStringResource(R.plurals.player_end_stars, stars, stars),
                    modifier = Modifier.size(StarSize),
                    tint = if (enabled) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                )
            }
        }
    }
}

/** The rating could not be sent: the message, and what was asked asked again. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RatingFailed(enabled: Boolean, onRetry: () -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.player_end_rating_failed),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
        TextButton(onClick = onRetry, enabled = enabled) { SingleLineLabel(stringResource(R.string.player_end_retry)) }
    }
}

/** The series' next volume: its title on one line, the button under it, whatever their lengths. */
@Composable
private fun NextVolumeCard(next: NextVolume, enabled: Boolean, onListen: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.player_end_next_volume),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            next.title?.let {
                Text(
                    text = it,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            Button(
                onClick = onListen,
                modifier = Modifier
                    .align(Alignment.End)
                    .heightIn(min = TouchTarget),
                enabled = enabled,
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
            ) {
                Icon(Icons.Rounded.PlayArrow, null, Modifier.size(ButtonDefaults.IconSize))
                SingleLineLabel(
                    stringResource(R.string.player_button_listen_next),
                    Modifier.padding(start = ButtonDefaults.IconSpacing),
                )
            }
        }
    }
}

/**
 * What the device keeps of the book, built like the next volume's card: its formats and their size, with
 * "Delete"; once asked, that the book goes from the device, with "Undo". Its texts take the lines they need.
 */
@Composable
private fun DeviceCard(copy: DeviceCopy, enabled: Boolean, actions: DeviceCopyActions) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        // Deleting is said in the colour of what is not done lightly; undoing it is not.
        val ink = if (copy.deleting) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        // The button is beside the texts, and under them only when a large font leaves it no room there.
        FlowRow(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (copy.deleting) {
                    Text(
                        text = stringResource(R.string.player_end_device_deleted),
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.player_end_device),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(
                            DownloadsR.string.downloads_formats_size,
                            LocalResources.current.formatsName(copy.formats, copy.textName),
                            fileSize(copy.bytes),
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
            OutlinedButton(
                onClick = if (copy.deleting) actions::keepOnDevice else actions::deleteFromDevice,
                modifier = Modifier.heightIn(min = TouchTarget),
                enabled = enabled,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = ink),
            ) {
                val label = if (copy.deleting) R.string.player_end_device_undo else R.string.player_end_device_delete
                SingleLineLabel(stringResource(label))
            }
        }
    }
}

/** "Close", which also sends the rating, and what shows that it is on its way. */
@Composable
private fun ClosingRow(sending: Boolean, onClose: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (sending) {
            val saving = stringResource(R.string.player_end_sending)
            CircularProgressIndicator(
                modifier = Modifier
                    .size(ProgressSize)
                    .semantics { contentDescription = saving },
                strokeWidth = ProgressStroke,
            )
        }
        OutlinedButton(
            onClick = onClose,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = TouchTarget),
            enabled = !sending,
        ) {
            SingleLineLabel(stringResource(R.string.player_end_close))
        }
    }
}
