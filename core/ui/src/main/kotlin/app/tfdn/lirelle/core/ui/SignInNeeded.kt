package app.tfdn.lirelle.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.designsystem.WrappingLabel
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.session.ServerKind

const val SignInNeededStateTag = "sign-in-needed"
const val SignInNeededAgainTag = "sign-in-needed-again"
const val SignInNeededDownloadsTag = "sign-in-needed-downloads"
const val SignInNeededBarTag = "sign-in-needed-bar"

/**
 * The current profile while its session waits for a sign-in, as the screens without their server name it.
 *
 * @property profileId what "Sign in again" is asked for.
 * @property username its account's.
 * @property kind the kind of its server.
 */
@Immutable
data class SignInNeeded(val profileId: String, val username: String, val kind: ServerKind)

/**
 * The current profile when its session waits for a sign-in; null while it does not, and outside the signed-in
 * level. The signed-in level provides it: a screen that cannot reach its server then says why — the profile
 * asks its server nothing until its account signs in again — instead of a lack of network, with no parameter
 * of its own for it.
 */
val LocalSignInNeeded = compositionLocalOf<SignInNeeded?> { null }

/**
 * Opens the sign-in screens for the profile of that id. The signed-in level provides it; null outside it. Not
 * static: the level gives a new one each time it is drawn again, which only the screens that use it follow.
 */
val LocalSignInAgain = compositionLocalOf<((profileId: String) -> Unit)?> { null }

/**
 * The profile that waits for a sign-in, with the way to sign it in again.
 *
 * @property message what happened to its sign-in, naming the profile as everywhere ("`<username>` · `<kind>`"): as
 *   its kind says it.
 */
internal class SignInWait(val message: String, val signInAgain: () -> Unit)

/** What the screens say instead of a lack of network; null while the profile needs no sign-in. */
@Composable
internal fun signInWait(): SignInWait? {
    val needed = LocalSignInNeeded.current ?: return null
    val signInAgain = LocalSignInAgain.current ?: return null
    val resources = LocalResources.current
    val profile = profileLine(resources, needed.username, needed.kind)
    return SignInWait(resources.getString(needed.kind.signInNeededMessageRes, profile)) {
        signInAgain(needed.profileId)
    }
}

/**
 * The content of the screen without its server while the profile waits for a sign-in: what is needed, whose
 * session expired, "Sign in again" — the one thing that brings the page back —, then the way to the downloaded
 * books where there is one, less loud. No "Try again": the server is not asked until then. No promise that the
 * page reloads either, though it does once the account has signed in again: the sentence would be about a
 * network, which the device has.
 */
@Composable
internal fun ColumnScope.SignInNeededContent(waiting: SignInWait, onOpenDownloads: (() -> Unit)?) {
    Icon(
        imageVector = Icons.Outlined.Lock,
        contentDescription = null,
        modifier = Modifier.size(56.dp),
        tint = MaterialTheme.colorScheme.outline,
    )
    Text(
        text = stringResource(R.string.sign_in_needed),
        modifier = Modifier.semantics { heading() },
        style = MaterialTheme.typography.titleLarge,
        textAlign = TextAlign.Center,
    )
    Text(
        text = waiting.message,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Button(onClick = waiting.signInAgain, modifier = Modifier.testTag(SignInNeededAgainTag)) {
        WrappingLabel(stringResource(R.string.sign_in_again))
    }
    if (onOpenDownloads != null) {
        OutlinedButton(onClick = onOpenDownloads, modifier = Modifier.testTag(SignInNeededDownloadsTag)) {
            WrappingLabel(stringResource(R.string.no_network_open_downloads))
        }
    }
}

/**
 * A reload that failed while what was loaded before stays on screen, said under it with its retry. While the
 * profile waits for a sign-in, a server out of reach is that: the bar says so and signs in again instead.
 */
@Composable
fun ServerFailureBar(error: ApiError, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val waiting = signInWait()
    if (error.isNoNetwork && waiting != null) {
        SignInNeededBar(waiting, modifier)
    } else {
        PagingErrorBar(error.libraryMessage(), onRetry = onRetry, modifier = modifier, canRetry = error.canRetry)
    }
}

/**
 * The bar of a failed reload while the profile waits for a sign-in, drawn as the other: what is needed and
 * whose session expired, read as one announcement, and "Sign in again", which goes under them when both do not
 * fit on one line.
 */
@Composable
private fun SignInNeededBar(waiting: SignInWait, modifier: Modifier) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag(SignInNeededBarTag),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        FlowRow(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .padding(vertical = 8.dp)
                    .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            ) {
                Text(stringResource(R.string.sign_in_needed), style = MaterialTheme.typography.titleSmall)
                Text(text = waiting.message, style = MaterialTheme.typography.bodyMedium)
            }
            TextButton(onClick = waiting.signInAgain) { WrappingLabel(stringResource(R.string.sign_in_again)) }
        }
    }
}
