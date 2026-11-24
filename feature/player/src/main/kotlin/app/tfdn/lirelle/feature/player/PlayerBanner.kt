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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.designsystem.SingleLineLabel
import app.tfdn.lirelle.core.ui.relativeTimeInSentence
import app.tfdn.lirelle.feature.player.controller.OfferedListening
import java.time.Instant

internal const val NewerPositionTag = "player-newer-position"
internal const val StatusTag = "player-status"
internal const val GoBackTag = "player-go-back"

private val IconSize = 20.dp

/**
 * A newer position from elsewhere (spec 8.3): "Go to 1:23:04 (2 hours ago)", "Ignore" and "Go there".
 * The buttons go under the sentence when they do not fit beside it.
 *
 * @param now the moment it is; null, or a position that does not say when it was written, and the
 *   sentence gives the place alone.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NewerPositionBanner(
    offered: OfferedListening,
    now: Instant?,
    onAccept: () -> Unit,
    onIgnore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag(NewerPositionTag),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        FlowRow(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = newerPositionText(offered, now),
                modifier = Modifier
                    .padding(vertical = 8.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onIgnore) { SingleLineLabel(stringResource(R.string.player_newer_position_ignore)) }
            Button(onClick = onAccept) { SingleLineLabel(stringResource(R.string.player_newer_position_go)) }
        }
    }
}

/**
 * The place, and how long ago it was written. In a book whose chapters are its files named alike
 * ("Part 100"), the time in the whole book means little: the part and the time in it are given instead.
 */
@Composable
private fun newerPositionText(offered: OfferedListening, now: Instant?): String {
    val part = offered.chapter?.takeIf { it.title == stringResource(R.string.player_part, it.number) }
    val place = part
        ?.let { stringResource(R.string.player_newer_position_in_chapter, it.title, clockTime(it.elapsedMs)) }
        ?: clockTime(offered.bookTimeMs)
    val written = offered.updatedAt
    if (written == null || now == null) return stringResource(R.string.player_newer_position, place)
    // The server's clock may run ahead of the device's: a position is never one of the future.
    return stringResource(R.string.player_newer_position_at, place, relativeTimeInSentence(written, now))
}

/**
 * Why playback waits: no network, a server out of reach, a book that could not be loaded. The text
 * takes the lines it needs; [onRetry] adds "Try again" under it, [onDismiss] a button that hides it.
 */
@Composable
internal fun StatusMessage(
    text: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag(StatusTag),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 4.dp)) {
            Row(
                modifier = Modifier.heightIn(min = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.CloudOff, contentDescription = null, modifier = Modifier.size(IconSize))
                Text(
                    text = text,
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 8.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodyMedium,
                )
                onDismiss?.let {
                    IconButton(onClick = it) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.player_dismiss))
                    }
                }
            }
            onRetry?.let {
                TextButton(onClick = it, modifier = Modifier.align(Alignment.End)) {
                    SingleLineLabel(stringResource(R.string.player_load_retry))
                }
            }
        }
    }
}

/** After a chapter chosen or a newer position followed: back to where playback was. */
@Composable
internal fun GoBackButton(onGoBack: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    FilledTonalButton(
        onClick = onGoBack,
        enabled = enabled,
        modifier = modifier
            .testTag(GoBackTag)
            .heightIn(min = 40.dp),
        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
    ) {
        Icon(Icons.AutoMirrored.Rounded.Undo, null, Modifier.size(ButtonDefaults.IconSize))
        SingleLineLabel(stringResource(R.string.player_go_back), Modifier.padding(start = ButtonDefaults.IconSpacing))
    }
}

/** "Go back" as an icon, where its label would not fit; a screen reader hears the label. */
@Composable
internal fun GoBackIconButton(onGoBack: () -> Unit, modifier: Modifier = Modifier) {
    FilledTonalIconButton(onClick = onGoBack, modifier = modifier.testTag(GoBackTag)) {
        Icon(Icons.AutoMirrored.Rounded.Undo, contentDescription = stringResource(R.string.player_go_back))
    }
}
