package app.tfdn.lirelle.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import app.tfdn.lirelle.feature.downloads.DownloadsRoute
import app.tfdn.lirelle.shell.TopLevelDestination
import app.tfdn.lirelle.shell.select

/**
 * Downloads, and the detail screens opened from it: the page of a downloaded book and what that page leads to.
 *
 * @param modes open the reader or the player above the shell: on a book, where it was left or from its start.
 */
fun NavGraphBuilder.downloadsDestinations(navController: NavHostController, modes: BookModes) {
    composable<DownloadsDestination> { entry ->
        DownloadsRoute(
            onOpenBook = entry.ifResumedWith { navController.navigate(BookDestination(it.value)) },
            onBrowseLibrary = entry.ifResumed { navController.select(TopLevelDestination.Library) },
        )
    }
    detailDestinations(navController, modes)
}
