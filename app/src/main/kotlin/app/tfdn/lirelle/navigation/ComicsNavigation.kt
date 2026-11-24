package app.tfdn.lirelle.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.feature.comics.ComicReaderScreen
import app.tfdn.lirelle.feature.comics.ComicReaderViewModel

/**
 * The comics reader, beside the shell as the reader is: shown without the navigation bar or rail, and left
 * for where it was opened from. The next album of a series takes the place of the one just read: Back from it
 * returns to where the first one was opened from, not to the album before.
 *
 * @param comics the comics reader of an album, given the album and where to open it, what leaves it, and what
 *   puts the reader of another album in its place.
 */
internal fun NavGraphBuilder.comicsDestination(
    navController: NavHostController,
    comics: @Composable (album: ComicsDestination, onLeave: () -> Unit, onOpenNext: (BookId) -> Unit) -> Unit,
) {
    composable<ComicsDestination> { entry ->
        val leave = { navController.leaveFrom(entry) }
        // Before the screen's own, as the reader's: a Back pressed while the screen is still coming in leaves it.
        BackHandler(onBack = leave)
        comics(entry.toRoute<ComicsDestination>(), leave) { nextId -> navController.replace(entry, nextId) }
    }
}

/**
 * Back to what is under the comics reader of [entry], once: it may ask again while it is leaving, and by then
 * the reader in front may be the next album's, which must stay.
 */
private fun NavHostController.leaveFrom(entry: NavBackStackEntry) {
    if (currentBackStackEntry?.id == entry.id) popBackStack()
}

/** The reader of the album [nextId] in the place of the one of [entry], once: a second request finds it gone. */
private fun NavHostController.replace(entry: NavBackStackEntry, nextId: BookId) {
    if (currentBackStackEntry?.id != entry.id) return
    navigate(ComicsDestination(nextId.value)) { popUpTo(entry.destination.id) { inclusive = true } }
}

/**
 * The comics reader, with a view model of its own for each album and each way to open it: the next album's
 * reader does not get the one of the album just read.
 */
@Composable
internal fun ComicsRoute(album: ComicsDestination, onLeave: () -> Unit, onOpenNext: (BookId) -> Unit) {
    // An album read again starts over once: a reader made again later goes on where the user got to.
    val first = rememberIsFirstShowing()
    ComicReaderScreen(
        viewModel = hiltViewModel<ComicReaderViewModel, ComicReaderViewModel.Factory>(
            key = "comics-${album.bookId}-${album.fromStart}",
            creationCallback = { it.create(album.bookId, album.fromStart && first) },
        ),
        onLeave = onLeave,
        onOpenNext = onOpenNext,
    )
}
