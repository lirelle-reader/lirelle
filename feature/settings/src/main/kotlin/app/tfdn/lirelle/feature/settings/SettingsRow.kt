package app.tfdn.lirelle.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val RowMinHeight = 72.dp
private val RowPadding = 16.dp
private val IconSize = 24.dp
private val IconGap = 16.dp

/** The margin around a row in a pane, where the row is a rounded shape that can be filled. */
private val PaneRowMargin = 8.dp

/**
 * How the rows of the list are drawn.
 *
 * @property inPane the list is beside the page chosen: a row is a rounded shape, filled when it is the one
 *   chosen, and has no chevron, since it leads nowhere else.
 * @property decorated there is room for the icons beside the names.
 */
internal class SettingsRowLook(val inPane: Boolean, val decorated: Boolean)

/**
 * The width a row needs to show a name whole on one line with its icon, and its chevron where it has one.
 *
 * @param nameWidth the width of the longest name.
 */
internal fun decoratedRowWidth(nameWidth: Dp, inPane: Boolean): Dp {
    val icon = IconSize + IconGap
    val margins = if (inPane) PaneRowMargin * 2 else 0.dp
    return margins + RowPadding * 2 + icon + nameWidth + if (inPane) 0.dp else icon
}

/**
 * A row of the list: the icon of a page, its name over a line saying what it holds, and a chevron where the
 * row opens a screen.
 *
 * @param about the line under the name; null when there is nothing to say yet.
 * @param marked the page is the one shown beside the list.
 */
@Composable
internal fun SettingsRow(
    page: SettingsPage,
    about: String?,
    marked: Boolean,
    look: SettingsRowLook,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val shaped = if (look.inPane) {
        Modifier
            .padding(horizontal = PaneRowMargin)
            .clip(MaterialTheme.shapes.large)
            .background(if (marked) colors.secondaryContainer else Color.Transparent)
            // Selected or not, which a screen reader says: the rows of a pane are a choice among pages.
            .selectable(selected = marked, onClick = onClick)
    } else {
        Modifier.clickable(onClick = onClick)
    }
    Row(
        modifier = modifier
            .testTag(settingsRowTag(page))
            .fillMaxWidth()
            .then(shaped)
            .heightIn(min = RowMinHeight)
            .padding(horizontal = RowPadding, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(IconGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val onRow = if (marked) colors.onSecondaryContainer else colors.onSurface
        val onRowVariant = if (marked) colors.onSecondaryContainer else colors.onSurfaceVariant
        // The name says it: the icons are decoration.
        if (look.decorated) Icon(page.icon, contentDescription = null, Modifier.size(IconSize), tint = onRowVariant)
        Column(Modifier.weight(1f)) {
            Text(stringResource(page.title), style = MaterialTheme.typography.bodyLarge, color = onRow)
            if (about != null) Text(about, style = MaterialTheme.typography.bodyMedium, color = onRowVariant)
        }
        if (look.decorated && !look.inPane) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(IconSize),
                tint = onRowVariant,
            )
        }
    }
}

/** The name of a page, in the list and as the title of the page. */
internal val SettingsPage.title: Int
    get() = when (this) {
        SettingsPage.Appearance -> R.string.settings_appearance
        SettingsPage.Reading -> R.string.settings_reading
        SettingsPage.Comics -> R.string.settings_comics
        SettingsPage.Player -> R.string.settings_player
        SettingsPage.Downloads -> R.string.settings_downloads
    }

private val SettingsPage.icon: ImageVector
    get() = when (this) {
        SettingsPage.Appearance -> Icons.Outlined.Contrast
        SettingsPage.Reading -> Icons.AutoMirrored.Outlined.MenuBook
        SettingsPage.Comics -> Icons.Outlined.GridView
        SettingsPage.Player -> Icons.Outlined.Headphones
        SettingsPage.Downloads -> Icons.Outlined.Download
    }
