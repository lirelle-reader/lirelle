package app.tfdn.lirelle.navigation

import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import app.tfdn.lirelle.feature.library.books.BookListDetailScreen
import app.tfdn.lirelle.feature.library.books.BookListScope
import app.tfdn.lirelle.feature.search.AuthorResultsScreen
import app.tfdn.lirelle.feature.search.SearchActions
import app.tfdn.lirelle.feature.search.SearchRoute
import app.tfdn.lirelle.feature.search.SeriesResultsScreen
import app.tfdn.lirelle.feature.search.R as SearchR

/**
 * Search, the full list behind each kind of result, and the detail screens opened from them.
 *
 * @param modes open the reader or the player above the shell: on a book, where it was left or from its start.
 */
fun NavGraphBuilder.searchDestinations(navController: NavHostController, modes: BookModes) {
    composable<SearchDestination> { entry ->
        SearchRoute(
            actions = SearchActions(
                onOpenBook = entry.ifResumedWith { navController.navigate(BookDestination(it.value)) },
                onOpenSeries = entry.ifResumedWith { navController.navigate(SeriesDestination(it.value)) },
                onOpenAuthor = entry.ifResumedWith { navController.navigate(AuthorDestination(it.value)) },
                onOpenGenre = entry.ifResumedWith { navController.navigate(GenreDestination(it)) },
                onSeeAllBooks = entry.ifResumedWith { navController.navigate(SearchBooksDestination(it)) },
                onSeeAllSeries = entry.ifResumedWith { navController.navigate(SearchSeriesDestination(it)) },
                onSeeAllAuthors = entry.ifResumedWith { navController.navigate(SearchAuthorsDestination(it)) },
            ),
        )
    }
    composable<SearchBooksDestination> { entry ->
        val text = entry.toRoute<SearchBooksDestination>().text
        BookListDetailScreen(
            title = stringResource(SearchR.string.search_books_title, text),
            scope = BookListScope.search(text),
            onBack = entry.ifResumed { navController.popBackStack() },
            onOpenBook = entry.ifResumedWith { navController.navigate(BookDestination(it.value)) },
        )
    }
    composable<SearchSeriesDestination> { entry ->
        SeriesResultsScreen(
            text = entry.toRoute<SearchSeriesDestination>().text,
            onBack = entry.ifResumed { navController.popBackStack() },
            onOpenSeries = entry.ifResumedWith { navController.navigate(SeriesDestination(it.value)) },
        )
    }
    composable<SearchAuthorsDestination> { entry ->
        AuthorResultsScreen(
            text = entry.toRoute<SearchAuthorsDestination>().text,
            onBack = entry.ifResumed { navController.popBackStack() },
            onOpenAuthor = entry.ifResumedWith { navController.navigate(AuthorDestination(it.value)) },
        )
    }
    detailDestinations(navController, modes)
}
