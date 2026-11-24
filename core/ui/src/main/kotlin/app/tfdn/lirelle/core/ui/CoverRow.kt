package app.tfdn.lirelle.core.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.designsystem.LocalWindowWidthClass
import app.tfdn.lirelle.core.designsystem.WindowWidthClass
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.model.BookSummary

private val PhoneCoverWidth = 104.dp
private val WideCoverWidth = 120.dp

/**
 * A titled row of covers that scrolls sideways.
 *
 * A plain row, not a lazy one: a row holds a few books, and what a plain row hides at its sides
 * is whole once scrolled to.
 *
 * @param books the books of the row; null stands for one that is not loaded yet.
 * @param onSeeAll what "See all" does; null for a row without that button.
 * @param showSeries true for a row about series: each cover carries its volume number, and the
 *   line under the title names the series instead of the author.
 */
@Composable
fun CoverRow(
    title: String,
    books: List<BookSummary?>,
    onOpenBook: (BookId) -> Unit,
    modifier: Modifier = Modifier,
    onSeeAll: (() -> Unit)? = null,
    showSeries: Boolean = false,
) {
    val widthClass = LocalWindowWidthClass.current
    val coverWidth = if (widthClass == WindowWidthClass.Expanded) WideCoverWidth else PhoneCoverWidth
    Column(modifier = modifier.fillMaxWidth()) {
        SectionHeading(title, Modifier.padding(horizontal = CollectionEdgePadding), onSeeAll = onSeeAll)
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = CollectionEdgePadding),
            horizontalArrangement = Arrangement.spacedBy(gridSpacing(widthClass)),
            verticalAlignment = Alignment.Bottom,
        ) {
            books.forEach { book ->
                Box(modifier = Modifier.width(coverWidth)) {
                    if (book == null) {
                        BookPlaceholder()
                    } else {
                        BookGridItem(
                            book = book,
                            onClick = { onOpenBook(book.id) },
                            showSeriesIndex = showSeries,
                            secondLine = book.series?.name?.takeIf { showSeries } ?: book.authors.joinToString(", "),
                        )
                    }
                }
            }
        }
    }
}
