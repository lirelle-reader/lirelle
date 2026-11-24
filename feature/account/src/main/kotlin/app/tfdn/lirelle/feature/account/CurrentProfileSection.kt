package app.tfdn.lirelle.feature.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.designsystem.SingleLineLabel
import app.tfdn.lirelle.core.designsystem.WrappingLabel
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.ui.ProfileAvatar
import app.tfdn.lirelle.core.ui.ProfileAvatarSize
import app.tfdn.lirelle.core.ui.R as CoreR

const val CurrentProfileHeaderTag = "profiles-current-header"

/** "Sign in again", of the current profile while it waits for a sign-in. */
const val ProfilesPageSignInAgainTag = "profiles-page-sign-in-again"

/**
 * What the page shows of the current profile.
 *
 * @property details what its server says of it, or that it is being asked, or could not be.
 * @property several there are other profiles: the section then has its label.
 * @property serverFirst the server's rows come before the account's. So on the canvas where the profiles
 *   are several in one column: the server is what tells them apart, and the other profiles follow below. In
 *   the column of its own that an expanded width gives it, and when it is the only profile, the account's
 *   rows come first, as on the Account page.
 * @property ownColumn the section has a column of its own beside the other profiles': "Sign in again" is then
 *   at the start, as wide as its label, where a button across half a tablet would be out of scale; in a single
 *   column it takes the width, as the page's other buttons.
 */
internal class CurrentShown(
    val profile: CurrentProfileDetails,
    val details: AccountUiState,
    val several: Boolean,
    val serverFirst: Boolean,
    val ownColumn: Boolean,
)

/**
 * The current profile: its label when it is one among several, who it is, then what is known of its account
 * and its server. The app's version is not the profile's: it has its place in More › About.
 *
 * Everything but the server's version is what the registry keeps, shown at once and whatever the server
 * answers. A profile that waits for a sign-in is not told that its server could not be asked, nor offered to
 * try again — it cannot be until its account has signed in —: the version's row is left out, and right under
 * who it is, "Sign in again" is offered instead, the page saying that it is needed and the menu that holds it
 * for the other profiles not being there for this one.
 *
 * @param onSignInAgain the current profile is to sign in again.
 */
@Composable
internal fun CurrentProfileSection(shown: CurrentShown, onRetry: () -> Unit, onSignInAgain: () -> Unit) {
    val profile = shown.profile
    if (shown.several) SectionLabel(stringResource(R.string.profiles_current))
    CurrentProfileHeader(profile)
    if (profile.row.signInNeeded) SignInAgainButton(onSignInAgain, fullWidth = !shown.ownColumn)
    val username = @Composable { DetailRow(stringResource(R.string.account_username), profile.row.username) }
    val email = @Composable {
        profile.email?.let { DetailRow(stringResource(R.string.account_email), it) }
    }
    val server = @Composable {
        DetailRow(stringResource(R.string.account_server), profile.serverUrl)
        if (!profile.row.signInNeeded) ServerVersion(shown.details, onRetry)
    }
    if (shown.serverFirst) {
        server()
        email()
        username()
    } else {
        username()
        email()
        server()
    }
}

/** The label of one of the page's two sections. */
@Composable
internal fun SectionLabel(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp)
            .semantics { heading() },
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.titleSmall,
    )
}

/**
 * Who the current profile is: its avatar, the account's name, "`<username>` · `<kind>`", and that it waits for
 * a sign-in when it does, which the avatar only shows as a mark. Read as one thing.
 */
@Composable
private fun CurrentProfileHeader(profile: CurrentProfileDetails) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .testTag(CurrentProfileHeaderTag)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProfileAvatar(profile.row.avatar, ProfileAvatarSize.Header, ringColor = scheme.background)
        Column(Modifier.weight(1f)) {
            Text(profile.name, style = MaterialTheme.typography.titleMedium)
            Text(
                text = profile.row.line,
                color = scheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (profile.row.signInNeeded) {
                Text(
                    text = stringResource(CoreR.string.sign_in_needed),
                    color = scheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

/**
 * "Sign in again", of the current profile: filled, being what the page waits for while its profile is marked.
 * Its label takes a second line rather than being cut.
 *
 * @param fullWidth across the column; otherwise at its start, as wide as its label.
 */
@Composable
private fun SignInAgainButton(onClick: () -> Unit, fullWidth: Boolean) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .then(if (fullWidth) Modifier.fillMaxWidth() else Modifier)
            .padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
            .testTag(ProfilesPageSignInAgainTag),
    ) {
        WrappingLabel(stringResource(CoreR.string.sign_in_again))
    }
}

/**
 * The version of the server once it answered, nothing when it tells none; while it is asked, a wait; when it could
 * not be, why.
 */
@Composable
private fun ServerVersion(details: AccountUiState, onRetry: () -> Unit) {
    when (details) {
        AccountUiState.Loading -> CircularProgressIndicator(Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        is AccountUiState.Loaded -> details.info.serverVersion?.let { version ->
            DetailRow(stringResource(R.string.account_server_version), version)
        }
        is AccountUiState.Failed -> LoadFailure(details.error, onRetry)
    }
}

@Composable
private fun LoadFailure(error: ApiError, onRetry: () -> Unit) {
    val message = when (error) {
        ApiError.ServerUnreachable -> stringResource(R.string.account_error_unreachable)
        else -> stringResource(R.string.account_error_unexpected)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = message,
            // Announced when it appears: nothing else tells a screen reader user.
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            color = MaterialTheme.colorScheme.error,
        )
        OutlinedButton(onClick = onRetry) { SingleLineLabel(stringResource(R.string.account_retry)) }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    ListItem(
        overlineContent = { Text(label) },
        headlineContent = { Text(value) },
    )
}
