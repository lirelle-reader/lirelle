package app.tfdn.lirelle.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.ReadId
import app.tfdn.lirelle.feature.history.HistoryRoute
import app.tfdn.lirelle.feature.history.ReadDetailRoute

/**
 * The reading history, one read of it, and the screens a book of it leads to: the page of the book and what
 * that page opens; the settings and About. They stay in More's graph, so Back stays in More.
 *
 * @param modes open the reader or the player above the shell: on a book, where it was left or from its start.
 * @param appVersion the version's name, as About shows it.
 */
fun NavGraphBuilder.moreDestinations(navController: NavHostController, modes: BookModes, appVersion: String) {
    composable<HistoryDestination> { entry ->
        val openRead = entry.ifResumedWith<Pair<ReadId, BookId?>> { (readId, bookId) ->
            navController.navigate(ReadDetailDestination(readId.value, bookId?.value))
        }
        HistoryRoute(
            onOpenRead = { readId, bookId -> openRead(readId to bookId) },
            onBack = entry.ifResumed { navController.popBackStack() },
        )
    }
    composable<ReadDetailDestination> { entry ->
        ReadDetailRoute(
            onOpenBook = entry.ifResumedWith { navController.navigate(BookDestination(it.value)) },
            onBack = entry.ifResumed { navController.popBackStack() },
        )
    }
    settingsDestinations(navController, appVersion)
    detailDestinations(navController, modes)
}
