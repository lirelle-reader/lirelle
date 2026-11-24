package app.tfdn.lirelle.feature.settings.about

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tfdn.lirelle.core.designsystem.LocalWindowWidthClass
import app.tfdn.lirelle.core.designsystem.WindowWidthClass
import app.tfdn.lirelle.feature.settings.TwoPanes

/** The columns the licences' rows are laid on in the pane of a wide window. */
private const val PaneColumns = 2

/**
 * About and, from it, the licences: three screens on a narrow window, two panes from the expanded width.
 *
 * @param appVersion the version's name, as the Version row shows it.
 * @param onBack leaves About.
 */
@Composable
fun AboutHost(appVersion: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel = hiltViewModel<LicencesViewModel>()
    val licences by viewModel.licences.collectAsStateWithLifecycle()
    AboutContent(appVersion = appVersion, licences = licences, onBack = onBack, modifier = modifier)
}

/**
 * About with the licences it was given.
 *
 * On a narrow window: About, then the list of licences, then the text of one, each with a way back to the one
 * before; the app's own licence, opened from About, returns to it. From the expanded width: About at the start,
 * and beside it the list, or the text chosen from it.
 *
 * What is shown is kept through a change of width: a text read alone is the one shown in the pane, and the
 * reverse.
 *
 * @param licences null while the list is read: the list then shows its title and no row.
 */
@Composable
internal fun AboutContent(
    appVersion: String,
    licences: Licences?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var shown by rememberSaveable(stateSaver = AboutShownSaver) { mutableStateOf<AboutShown>(AboutShown.About) }
    // Kept here, so that the list is where it was left when a text is closed.
    val listState = rememberLazyGridState()
    val wide = LocalWindowWidthClass.current == WindowWidthClass.Expanded
    val component = (shown as? AboutShown.Text)?.let { licences?.component(it.componentId) }
    val toList = { shown = AboutShown.Licences }
    val toAbout = { shown = AboutShown.About }
    val toAppLicence = { shown = AboutShown.Text(AppLicenceId) }
    val choose: (Component) -> Unit = { shown = AboutShown.Text(it.id) }
    val aboutPage = rememberMovableAboutPage()
    val textPage = rememberMovableTextPage()
    Box(modifier) {
        when {
            wide -> {
                // The text has an arrow back to the list: the system's Back does the same.
                BackHandler(enabled = component != null, onBack = toList)
                TwoPanes(
                    list = { aboutPage(appVersion, toAppLicence, null, onBack) },
                    detail = {
                        if (component != null) {
                            textPage(component, true, toList)
                        } else {
                            LicencesListPage(licences, PaneColumns, listState, onChoose = choose, onBack = null)
                        }
                    },
                )
            }
            shown == AboutShown.About -> aboutPage(appVersion, toAppLicence, toList, onBack)
            component != null -> {
                // The app's licence is opened from About, a component's from the list: back returns there.
                val back = if (component.id == AppLicenceId) toAbout else toList
                BackHandler(onBack = back)
                textPage(component, false, back)
            }
            else -> {
                BackHandler(onBack = toAbout)
                LicencesListPage(licences, columns = 1, listState, onChoose = choose, onBack = toAbout)
            }
        }
    }
}

/**
 * About, drawn as one and the same wherever it is called from: alone on a narrow window, in the pane of a wide
 * one. How far it was scrolled then goes with it through a change of width.
 */
@Composable
private fun rememberMovableAboutPage(): @Composable (String, () -> Unit, (() -> Unit)?, () -> Unit) -> Unit =
    remember {
        movableContentOf {
                appVersion: String,
                onOpenAppLicence: () -> Unit,
                onOpenLicences: (() -> Unit)?,
                onBack: () -> Unit,
            ->
            AboutPage(
                appVersion = appVersion,
                onOpenAppLicence = onOpenAppLicence,
                onOpenLicences = onOpenLicences,
                onBack = onBack,
            )
        }
    }

/**
 * A licence's text, drawn as one and the same alone or in the pane, for the same reason. The text of another
 * component starts at its top. The list needs none of this: where it was left is kept by its caller.
 */
@Composable
private fun rememberMovableTextPage(): @Composable (Component, Boolean, () -> Unit) -> Unit = remember {
    movableContentOf { component: Component, inPane: Boolean, onBack: () -> Unit ->
        key(component.id) { LicenceTextPage(component = component, inPane = inPane, onBack = onBack) }
    }
}
