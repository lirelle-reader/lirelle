package app.tfdn.lirelle.feature.search

import androidx.annotation.StringRes
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.model.BookSummary
import app.tfdn.lirelle.core.library.model.SearchResults
import app.tfdn.lirelle.core.library.model.SearchSection
import app.tfdn.lirelle.core.ui.BookGridItem
import app.tfdn.lirelle.core.ui.SectionHeading

/** How many rows a section shows before its "See all". */
private const val SectionRows = 5
private val GridCoverWidth = 120.dp
private val GridGap = 16.dp

/**
 * What was found, by section: books, series, authors. A section with nothing is left out.
 *
 * @param wide true for the books as a grid of covers next to the series and the authors; false
 *   for one column of rows. On a wide window a zone with nothing leaves its width to the other.
 * @param actions what a result and a "See all" open.
 */
@Composable
internal fun SearchResultsContent(
    results: SearchResults,
    wide: Boolean,
    actions: SearchActions,
    modifier: Modifier = Modifier,
) {
    if (wide) {
        val hasSide = results.series.items.isNotEmpty() || results.authors.items.isNotEmpty()
        Row(modifier = modifier.fillMaxSize()) {
            if (results.books.items.isNotEmpty()) {
                ScrollingZone(SearchMainTag, Modifier.weight(MainWeight), resultsScroll(results.text)) {
                    BookGrid(results.books, actions.onOpenBook) { actions.onSeeAllBooks(results.text) }
                }
            }
            if (hasSide) {
                ScrollingZone(SearchSideTag, Modifier.weight(SideWeight), resultsScroll(results.text)) {
                    SeriesAndAuthors(results, actions)
                }
            }
        }
    } else {
        ScrollingZone(SearchMainTag, modifier.fillMaxSize(), resultsScroll(results.text)) {
            Section(R.string.search_books, results.books, { actions.onSeeAllBooks(results.text) }) { book ->
                BookResultRow(book, onClick = { actions.onOpenBook(book.id) })
            }
            SeriesAndAuthors(results, actions)
        }
    }
}

/**
 * The scroll position of a list of results. It survives rotation and the way back from a
 * result, and returns to the top when the results are those of another text.
 */
@Composable
private fun resultsScroll(text: String): ScrollState {
    val scroll = rememberScrollState()
    var shownFor by rememberSaveable { mutableStateOf(text) }
    LaunchedEffect(text) {
        if (shownFor != text) {
            shownFor = text
            scroll.scrollTo(0)
        }
    }
    return scroll
}

@Composable
private fun SeriesAndAuthors(results: SearchResults, actions: SearchActions) {
    Section(R.string.search_series, results.series, { actions.onSeeAllSeries(results.text) }) { series ->
        SeriesResultRow(series, onClick = { actions.onOpenSeries(series.id) })
    }
    Section(R.string.search_authors, results.authors, { actions.onSeeAllAuthors(results.text) }) { author ->
        AuthorResultRow(author, onClick = { actions.onOpenAuthor(author.id) })
    }
}

/** The first rows of a section under its title, with "See all" when the server has more. */
@Composable
private fun <T> Section(
    @StringRes title: Int,
    section: SearchSection<T>,
    onSeeAll: () -> Unit,
    row: @Composable (T) -> Unit,
) {
    if (section.items.isEmpty()) return
    val visible = section.items.take(SectionRows)
    SectionHeading(
        title = stringResource(title),
        seeAllLabel = stringResource(R.string.search_see_all_count, section.total),
        onSeeAll = onSeeAll.takeIf { section.total > visible.size },
    )
    visible.forEach { row(it) }
}

/** Every book the search returned, as covers. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BookGrid(books: SearchSection<BookSummary>, onOpenBook: (BookId) -> Unit, onSeeAll: () -> Unit) {
    SectionHeading(
        title = stringResource(R.string.search_books),
        seeAllLabel = stringResource(R.string.search_see_all_count, books.total),
        onSeeAll = onSeeAll.takeIf { books.total > books.items.size },
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(GridGap),
        verticalArrangement = Arrangement.spacedBy(GridGap),
    ) {
        books.items.forEach { book ->
            BookGridItem(book, onClick = { onOpenBook(book.id) }, modifier = Modifier.width(GridCoverWidth))
        }
    }
}
