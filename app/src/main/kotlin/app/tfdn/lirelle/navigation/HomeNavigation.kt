package app.tfdn.lirelle.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.SourceCapabilities
import app.tfdn.lirelle.core.library.model.HomeRowKind
import app.tfdn.lirelle.core.library.model.StatusFilter
import app.tfdn.lirelle.feature.home.HomeRoute
import app.tfdn.lirelle.feature.home.HomeRowScreen
import app.tfdn.lirelle.feature.home.titleRes
import app.tfdn.lirelle.feature.library.books.BookListDetailScreen
import app.tfdn.lirelle.feature.library.books.BookListScope

/**
 * Home, the full list behind each of its rows, and the detail screens opened from them.
 *
 * @param modes open the reader or the player above the shell: on a book, where it was left or from its start.
 * @param onSignInAgain opens the sign-in screens, above the shell, for the profile of that id: what Home's
 *   notice of an expired session asks for.
 * @param onOpenDownloads selects the Downloads destination: what Home offers under that notice while it has no
 *   row to show. Like [onSignInAgain] it has no default: the app that forgets one does not compile.
 * @param titleAction what stands at the end of Home's title: the profile button.
 */
fun NavGraphBuilder.homeDestinations(
    navController: NavHostController,
    modes: BookModes,
    onSignInAgain: (profileId: String) -> Unit,
    onOpenDownloads: () -> Unit,
    titleAction: @Composable () -> Unit = {},
) {
    composable<HomeDestination> { entry ->
        HomeRoute(
            onOpenBook = entry.ifResumedWith { navController.navigate(BookDestination(it.value)) },
            // A book of the "Continue" row goes on where it was left, read or listened to.
            onRead = entry.ifResumedWith { modes.onRead(ReaderDestination(it.value)) },
            // A comic goes on in the comics reader.
            onReadComic = entry.ifResumedWith { modes.onReadComic(ComicsDestination(it.value)) },
            onListen = entry.ifResumedWith { modes.onListen(it, false) },
            // One whose only narration is in its read-along EPUB is listened to in the reader.
            onNarrate = entry.ifResumedWith { modes.onRead(ReaderDestination(it.value, narrate = true)) },
            onSeeAll = entry.ifResumedWith { navController.navigate(HomeListDestination(it.name)) },
            // Guarded where it is made: the sign-in screens open once, from the shell in front.
            onSignInAgain = onSignInAgain,
            // As a tap on the bar's item: nothing to guard, selected twice it is selected.
            onOpenDownloads = onOpenDownloads,
            titleAction = titleAction,
        )
    }
    composable<HomeListDestination> { entry ->
        val kind = HomeRowKind.valueOf(entry.toRoute<HomeListDestination>().kind)
        val onBack = entry.ifResumed { navController.popBackStack() }
        val onOpenBook = entry.ifResumedWith<BookId> { navController.navigate(BookDestination(it.value)) }
        val asRow = hiltViewModel<HomeListViewModel>().seesAllAsRow(kind)
        // Null when the row opens its own screen, whatever its kind: the source has no list of all its books.
        when (kind.takeUnless { asRow }) {
            null, HomeRowKind.Continue, HomeRowKind.UpNext -> HomeRowScreen(kind, onBack, onOpenBook)
            HomeRowKind.WantToRead -> BookListDetailScreen(
                title = stringResource(kind.titleRes()),
                scope = BookListScope.status(StatusFilter.WantToRead, WantToReadKey),
                onBack = onBack,
                onOpenBook = onOpenBook,
            )
            HomeRowKind.RecentlyAdded -> BookListDetailScreen(
                title = stringResource(kind.titleRes()),
                scope = BookListScope.recentlyAdded(),
                onBack = onBack,
                onOpenBook = onOpenBook,
            )
        }
    }
    detailDestinations(navController, modes)
}

private const val WantToReadKey = "want-to-read"

/**
 * Whether "See all" of a Home row opens the row's own screen rather than a list of the library's books: always for
 * Continue and Up next, and for every row when the source cannot list its books.
 */
internal fun HomeRowKind.seeAllAsRow(capabilities: SourceCapabilities): Boolean =
    this == HomeRowKind.Continue || this == HomeRowKind.UpNext || !capabilities.libraryBooks
