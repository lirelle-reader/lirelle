package app.tfdn.lirelle.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.tfdn.lirelle.core.designsystem.LocalWindowWidthClass
import app.tfdn.lirelle.core.designsystem.WindowWidthClass
import app.tfdn.lirelle.core.ui.SettingsPageFrame
import app.tfdn.lirelle.feature.settings.appearance.AppTheme

/**
 * The settings: on a narrow window the list, or the page chosen from it; from the expanded width, the list and
 * the page side by side.
 *
 * The page chosen is kept through a change of width: a page shown alone is the marked row of the wide layout,
 * and the reverse.
 *
 * @param appTheme the app's theme, which the row of Appearance tells; null until it is known.
 * @param page draws a page; `onBack` is null where the page sits in a pane.
 * @param onBack leaves the settings.
 */
@Composable
fun SettingsHost(
    appTheme: AppTheme?,
    page: @Composable (SettingsPage, onBack: (() -> Unit)?) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Kept by its name, which holds through a restart of the app.
    var chosenName by rememberSaveable { mutableStateOf<String?>(null) }
    val chosen = SettingsPage.entries.firstOrNull { it.name == chosenName }
    val wide = LocalWindowWidthClass.current == WindowWidthClass.Expanded
    val shownPage = rememberMovablePage(page)
    Box(modifier) {
        when {
            wide -> TwoPanes(
                list = {
                    SettingsListPage(
                        selected = chosen ?: SettingsPage.Appearance,
                        appTheme = appTheme,
                        onChoose = { chosenName = it.name },
                        onBack = onBack,
                    )
                },
                // Nothing chosen yet: the first page, so the pane is never empty.
                detail = { shownPage(chosen ?: SettingsPage.Appearance, null) },
            )
            chosen == null -> SettingsListPage(
                selected = null,
                appTheme = appTheme,
                onChoose = { chosenName = it.name },
                onBack = onBack,
            )
            else -> {
                BackHandler { chosenName = null }
                shownPage(chosen) { chosenName = null }
            }
        }
    }
}

/**
 * The page, drawn as one and the same wherever it is called from: alone on a narrow window, in the pane of a
 * wide one. What a page holds of its own (an open dialog, an unfolded choice, how far it was scrolled) then
 * goes with it through a change of width, instead of being lost with the place it was drawn at.
 *
 * Each page has a state of its own: another page starts anew.
 */
@Composable
private fun rememberMovablePage(
    page: @Composable (SettingsPage, onBack: (() -> Unit)?) -> Unit,
): @Composable (SettingsPage, (() -> Unit)?) -> Unit {
    val currentPage by rememberUpdatedState(page)
    return remember {
        movableContentOf { shown: SettingsPage, onBack: (() -> Unit)? ->
            key(shown) { currentPage(shown, onBack) }
        }
    }
}

/** The list under its title and the arrow that leaves the settings: a screen when narrow, a pane when wide. */
@Composable
private fun SettingsListPage(
    selected: SettingsPage?,
    appTheme: AppTheme?,
    onChoose: (SettingsPage) -> Unit,
    onBack: () -> Unit,
) {
    SettingsPageFrame(title = stringResource(R.string.settings_title), onBack = onBack) {
        SettingsList(selected = selected, appTheme = appTheme, onChoose = onChoose)
    }
}
