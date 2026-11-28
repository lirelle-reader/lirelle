package app.tfdn.lirelle.feature.account

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.designsystem.SingleLineLabel
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.ui.ProfileAvatar
import app.tfdn.lirelle.core.ui.ProfileAvatarSize
import app.tfdn.lirelle.core.ui.profileAvatarModel
import app.tfdn.lirelle.feature.account.switcher.ProfileRow
import app.tfdn.lirelle.feature.account.switcher.ProfileTexts
import app.tfdn.lirelle.feature.account.switcher.said

/** The profile the question is about, at the top of the confirmation. */
const val RemovalProfileTag = "removal-profile"

/** What goes with the profile: its downloads. */
const val RemovalKeptTag = "removal-kept"

/** What would be lost for good. */
const val RemovalAtRiskTag = "removal-at-risk"

private val LineIconSize = 20.dp

/**
 * What the removal of [profile] does, as its opening sentence says it: what the device keeps for it is erased; of
 * the current one only, that it is signed out; and what its server keeps — for BookOrbit nothing changes there
 * (the session of a profile that sleeps lives until it expires), for Audiobookshelf the profile's session is
 * closed, for Komga and Kavita its key is revoked, current or not. Except a key the user pasted
 * ([ProfileRow.usersOwnKey]), which is only forgotten: it is said to stay valid, and the profile, whose server is
 * told nothing, is not said to be signed out.
 */
@StringRes
internal fun removalBody(profile: ProfileRow): Int = when (profile.kind) {
    ServerKind.BookOrbit ->
        if (profile.current) R.string.profiles_remove_body else R.string.profiles_remove_body_sleeping
    ServerKind.Audiobookshelf ->
        if (profile.current) R.string.profiles_remove_body_session else R.string.profiles_remove_body_sleeping_session
    ServerKind.Komga, ServerKind.Kavita -> when {
        profile.usersOwnKey -> R.string.profiles_remove_body_own_key
        profile.current -> R.string.profiles_remove_body_key
        else -> R.string.profiles_remove_body_sleeping_key
    }
}

/**
 * "Remove this profile?": names the profile — the current one or one that sleeps —, says what the removal does
 * ([removalBody]), then what goes with it: for the current profile its downloads, counted, and, set apart, what
 * would be lost for good because it cannot be sent before; for a profile that sleeps the same without numbers.
 *
 * @param onConfirm the user confirmed; whoever is told takes the answer once.
 */
@Composable
fun RemoveProfileDialog(profile: ProfileRow, cost: RemovalCost, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val lines = removalLines(LocalContext.current, cost)
    val scheme = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { RemovalTitle(profile) },
        text = {
            // In large text the sentences are taller than a small screen leaves the dialog.
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(removalBody(profile)))
                lines.kept?.let { RemovalLine(it, atRisk = false, Modifier.testTag(RemovalKeptTag)) }
                lines.atRisk?.let { RemovalLine(it, atRisk = true, Modifier.testTag(RemovalAtRiskTag)) }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                SingleLineLabel(stringResource(R.string.profiles_remove_confirm), color = scheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { SingleLineLabel(stringResource(R.string.profiles_remove_cancel)) }
        },
    )
}

/** Who the question is about, read as one thing as in the lists of profiles, then the question itself. */
@Composable
private fun RemovalTitle(profile: ProfileRow) {
    val said = profile.said()
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(
            modifier = Modifier
                .semantics(mergeDescendants = true) { contentDescription = said }
                .testTag(RemovalProfileTag),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProfileAvatar(profile.avatar, ProfileAvatarSize.Row, ringColor = AlertDialogDefaults.containerColor)
            ProfileTexts(profile, Modifier.weight(1f))
        }
        Text(stringResource(R.string.profiles_remove_title), modifier = Modifier.semantics { heading() })
    }
}

/**
 * One thing the removal takes: an icon, which is decoration, and the sentence beside it.
 *
 * @param atRisk it is lost for good, not only deleted from the device: said in the colour of an error.
 */
@Composable
private fun RemovalLine(text: String, atRisk: Boolean, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(
            imageVector = if (atRisk) Icons.Outlined.WarningAmber else Icons.Outlined.FileDownload,
            contentDescription = null,
            modifier = Modifier.size(LineIconSize),
            tint = if (atRisk) scheme.error else scheme.onSurfaceVariant,
        )
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            color = if (atRisk) scheme.error else Color.Unspecified,
            fontWeight = if (atRisk) FontWeight.Medium else null,
        )
    }
}

@Preview(name = "Phone", widthDp = 411, heightDp = 891)
@Composable
private fun RemoveProfileDialogPreview() {
    LirelleTheme {
        RemoveProfileDialog(
            profile = ProfileRow(
                id = "p0",
                line = "camille · BookOrbit",
                username = "camille",
                kindName = "BookOrbit",
                kind = ServerKind.BookOrbit,
                usersOwnKey = false,
                server = "livres.example.org",
                avatar = profileAvatarModel("p0", "C", ServerKind.BookOrbit, picture = null, signInNeeded = false),
                current = true,
                signInNeeded = false,
            ),
            cost = RemovalCost.Counted(books = 12, bytes = 1_400_000_000, lost = PendingCounts(2, 0, 0, 1)),
            onConfirm = {},
            onDismiss = {},
        )
    }
}
