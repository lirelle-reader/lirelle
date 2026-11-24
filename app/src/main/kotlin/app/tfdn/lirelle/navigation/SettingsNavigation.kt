package app.tfdn.lirelle.navigation

import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import app.tfdn.lirelle.feature.comics.settings.ComicSettingsRoute
import app.tfdn.lirelle.feature.player.PlayerSettingsRoute
import app.tfdn.lirelle.feature.reader.settings.ReadingSettingsRoute
import app.tfdn.lirelle.feature.settings.SettingsHost
import app.tfdn.lirelle.feature.settings.SettingsPage
import app.tfdn.lirelle.feature.settings.about.AboutHost
import app.tfdn.lirelle.feature.settings.appearance.AppearanceRoute
import app.tfdn.lirelle.feature.settings.appearance.AppearanceViewModel
import app.tfdn.lirelle.shell.DownloadsSettingsRoute

/**
 * The settings and About, each one destination of More's graph: the page chosen in the settings and the licence
 * read in About are held by their screens, so Back goes through them before it returns to More.
 *
 * The view model of a page belongs to the settings' destination: the pages hold no state of their own.
 *
 * @param appVersion the version's name, as About shows it.
 */
fun NavGraphBuilder.settingsDestinations(navController: NavHostController, appVersion: String) {
    composable<SettingsDestination> { entry ->
        // The row of Appearance tells the theme chosen.
        val appTheme by hiltViewModel<AppearanceViewModel>().theme.collectAsStateWithLifecycle()
        SettingsHost(
            appTheme = appTheme,
            page = { page, onBack ->
                when (page) {
                    SettingsPage.Appearance -> AppearanceRoute(onBack)
                    SettingsPage.Reading -> ReadingSettingsRoute(onBack)
                    SettingsPage.Comics -> ComicSettingsRoute(onBack)
                    SettingsPage.Player -> PlayerSettingsRoute(onBack)
                    SettingsPage.Downloads -> DownloadsSettingsRoute(onBack)
                }
            },
            onBack = entry.ifResumed { navController.popBackStack() },
        )
    }
    composable<AboutDestination> { entry ->
        AboutHost(appVersion = appVersion, onBack = entry.ifResumed { navController.popBackStack() })
    }
}
