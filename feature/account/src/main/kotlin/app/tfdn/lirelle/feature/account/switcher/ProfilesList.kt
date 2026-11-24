package app.tfdn.lirelle.feature.account.switcher

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.ui.ProfileAvatar
import app.tfdn.lirelle.core.ui.ProfileAvatarSize
import app.tfdn.lirelle.core.ui.R as CoreR
import app.tfdn.lirelle.feature.account.R

/** The row of the profile of an id is tagged with this and the id. */
const val ProfileRowTagPrefix = "profile-row-"
const val ProfilesCheckTag = "profiles-current-check"
const val ProfilesAddTag = "profiles-add"
const val ProfilesManageTag = "profiles-manage"

private val RowHeight = 72.dp

/** A row with the third line, of a profile that waits for a sign-in. */
private val MarkedRowHeight = 88.dp
private val ActionHeight = 56.dp
private val RowGap = 16.dp
private val CheckSize = 24.dp
private val ActionIconSize = 22.dp
private val DashedStroke = 1.5.dp
private val Dash = 4.dp

/**
 * What the list of profiles leads to. Each closes the list first: none leaves it open behind what it opens.
 *
 * @property onPick a profile was chosen, the current one or another.
 * @property onAdd "Add a profile".
 * @property onManage "Manage profiles".
 */
class ProfilesActions(
    val onPick: (ProfileRow) -> Unit,
    val onAdd: () -> Unit,
    val onManage: () -> Unit,
)

/**
 * The profiles of the device and what can be done about them: one row each, the current one set apart and
 * checked, then "Add a profile" and "Manage profiles". The same in the sheet of a phone and in the menu of a
 * wide screen, which only differ by what holds it.
 *
 * A row is read as one thing — who, where, and whether it is the current one or waits for a sign-in —, not
 * as its texts one after the other.
 *
 * @param ground the colour of what holds the list: it rings the avatars' badges.
 * @param edge the space kept at both sides of every row.
 */
@Composable
fun ProfilesList(
    state: SwitcherState,
    actions: ProfilesActions,
    ground: Color,
    edge: Dp,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.profiles_title),
            modifier = Modifier
                .padding(start = edge, end = edge, bottom = 8.dp)
                .semantics { heading() },
            style = MaterialTheme.typography.titleLarge,
        )
        state.rows.forEach { row ->
            // The current profile lies on a tone of its own, which then is the ground of its avatar.
            ProfileItem(row, if (row.current) scheme.surfaceContainerHigh else ground, edge) { actions.onPick(row) }
        }
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = edge, vertical = 8.dp),
            color = scheme.outlineVariant,
        )
        ActionItem(
            label = stringResource(R.string.profiles_add),
            look = ActionLook(Icons.Outlined.Add, scheme.primary, FontWeight.Medium, dashed = true),
            modifier = Modifier.testTag(ProfilesAddTag),
            edge = edge,
            onClick = actions.onAdd,
        )
        ActionItem(
            label = stringResource(R.string.profiles_manage),
            look = ActionLook(Icons.Outlined.Settings, scheme.onSurfaceVariant, FontWeight.Normal, dashed = false),
            modifier = Modifier.testTag(ProfilesManageTag),
            edge = edge,
            onClick = actions.onManage,
        )
    }
}

/** What a screen reader says of a row: its two lines, then what sets the profile apart. */
@Composable
internal fun ProfileRow.said(): String {
    val named = stringResource(R.string.profiles_row_said, line, server)
    val placed = if (current) stringResource(R.string.profiles_said_current, named) else named
    return if (signInNeeded) stringResource(R.string.profiles_said_sign_in_needed, placed) else placed
}

/**
 * One profile, with the check at the end of the current one's row. The row is as high as its lines need at
 * the text size in use; what is too wide for it is cut by [ProfileTexts], never laid over the check.
 */
@Composable
private fun ProfileItem(row: ProfileRow, ground: Color, edge: Dp, onClick: () -> Unit) {
    val said = row.said()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ground)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = said }
            .testTag(ProfileRowTagPrefix + row.id)
            .heightIn(min = if (row.signInNeeded) MarkedRowHeight else RowHeight)
            .padding(horizontal = edge, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(RowGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProfileAvatar(row.avatar, ProfileAvatarSize.Row, ringColor = ground)
        ProfileTexts(row, Modifier.weight(1f))
        if (row.current) {
            // Decoration: the row says "current profile" itself.
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = null,
                modifier = Modifier.size(CheckSize).testTag(ProfilesCheckTag),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * The lines of a profile wherever it is listed: who, where, and that it waits for a sign-in when it does.
 *
 * "`<username>` · `<kind>`" and the server's address each stay on one line and end with an ellipsis when
 * they do not fit: a username and an address have nowhere to break, and a list whose rows all have the same
 * two lines is read at a glance. On a narrow screen at the largest text size even an ordinary name is cut;
 * the avatar and the address beside it still tell the profiles apart. The mark wraps: it is a sentence.
 */
@Composable
internal fun ProfileTexts(row: ProfileRow, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier) {
        Text(
            text = row.line,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = row.server,
            color = scheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (row.signInNeeded) {
            Text(
                text = stringResource(CoreR.string.sign_in_needed),
                color = scheme.error,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/**
 * How an action of the list looks.
 *
 * @property dashed the icon stands in a dashed circle, the place of an avatar that is not there yet.
 */
private class ActionLook(val icon: ImageVector, val color: Color, val weight: FontWeight, val dashed: Boolean)

/** "Add a profile" or "Manage profiles": an icon where the rows have their avatar, and a label that may wrap. */
@Composable
private fun ActionItem(label: String, look: ActionLook, modifier: Modifier, edge: Dp, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val outline = scheme.outline
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = ActionHeight)
            .padding(horizontal = edge, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(RowGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(ProfileAvatarSize.Row.diameter)
                .then(if (look.dashed) Modifier.dashedCircle(outline) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Icon(look.icon, contentDescription = null, modifier = Modifier.size(ActionIconSize), tint = look.color)
        }
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            color = if (look.dashed) look.color else scheme.onSurface,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = look.weight,
        )
    }
}

private fun Modifier.dashedCircle(color: Color): Modifier = drawBehind {
    val stroke = DashedStroke.toPx()
    drawCircle(
        color = color,
        radius = (size.minDimension - stroke) / 2,
        style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(Dash.toPx(), Dash.toPx()))),
    )
}
