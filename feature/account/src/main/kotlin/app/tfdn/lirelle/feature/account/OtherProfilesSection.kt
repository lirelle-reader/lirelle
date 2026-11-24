package app.tfdn.lirelle.feature.account

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.designsystem.WrappingLabel
import app.tfdn.lirelle.core.ui.ProfileAvatar
import app.tfdn.lirelle.core.ui.ProfileAvatarSize
import app.tfdn.lirelle.core.ui.R as CoreR
import app.tfdn.lirelle.feature.account.switcher.ProfileRow
import app.tfdn.lirelle.feature.account.switcher.ProfileRowTagPrefix
import app.tfdn.lirelle.feature.account.switcher.ProfileTexts
import app.tfdn.lirelle.feature.account.switcher.said

/** The button at the end of the row of the profile of an id is tagged with this and the id. */
const val ProfileActionsTagPrefix = "profile-actions-"
const val ProfilesPageAddTag = "profiles-page-add"
const val ProfilesPageRemoveTag = "profiles-page-remove"
const val ProfileSignInAgainTag = "profile-sign-in-again"
const val ProfileRemoveTag = "profile-remove"

private val RowHeight = 72.dp

/** A row with the third line, of a profile that waits for a sign-in. */
private val MarkedRowHeight = 88.dp
private val ButtonIconSize = 18.dp

/** The profiles that sleep, in order of use, under their label. */
@Composable
internal fun OtherProfilesSection(others: List<ProfileRow>, actions: ProfilesPageActions) {
    SectionLabel(stringResource(R.string.profiles_others))
    others.forEach { OtherProfileRow(it, actions) }
}

/**
 * A profile that sleeps: the same row as in the list the profile button opens — read as one thing, and a tap
 * switches to it —, then a button of its own for what else can be done about it.
 */
@Composable
private fun OtherProfileRow(row: ProfileRow, actions: ProfilesPageActions) {
    val said = row.said()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clickable(role = Role.Button) { actions.onSwitch(row.id) }
                .semantics { contentDescription = said }
                .testTag(ProfileRowTagPrefix + row.id)
                .heightIn(min = if (row.signInNeeded) MarkedRowHeight else RowHeight)
                .padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProfileAvatar(row.avatar, ProfileAvatarSize.Row, ringColor = MaterialTheme.colorScheme.background)
            ProfileTexts(row, Modifier.weight(1f))
        }
        OtherProfileActions(row, actions)
    }
}

/**
 * The button at the end of a row, which names the profile it is about, and its menu: "Sign in again" only for
 * a profile known to need it, and "Remove this profile". Choosing closes the menu first.
 */
@Composable
private fun OtherProfileActions(row: ProfileRow, actions: ProfilesPageActions) {
    var open by rememberSaveable(row.id) { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag(ProfileActionsTagPrefix + row.id)) {
            Icon(
                imageVector = Icons.Outlined.MoreVert,
                contentDescription = stringResource(R.string.profiles_more_actions, row.username, row.kindName),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (row.signInNeeded) {
                DropdownMenuItem(
                    text = { Text(stringResource(CoreR.string.sign_in_again)) },
                    onClick = {
                        open = false
                        actions.onSignInAgain(row.id)
                    },
                    modifier = Modifier.testTag(ProfileSignInAgainTag),
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.profiles_remove), color = MaterialTheme.colorScheme.error) },
                onClick = {
                    open = false
                    actions.onRemoveOther(row.id)
                },
                modifier = Modifier.testTag(ProfileRemoveTag),
            )
        }
    }
}

/** "Add a profile": the page's one filled button. Its label takes a second line rather than being cut. */
@Composable
internal fun AddProfileButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .testTag(ProfilesPageAddTag),
    ) {
        Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(ButtonIconSize))
        WrappingLabel(stringResource(R.string.profiles_add), Modifier.padding(start = 8.dp).weight(1f, fill = false))
    }
}

/** "Remove this profile", of the current one: outlined, its label in the colour of what cannot be undone. */
@Composable
internal fun RemoveProfileButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .testTag(ProfilesPageRemoveTag),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
    ) {
        WrappingLabel(stringResource(R.string.profiles_remove))
    }
}
