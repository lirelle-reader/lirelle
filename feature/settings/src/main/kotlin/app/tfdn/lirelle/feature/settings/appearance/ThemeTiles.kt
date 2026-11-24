package app.tfdn.lirelle.feature.settings.appearance

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.designsystem.SingleLineLabel
import app.tfdn.lirelle.feature.settings.R

private val TileGap = 8.dp
private val TilePadding = 8.dp
private val TileMinHeight = 96.dp
private val StackedTileMinHeight = 56.dp
private val TileIconSize = 28.dp

/** The test tag of the tile of a theme. */
internal fun themeTileTag(theme: AppTheme): String = "appearance-tile-${theme.name}"

/**
 * The three themes to choose from, one of them at most being chosen: a radio group.
 *
 * The tiles sit on one line, each an icon over its name. Where the names cannot all be whole on that line, a
 * narrow window with large text, the tiles stack instead, the icon before the name: a name is never cut.
 */
@Composable
internal fun ThemeTiles(chosen: AppTheme?, onChoose: (AppTheme) -> Unit, modifier: Modifier = Modifier) {
    val nameStyle = MaterialTheme.typography.labelLarge
    val tileWidth = widestName(nameStyle) + TilePadding * 2
    ProvideTextStyle(nameStyle) {
        BoxWithConstraints(modifier.fillMaxWidth()) {
            if (tileWidth * AppTheme.entries.size + TileGap * (AppTheme.entries.size - 1) <= maxWidth) {
                Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(TileGap)) {
                    AppTheme.entries.forEach { theme ->
                        ThemeTile(theme, theme == chosen, { onChoose(theme) }, Modifier.weight(1f))
                    }
                }
            } else {
                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(TileGap)) {
                    AppTheme.entries.forEach { theme ->
                        StackedThemeTile(theme, theme == chosen) { onChoose(theme) }
                    }
                }
            }
        }
    }
}

/** The room the longest of the three names takes on one line, at the current text size. */
@Composable
private fun widestName(style: TextStyle): Dp {
    val names = AppTheme.entries.map { stringResource(it.label) }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(names, style, density) {
        val widest = names.maxOf { measurer.measure(it, style, softWrap = false, maxLines = 1).size.width }
        // One pixel more: the width was rounded.
        with(density) { (widest + 1).toDp() }
    }
}

/** A tile of the line of three: the icon over the name. */
@Composable
private fun ThemeTile(theme: AppTheme, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .tile(theme, selected, tileLook(), onClick)
            .heightIn(min = TileMinHeight)
            .padding(TilePadding),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ThemeIcon(theme, selected)
        SingleLineLabel(stringResource(theme.label), color = MaterialTheme.colorScheme.onSurface)
    }
}

/** A tile of the stack: the icon before the name, on the whole width. */
@Composable
private fun StackedThemeTile(theme: AppTheme, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .tile(theme, selected, tileLook(), onClick)
            .heightIn(min = StackedTileMinHeight)
            .padding(horizontal = 16.dp, vertical = TilePadding),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ThemeIcon(theme, selected)
        SingleLineLabel(stringResource(theme.label), color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun ThemeIcon(theme: AppTheme, selected: Boolean) {
    Icon(
        imageVector = theme.icon,
        // The name beside it says it.
        contentDescription = null,
        tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(TileIconSize),
    )
}

/** The look and the behaviour the two shapes of a tile share: the chosen one is outlined and filled. */
private fun Modifier.tile(theme: AppTheme, selected: Boolean, look: TileLook, onClick: () -> Unit): Modifier = this
    .testTag(themeTileTag(theme))
    .clip(look.shape)
    .background(if (selected) look.colors.surfaceContainerHigh else look.colors.surface)
    .border(
        width = if (selected) 2.dp else 1.dp,
        color = if (selected) look.colors.primary else look.colors.outlineVariant,
        shape = look.shape,
    )
    .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)

/** What a tile is drawn with, read from the theme once for its modifier. */
private class TileLook(val colors: ColorScheme, val shape: Shape)

@Composable
private fun tileLook(): TileLook = TileLook(MaterialTheme.colorScheme, MaterialTheme.shapes.medium)

private val AppTheme.label: Int
    get() = when (this) {
        AppTheme.System -> R.string.appearance_system
        AppTheme.Light -> R.string.appearance_light
        AppTheme.Dark -> R.string.appearance_dark
    }

private val AppTheme.icon: ImageVector
    get() = when (this) {
        AppTheme.System -> Icons.Outlined.PhoneAndroid
        AppTheme.Light -> Icons.Outlined.LightMode
        AppTheme.Dark -> Icons.Outlined.DarkMode
    }
