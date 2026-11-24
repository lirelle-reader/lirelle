package app.tfdn.lirelle.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailDefaults
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuite
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldLayout
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.designsystem.SingleLineLabel

internal const val ShellRailFooterTag = "shell-rail-footer"

/** Between the destinations of the rail, as the rail itself spaces what it is given. */
private val RailItemSpacing = 4.dp

/** Under what stands at the bottom of the rail, as the canvas has it. */
private val RailFooterBottom = 12.dp

/**
 * The destinations of the shell as they are now, for the bar or the rail.
 *
 * @property isSelected whether a destination is the one in front.
 * @property onSelect a destination was tapped.
 */
internal class ShellDestinations(
    val isSelected: (TopLevelDestination) -> Boolean,
    val onSelect: (TopLevelDestination) -> Unit,
)

/**
 * The bar under the screens or the rail beside them, and the screens: what `NavigationSuiteScaffold` lays
 * out, put together here from the library's own parts because that scaffold has no room, at the bottom of
 * its rail, for the profile button. The bar is the library's; the rail is [ShellRail]. One layout for both,
 * so that the screens stay where they are in the composition when the window gets wider or narrower.
 *
 * The window insets are handled as `NavigationSuiteScaffold` of material3-adaptive-navigation-suite 1.4.0
 * handles them — the bar or the rail keeps clear of the system's bars on its own side, and the screens are
 * told that side is taken. That is a copy of the library's behaviour, not a call to it: it must be read
 * again against the library's source each time that library is upgraded. `ShellInsetsTest` pins the result.
 *
 * @param railFooter what stands at the bottom of the rail, under the destinations. Not shown with the bar.
 */
@Composable
internal fun ShellScaffold(
    layoutType: NavigationSuiteType,
    destinations: ShellDestinations,
    modifier: Modifier = Modifier,
    railFooter: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val rail = layoutType == NavigationSuiteType.NavigationRail
    Surface(
        modifier = modifier,
        color = NavigationSuiteScaffoldDefaults.containerColor,
        contentColor = NavigationSuiteScaffoldDefaults.contentColor,
    ) {
        NavigationSuiteScaffoldLayout(
            navigationSuite = { if (rail) ShellRail(destinations, railFooter) else ShellBar(destinations) },
            layoutType = layoutType,
        ) {
            // The bar or the rail keeps clear of the system's bars on its side: the screens need not again.
            val taken = if (rail) {
                NavigationRailDefaults.windowInsets.only(WindowInsetsSides.Start)
            } else {
                NavigationBarDefaults.windowInsets.only(WindowInsetsSides.Bottom)
            }
            Box(Modifier.consumeWindowInsets(taken)) { content() }
        }
    }
}

/** The bottom bar, the library's own. */
@Composable
private fun ShellBar(destinations: ShellDestinations) {
    NavigationSuite(layoutType = NavigationSuiteType.NavigationBar) {
        TopLevelDestination.entries.forEach { destination ->
            item(
                selected = destinations.isSelected(destination),
                onClick = { destinations.onSelect(destination) },
                icon = { Icon(destination.icon, contentDescription = null) },
                // One line: the item does not grow, and a wrapped label breaks mid-word.
                label = { SingleLineLabel(stringResource(destination.label)) },
                modifier = Modifier.testTag(destination.testTag),
            )
        }
    }
}

/**
 * The rail: the destinations as the library draws them, in its colours, and [footer] at the bottom. The rail
 * keeps clear of the system's bars and of a display cutout by itself.
 *
 * The footer is always whole. In a window too low for everything — a phone on its side —, it is the
 * destinations that scroll above it.
 */
@Composable
private fun ShellRail(destinations: ShellDestinations, footer: @Composable () -> Unit) {
    val colors = NavigationSuiteDefaults.colors()
    val itemColors = NavigationSuiteDefaults.itemColors().navigationRailItemColors
    NavigationRail(
        containerColor = colors.navigationRailContainerColor,
        contentColor = colors.navigationRailContentColor,
    ) {
        Column(
            // The rail makes its own column the group its items are chosen from; the items are no longer
            // that column's children, so this one says it: a screen reader counts a destination among the
            // five ("2 of 5") from the group that holds it.
            modifier = Modifier.weight(1f).selectableGroup().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(RailItemSpacing),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TopLevelDestination.entries.forEach { destination ->
                NavigationRailItem(
                    selected = destinations.isSelected(destination),
                    onClick = { destinations.onSelect(destination) },
                    icon = { Icon(destination.icon, contentDescription = null) },
                    modifier = Modifier.testTag(destination.testTag),
                    // One line: the item does not grow, and a wrapped label breaks mid-word.
                    label = { SingleLineLabel(stringResource(destination.label)) },
                    colors = itemColors,
                )
            }
        }
        Box(Modifier.padding(bottom = RailFooterBottom).testTag(ShellRailFooterTag)) { footer() }
    }
}
