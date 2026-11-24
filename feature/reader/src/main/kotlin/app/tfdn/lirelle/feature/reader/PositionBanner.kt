package app.tfdn.lirelle.feature.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.designsystem.SingleLineLabel
import app.tfdn.lirelle.core.ui.relativeTimeInSentence
import java.time.Instant

internal const val PositionBannerTag = "reader-position-banner"
internal const val GoBackTag = "reader-go-back"

/**
 * Another device read further: where and how long ago, "Ignore" and "Go there". The buttons go
 * under the sentence when they do not fit beside it.
 *
 * @param now the moment it is; null, or a position that does not say when it was written, and the
 *   sentence gives the place alone.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PositionBanner(
    offered: OfferedPosition,
    now: Instant?,
    onAccept: () -> Unit,
    onIgnore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag(PositionBannerTag),
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
            TextButton(onClick = onIgnore) { SingleLineLabel(stringResource(R.string.reader_newer_position_ignore)) }
            Button(onClick = onAccept) { SingleLineLabel(stringResource(R.string.reader_newer_position_go)) }
        }
    }
}

@Composable
private fun newerPositionText(offered: OfferedPosition, now: Instant?): String {
    val percent = offered.percent.toInt()
    val written = offered.updatedAt
    if (written == null || now == null) return stringResource(R.string.reader_newer_position, percent)
    // The server's clock may run ahead of the device's: a position is never one of the future.
    return stringResource(R.string.reader_newer_position_at, percent, relativeTimeInSentence(written, now))
}

/** Around "Go back"'s label: a small pill, lower than its touch target. */
private val GoBackSidePadding = 12.dp
private val GoBackUprightPadding = 6.dp

/**
 * After a jump: returns to the page that was left. A small pill, [goBackHeight] high, in a touch
 * target at least [goBackRoom] high and as wide, which reaches past the pill. Its label is never cut.
 *
 * @param pillAlignment where the pill sits in its touch target.
 */
@Composable
internal fun GoBackButton(
    onGoBack: () -> Unit,
    modifier: Modifier = Modifier,
    pillAlignment: Alignment = Alignment.Center,
) {
    val interactions = remember { MutableInteractionSource() }
    val target = LocalMinimumInteractiveComponentSize.current
    Box(
        modifier = modifier
            .testTag(GoBackTag)
            // Outside the least size, so that the whole target answers, not the pill alone.
            .clickable(interactions, indication = null, role = Role.Button, onClick = onGoBack)
            .sizeIn(minWidth = target, minHeight = target),
        contentAlignment = pillAlignment,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shadowElevation = 1.dp,
        ) {
            Box(
                modifier = Modifier
                    .indication(interactions, ripple())
                    .height(goBackHeight())
                    .padding(horizontal = GoBackSidePadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.reader_go_back),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

/** How high "Go back"'s pill is drawn: one line of its label and a little room. */
@Composable
private fun goBackHeight(): Dp =
    with(LocalDensity.current) { MaterialTheme.typography.labelLarge.lineHeight.toDp() } + GoBackUprightPadding * 2

/** How high "Go back" takes room: its pill, or the touch target around it when that is higher. */
@Composable
internal fun goBackRoom(): Dp = maxOf(goBackHeight(), LocalMinimumInteractiveComponentSize.current)

/** A link of the book leads to the web: the address is shown whole, and nothing opens without a yes. */
@Composable
internal fun ExternalLinkDialog(url: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.reader_link_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.reader_link_text))
                Text(url, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { SingleLineLabel(stringResource(R.string.reader_link_open)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { SingleLineLabel(stringResource(R.string.reader_cancel)) }
        },
    )
}
