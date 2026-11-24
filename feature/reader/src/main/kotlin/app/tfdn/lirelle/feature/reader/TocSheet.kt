package app.tfdn.lirelle.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.feature.reader.bridge.TocEntry

/** How far one level of nesting pushes an entry, and how many levels do: deeper ones line up with the last. */
private val IndentStep = 12.dp
private const val MaxIndentLevels = 5

/** How many entries stay visible above the current one when the list opens. */
private const val EntriesAbove = 2

private val EntryMinHeight = 48.dp

private const val LabelLines = 2

/** The entries with their nesting, the current one marked; the list opens on it. */
@Composable
internal fun TocList(
    toc: List<TocEntry>,
    currentId: Int?,
    onChoose: (TocEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val current = toc.indexOfFirst { it.id == currentId }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (current - EntriesAbove).coerceAtLeast(0))
    LazyColumn(modifier = modifier.fillMaxWidth(), state = listState) {
        items(toc, key = TocEntry::id) { entry ->
            TocRow(entry, selected = entry.id == currentId, onClick = { onChoose(entry) })
        }
    }
}

/**
 * One entry. Its label takes two lines at most, then ends with an ellipsis: a book without titles
 * has the start of each chapter's text for labels. An entry that leads nowhere, which the page
 * gives with an empty address, is a heading among the others: it cannot be chosen.
 */
@Composable
private fun TocRow(entry: TocEntry, selected: Boolean, onClick: () -> Unit) {
    val indent = IndentStep * entry.depth.coerceIn(0, MaxIndentLevels)
    val colours = MaterialTheme.colorScheme
    val choice = if (entry.href.isEmpty()) Modifier else Modifier.selectable(selected = selected, onClick = onClick)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(choice)
            .background(if (selected) colours.secondaryContainer else Color.Transparent)
            .heightIn(min = EntryMinHeight)
            .padding(start = 24.dp + indent, end = 24.dp, top = 8.dp, bottom = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = entry.label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) colours.onSecondaryContainer else colours.onSurface,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = LabelLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
