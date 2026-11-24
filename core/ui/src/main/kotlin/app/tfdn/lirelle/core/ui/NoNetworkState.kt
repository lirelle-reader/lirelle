package app.tfdn.lirelle.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.designsystem.SingleLineLabel
import app.tfdn.lirelle.core.designsystem.WrappingLabel
import kotlinx.coroutines.flow.StateFlow

const val NoNetworkStateTag = "no-network"
const val NoNetworkDownloadsTag = "no-network-downloads"
const val NoNetworkRetryTag = "no-network-retry"

/**
 * Takes the place of a whole screen that could not be loaded for lack of network: the downloaded books are
 * one tap away. It tries again by itself when the network is back.
 *
 * While the current profile waits for a sign-in ([LocalSignInNeeded]), its server is out of reach for that
 * and not for a lack of network: the screen then says so and offers to sign in again
 * ([SignInNeededContent]). It still tries again by itself when the profile's network comes back — which is
 * when its account has signed in again —, from the same place whichever of the two it shows: the mark that
 * falls and the network that comes back, seen in one go, are not missed.
 *
 * @param onOpenDownloads leads to the downloaded books; null where they cannot be reached from here, which only
 *   a profile that waits for a sign-in is shown without.
 * @param scrolls it scrolls, for the screens too short for it at a large text size. False where what holds it
 *   scrolls already.
 * @param online whether the device has a network: each time it comes back while this is shown, [onRetry] is
 *   called, once. Null where that is not known: only a tap tries again.
 */
@Composable
fun NoNetworkState(
    onRetry: () -> Unit,
    onOpenDownloads: (() -> Unit)?,
    modifier: Modifier = Modifier,
    scrolls: Boolean = true,
    online: StateFlow<Boolean>? = LocalDeviceOnline.current,
) {
    if (online != null) RetryWhenBackOnline(online, onRetry)
    val waiting = signInWait()
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag(if (waiting != null) SignInNeededStateTag else NoNetworkStateTag)
            .then(if (scrolls) Modifier.verticalScroll(rememberScrollState()) else Modifier)
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (waiting != null) {
            SignInNeededContent(waiting, onOpenDownloads)
        } else {
            NoNetworkContent(onRetry, onOpenDownloads)
        }
    }
}

/** What is said for lack of network: the downloaded books first, "Try again" after. */
@Composable
private fun ColumnScope.NoNetworkContent(onRetry: () -> Unit, onOpenDownloads: (() -> Unit)?) {
    Icon(
        imageVector = Icons.Outlined.WifiOff,
        contentDescription = null,
        modifier = Modifier.size(56.dp),
        tint = MaterialTheme.colorScheme.outline,
    )
    Text(
        text = stringResource(R.string.no_network_title),
        modifier = Modifier.semantics { heading() },
        style = MaterialTheme.typography.titleLarge,
        textAlign = TextAlign.Center,
    )
    Text(
        text = stringResource(R.string.no_network_message),
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    if (onOpenDownloads != null) {
        Button(onClick = onOpenDownloads, modifier = Modifier.testTag(NoNetworkDownloadsTag)) {
            WrappingLabel(stringResource(R.string.no_network_open_downloads))
        }
    }
    TextButton(onClick = onRetry, modifier = Modifier.testTag(NoNetworkRetryTag)) {
        SingleLineLabel(stringResource(R.string.state_retry))
    }
}

/** Calls [onRetry] each time [online] goes from false to true; what it was when this appeared is not a return. */
@Composable
private fun RetryWhenBackOnline(online: StateFlow<Boolean>, onRetry: () -> Unit) {
    val retry by rememberUpdatedState(onRetry)
    // Saved with the screen: one left offline (under the sign-in screens, say) and come back to once the
    // network is back finds that it was offline, and tries again.
    var was by rememberSaveable { mutableStateOf(online.value) }
    LaunchedEffect(online) {
        online.collect { now ->
            if (now && !was) retry()
            was = now
        }
    }
}
