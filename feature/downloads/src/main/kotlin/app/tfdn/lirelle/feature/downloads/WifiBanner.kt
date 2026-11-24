package app.tfdn.lirelle.feature.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.designsystem.WrappingLabel
import app.tfdn.lirelle.core.ui.fileSize

const val WifiBannerTag = "downloads-wifi"
const val WifiNowTag = "downloads-wifi-now"
const val WifiAlwaysTag = "downloads-wifi-always"
const val WaitingForNetworkTag = "downloads-waiting-network"

private val BannerShape = RoundedCornerShape(12.dp)

/** Narrow, so that the longer label has the width of the banner on a narrow phone. */
private val ButtonPadding = PaddingValues(horizontal = 8.dp)

/**
 * The queue waits for Wi-Fi: how many books and how much are left, and the two ways on: these downloads now,
 * or the mobile network for good.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WifiBanner(
    banner: WifiBannerUi,
    onDownloadNow: () -> Unit,
    onAlwaysAllowMobile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val message = banner.leftBytes?.let {
        pluralStringResource(R.plurals.downloads_wifi_left, banner.bookCount, banner.bookCount, fileSize(it))
    } ?: pluralStringResource(R.plurals.downloads_wifi_waiting, banner.bookCount, banner.bookCount)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag(WifiBannerTag),
        shape = BannerShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Column(modifier = Modifier.padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Wifi, contentDescription = null, modifier = Modifier.size(20.dp))
                Text(
                    text = stringResource(R.string.downloads_wifi_title),
                    // Announced when it appears: the downloads stopped on their own.
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            Text(
                text = message,
                modifier = Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onDownloadNow, Modifier.testTag(WifiNowTag), contentPadding = ButtonPadding) {
                    WrappingLabel(stringResource(R.string.downloads_wifi_now))
                }
                TextButton(onAlwaysAllowMobile, Modifier.testTag(WifiAlwaysTag), contentPadding = ButtonPadding) {
                    WrappingLabel(stringResource(R.string.downloads_wifi_always))
                }
            }
        }
    }
}

/** The queue waits for any network: it resumes alone, there is nothing to choose. */
@Composable
internal fun WaitingForNetwork(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag(WaitingForNetworkTag),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.WifiOff,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.downloads_waiting_network),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
