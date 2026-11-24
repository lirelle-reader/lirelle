package app.tfdn.lirelle.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.designsystem.LocalWindowWidthClass
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.Presentation
import app.tfdn.lirelle.core.library.model.BookSummary

/**
 * The margin a collection keeps on each side of its content. A header that goes from one edge
 * of the screen to the other, such as a row of chips that scrolls sideways, reaches out by as much.
 */
val CollectionEdgePadding = 16.dp

/**
 * Books as a grid or a list.
 *
 * Covers are not all the same shape: books are 2:3, audiobooks are square. Each row is laid
 * out on its own, so a row is as tall as its tallest cover, and covers of a row are aligned
 * at the bottom, like books standing on a shelf. The standard lazy grid aligns items at the
 * top of equal cells, which is why rows are composed here.
 *
 * @param showSeriesIndex whether each cover of the grid carries the volume number of its book: on
 *   the screen of a series. A list row names the volume on its series line instead.
 * @param header items placed before the books, scrolling with them.
 * @param footer shown after the books, for example a retry when a page failed.
 */
@Composable
fun BookCollection(
    items: BookItems,
    presentation: Presentation,
    onOpenBook: (BookId) -> Unit,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(CollectionEdgePadding),
    showSeriesIndex: Boolean = false,
    header: (LazyListScope.() -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
) {
    val widthClass = LocalWindowWidthClass.current
    val direction = LocalLayoutDirection.current
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val available = maxWidth - contentPadding.calculateStartPadding(direction) -
            contentPadding.calculateEndPadding(direction)
        val isGrid = presentation == Presentation.Grid
        val columns = if (isGrid) bookGridColumns(available, widthClass) else bookListColumns(widthClass)
        val spacing = gridSpacing(widthClass)
        // Every cell gets the same whole number of pixels, so covers of one shape are exactly the
        // same height; weights would round each cell differently and bottom-aligned covers would
        // not line up at the top. The few pixels left over go between the cells.
        val cellWidth = with(LocalDensity.current) {
            ((available.roundToPx() - spacing.roundToPx() * (columns - 1)) / columns).toDp()
        }
        LazyColumn(
            state = state,
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(if (isGrid) spacing else 4.dp),
        ) {
            header?.invoke(this)
            items(count = rowCount(items.count, columns), key = { "row-$it" }) { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom,
                ) {
                    repeat(columns) { column ->
                        val index = row * columns + column
                        Box(modifier = Modifier.width(cellWidth)) {
                            if (index < items.count) Cell(items[index], isGrid, showSeriesIndex, onOpenBook)
                        }
                    }
                }
            }
            if (footer != null) item(key = "footer") { footer() }
        }
    }
}

@Composable
private fun Cell(book: BookSummary?, isGrid: Boolean, showSeriesIndex: Boolean, onOpenBook: (BookId) -> Unit) {
    when {
        book == null -> BookPlaceholder()
        isGrid -> BookGridItem(book, onClick = { onOpenBook(book.id) }, showSeriesIndex = showSeriesIndex)
        else -> BookListRow(book, onClick = { onOpenBook(book.id) })
    }
}
