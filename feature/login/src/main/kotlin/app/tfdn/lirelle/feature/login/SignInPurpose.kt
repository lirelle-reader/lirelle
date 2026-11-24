package app.tfdn.lirelle.feature.login

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import app.tfdn.lirelle.core.session.Profile
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.ui.profileLine

/** What the sign-in screens are shown for. */
sealed interface SignInPurpose {
    /** Nobody is signed in. */
    data object First : SignInPurpose

    /** Another profile is added over the current one. */
    data object Add : SignInPurpose

    /** The profile [profileId] signs in again: its server, which cannot be changed. */
    data class Again(val profileId: String) : SignInPurpose
}

/**
 * A profile as the sign-in screens name it: "`<username>` · `<kind>`", and its server. Only what is shown: the
 * sign-in screens are about no profile, and hold nothing of one that could change under them.
 */
data class ProfileLabel(val username: String, val kind: ServerKind, val serverUrl: String)

internal fun Profile.label() = ProfileLabel(session.user.username, kind, session.serverUrl)

/** "`<username>` · `<kind>`", the kind of server in words: as every list of profiles names it. */
@Composable
internal fun ProfileLabel.text(): String = profileLine(LocalResources.current, username, kind)
