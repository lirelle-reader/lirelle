package app.tfdn.lirelle.feature.comics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.designsystem.SingleLineLabel

const val AlbumBannerTag = "comic-album-banner"

/**
 * What reading panel by panel is, and where it falls short: told once on the device, the first time the mode is
 * turned on. It has one button; a tap outside it and back close it as the button does.
 *
 * @param onClose it was closed, whichever way.
 */
@Composable
internal fun PanelNoticeDialog(onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.comics_panels_notice_title)) },
        text = {
            // In large text the sentences are taller than a small screen leaves the dialog.
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.comics_panels_notice_found))
                Text(stringResource(R.string.comics_panels_notice_limits))
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) { SingleLineLabel(stringResource(R.string.comics_panels_notice_ok)) }
        },
    )
}

/**
 * Few pages of the album can be cut into panels: it says so where a newer page is offered, with "Turn panel by
 * panel off" and "OK". Both close it; the first turns the mode off for the series before. The buttons go under
 * the sentence when they do not fit beside it, and the long one takes two lines when one is too short for it.
 *
 * @param insets what of the window's edges the banner keeps clear of: its bottom too when nothing is under it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AlbumBanner(actions: PanelModeActions, insets: WindowInsets, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth().testTag(AlbumBannerTag),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        FlowRow(
            modifier = Modifier.windowInsetsPadding(insets).padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.comics_panels_album),
                modifier = Modifier.padding(vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(
                onClick = {
                    actions.setPanelMode(false)
                    actions.closeAlbumBanner()
                },
                modifier = Modifier.heightIn(min = MinTarget),
            ) {
                Text(stringResource(R.string.comics_panels_album_off), textAlign = TextAlign.Center)
            }
            Button(onClick = actions::closeAlbumBanner, modifier = Modifier.heightIn(min = MinTarget)) {
                SingleLineLabel(stringResource(R.string.comics_panels_album_ok))
            }
        }
    }
}
