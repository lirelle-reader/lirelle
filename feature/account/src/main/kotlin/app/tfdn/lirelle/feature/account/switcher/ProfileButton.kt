package app.tfdn.lirelle.feature.account.switcher

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.ui.ProfileAvatar
import app.tfdn.lirelle.core.ui.ProfileAvatarSize
import app.tfdn.lirelle.feature.account.R

const val ProfileButtonTag = "profile-button"

/** What a finger needs, around an avatar that is smaller. */
private val TouchTarget = 48.dp

/**
 * The profile button: the avatar of the current profile, which opens the list of profiles. Shown from two
 * profiles on: with one there is nobody to switch to, nothing is composed and no room is taken.
 *
 * The avatar says nothing by itself; the button names the profile it stands for — "Profiles: camille,
 * BookOrbit" — and says when that profile waits for a sign-in, which the avatar only shows as a mark.
 *
 * @param ringColor the ground the button lies on, which sets the avatar's badge off from the avatar.
 */
@Composable
fun ProfileButton(
    state: SwitcherState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    ringColor: Color = MaterialTheme.colorScheme.background,
) {
    val current = state.current
    if (!state.severalProfiles || current == null) return
    val named = stringResource(R.string.profiles_button, current.username, current.kindName)
    val said = if (current.signInNeeded) stringResource(R.string.profiles_said_sign_in_needed, named) else named
    Box(
        modifier = modifier
            .size(TouchTarget)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                // Round, and not clipped to the circle: the badge stands out of the avatar, past it.
                indication = ripple(bounded = false, radius = TouchTarget / 2),
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { contentDescription = said }
            .testTag(ProfileButtonTag),
        contentAlignment = Alignment.Center,
    ) {
        ProfileAvatar(current.avatar, ProfileAvatarSize.Button, ringColor = ringColor)
    }
}
