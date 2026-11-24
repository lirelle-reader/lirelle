package app.tfdn.lirelle.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import app.tfdn.lirelle.core.ids.AuthorId
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.SeriesId
import app.tfdn.lirelle.core.ids.ShelfId
import app.tfdn.lirelle.core.library.model.BookListSource
import app.tfdn.lirelle.core.library.model.ShelfKind
import app.tfdn.lirelle.feature.book.BookScreen
import app.tfdn.lirelle.feature.library.LibraryRoute
import app.tfdn.lirelle.feature.library.authors.AuthorScreen
import app.tfdn.lirelle.feature.library.books.BookListDetailScreen
import app.tfdn.lirelle.feature.library.books.BookListScope
import app.tfdn.lirelle.feature.library.series.SeriesScreen
import app.tfdn.lirelle.feature.library.shelves.ShelfScreen
import app.tfdn.lirelle.shell.TopLevelDestination
import app.tfdn.lirelle.shell.select

/**
 * The Library and everything opened from it. These screens stay inside the Library graph,
 * so the navigation bar or rail remains visible and the back stack is the Library's own.
 *
 * @param modes open the reader or the player above the shell: on a book, where it was left or from its start.
 * @param titleAction what stands at the end of the Library's title: the profile button.
 */
fun NavGraphBuilder.libraryDestinations(
    navController: NavHostController,
    modes: BookModes,
    titleAction: @Composable () -> Unit = {},
) {
    composable<LibraryDestination> { entry ->
        LibraryRoute(
            onOpenBook = entry.ifResumedWith { navController.navigate(BookDestination(it.value)) },
            onOpenSeries = entry.ifResumedWith { navController.navigate(SeriesDestination(it.value)) },
            onOpenAuthor = entry.ifResumedWith { navController.navigate(AuthorDestination(it.value)) },
            onOpenShelf = entry.ifResumedWith { shelf ->
                navController.navigate(ShelfDestination(shelf.kind == ShelfKind.SmartList, shelf.id.value, shelf.name))
            },
            titleAction = titleAction,
        )
    }
    detailDestinations(navController, modes)
}

/**
 * The screens every tab can open. Each tab's graph gets its own copy, so Back stays in the tab.
 *
 * @param modes open the reader or the player above the shell: on a book, where it was left or from its start.
 */
fun NavGraphBuilder.detailDestinations(navController: NavHostController, modes: BookModes) {
    composable<SeriesDestination> { entry ->
        SeriesScreen(
            seriesId = SeriesId(entry.toRoute<SeriesDestination>().id),
            onBack = entry.ifResumed { navController.popBackStack() },
            onOpenBook = entry.ifResumedWith { navController.navigate(BookDestination(it.value)) },
        )
    }
    composable<AuthorDestination> { entry ->
        AuthorScreen(
            authorId = AuthorId(entry.toRoute<AuthorDestination>().id),
            onBack = entry.ifResumed { navController.popBackStack() },
            onOpenBook = entry.ifResumedWith { navController.navigate(BookDestination(it.value)) },
        )
    }
    composable<ShelfDestination> { entry ->
        val shelf = entry.toRoute<ShelfDestination>()
        val id = ShelfId(shelf.id)
        ShelfScreen(
            source = if (shelf.smartList) BookListSource.SmartList(id) else BookListSource.Collection(id),
            name = shelf.name,
            onBack = entry.ifResumed { navController.popBackStack() },
            onOpenBook = entry.ifResumedWith { navController.navigate(BookDestination(it.value)) },
        )
    }
    composable<BookDestination> { entry ->
        val read = entry.ifResumedWith(modes.onRead)
        val readComic = entry.ifResumedWith(modes.onReadComic)
        val listen = entry.ifResumedWith<Pair<BookId, Boolean>> { (bookId, fromStart) ->
            modes.onListen(bookId, fromStart)
        }
        BookScreen(
            bookId = BookId(entry.toRoute<BookDestination>().id),
            onBack = entry.ifResumed { navController.popBackStack() },
            onOpenBook = entry.ifResumedWith { navController.navigate(BookDestination(it.value)) },
            onOpenAuthor = entry.ifResumedWith { navController.navigate(AuthorDestination(it.value)) },
            onOpenGenre = entry.ifResumedWith { navController.navigate(GenreDestination(it)) },
            // The series of the book, on the stack its page is on.
            onOpenSeries = entry.ifResumedWith { navController.navigate(SeriesDestination(it.value)) },
            onRead = { bookId, fromStart -> read(ReaderDestination(bookId.value, fromStart)) },
            // A comic is read in the comics reader.
            onReadComic = { bookId, fromStart -> readComic(ComicsDestination(bookId.value, fromStart)) },
            onListen = { bookId, fromStart -> listen(bookId to fromStart) },
            // A book whose only narration is in its read-along EPUB is listened to in the reader.
            onNarrate = { bookId -> read(ReaderDestination(bookId.value, narrate = true)) },
            // From a page that could not be loaded for lack of network.
            onOpenDownloads = entry.ifResumed { navController.select(TopLevelDestination.Downloads) },
        )
    }
    composable<GenreDestination> { entry ->
        val genre = entry.toRoute<GenreDestination>().name
        BookListDetailScreen(
            title = genre,
            scope = BookListScope.genre(genre),
            onBack = entry.ifResumed { navController.popBackStack() },
            onOpenBook = entry.ifResumedWith { navController.navigate(BookDestination(it.value)) },
        )
    }
}

/**
 * Runs [action] only while this screen is the one in front: the second tap of a double tap
 * must not open a screen twice, nor go back twice.
 */
internal fun <T> NavBackStackEntry.ifResumedWith(action: (T) -> Unit): (T) -> Unit = { value ->
    if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) action(value)
}

/** The same guard for an action without argument. A separate name: a lambda cannot pick the overload. */
internal fun NavBackStackEntry.ifResumed(action: () -> Unit): () -> Unit = {
    if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) action()
}
