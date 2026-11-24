package app.tfdn.lirelle.feature.library.books

import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.designsystem.SingleLineLabel
import app.tfdn.lirelle.core.library.Presentation
import app.tfdn.lirelle.core.library.model.BookSort
import app.tfdn.lirelle.core.library.model.FormatFilter
import app.tfdn.lirelle.core.library.model.SortDirection
import app.tfdn.lirelle.core.library.model.StatusFilter
import app.tfdn.lirelle.core.ui.CollectionEdgePadding
import app.tfdn.lirelle.feature.library.R

@StringRes
internal fun BookSort.labelRes(): Int = when (this) {
    BookSort.Added -> R.string.sort_added
    BookSort.Title -> R.string.sort_title
    BookSort.Author -> R.string.sort_author
    BookSort.Series -> R.string.sort_series
    BookSort.Published -> R.string.sort_published
}

@StringRes
internal fun FormatFilter.labelRes(): Int = when (this) {
    FormatFilter.Epub -> R.string.format_epub
    FormatFilter.Pdf -> R.string.format_pdf
    FormatFilter.Comics -> R.string.format_comics
    FormatFilter.Audio -> R.string.format_audio
}

@StringRes
internal fun StatusFilter.labelRes(): Int = when (this) {
    StatusFilter.Unread -> R.string.status_unread
    StatusFilter.WantToRead -> R.string.status_want_to_read
    StatusFilter.Reading -> R.string.status_reading
    StatusFilter.Read -> R.string.status_read
    StatusFilter.Abandoned -> R.string.status_abandoned
}

internal const val FilterTickTag = "filter-tick"
internal const val ChipRowTag = "chip-row"
internal const val SortDirectionTag = "sort-direction"

/** Sort, filters, presentation switch and the number of books, above a book list. */
@Composable
fun SortFilterBar(
    state: BookListUiState,
    onSort: (BookSort) -> Unit,
    onToggleFormat: (FormatFilter) -> Unit,
    onToggleStatus: (StatusFilter) -> Unit,
    onReadAlongOnly: (Boolean) -> Unit,
    onPresentation: (Presentation) -> Unit,
    modifier: Modifier = Modifier,
    edgePadding: Dp = CollectionEdgePadding,
) {
    val filters = state.request.filters
    val choices = state.choices
    Column(modifier = modifier.fillMaxWidth()) {
        ChipRow(edgePadding) {
            SortChip(
                label = stringResource(state.request.sort.labelRes()),
                direction = state.request.direction,
                options = choices.sorts.map { it to stringResource(it.labelRes()) },
                selected = state.request.sort,
                onSelect = onSort,
            )
            // Only what the source offers: a filter it does not have is not shown at all.
            if (choices.formats.isNotEmpty()) {
                MultiChoiceChip(
                    label = stringResource(R.string.filter_format),
                    options = choices.formats.map { it to stringResource(it.labelRes()) },
                    selected = filters.formats,
                    onToggle = onToggleFormat,
                )
            }
            // A list about one status ("Want to read") always holds that status: there is nothing to choose.
            if (!state.statusLocked && choices.statuses.isNotEmpty()) {
                MultiChoiceChip(
                    label = stringResource(R.string.filter_status),
                    options = choices.statuses.map { it to stringResource(it.labelRes()) },
                    selected = filters.statuses,
                    onToggle = onToggleStatus,
                )
            }
            if (choices.readAlong) {
                TickedFilterChip(
                    label = stringResource(R.string.filter_read_along),
                    selected = filters.readAlongOnly,
                    onClick = { onReadAlongOnly(!filters.readAlongOnly) },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.weight(1f)) {
                state.total?.let { total ->
                    Text(
                        text = pluralStringResource(R.plurals.books_count, total, total),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            PresentationButton(state.presentation, onPresentation)
        }
    }
}

/**
 * A chip showing the current sort and its direction; its menu lists the sorts. It is a control
 * and always has a value, so it is never drawn as a filter that is on.
 */
@Composable
internal fun <T> SortChip(
    label: String,
    direction: SortDirection,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val ascending = direction == SortDirection.Ascending
    val description = stringResource(if (ascending) R.string.sort_ascending else R.string.sort_descending, label)
    Box {
        AssistChip(
            onClick = { expanded = true },
            label = { SingleLineLabel(label) },
            trailingIcon = {
                Icon(
                    if (ascending) Icons.Outlined.ArrowUpward else Icons.Outlined.ArrowDownward,
                    contentDescription = null,
                    modifier = Modifier
                        .size(AssistChipDefaults.IconSize)
                        .testTag(SortDirectionTag),
                )
            },
            modifier = Modifier.semantics { contentDescription = description },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (option, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                    trailingIcon = { if (option == selected) Icon(Icons.Outlined.Check, contentDescription = null) },
                )
            }
        }
    }
}

/** A chip whose menu lets several options be ticked; it is ticked itself when any is. */
@Composable
internal fun <T> MultiChoiceChip(
    label: String,
    options: List<Pair<T, String>>,
    selected: Set<T>,
    onToggle: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TickedFilterChip(label = label, selected = selected.isNotEmpty(), onClick = { expanded = true })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (option, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = { onToggle(option) },
                    trailingIcon = { if (option in selected) Icon(Icons.Outlined.Check, contentDescription = null) },
                )
            }
        }
    }
}

/**
 * Chips on one line that scrolls sideways and never wraps: each chip is as wide as its label.
 *
 * The line goes from one edge of the screen to the other, so a chip cut by an edge shows that
 * there is more to scroll to; at rest the first chip is in line with the content below.
 *
 * A sideways drag or fling that starts on the line ends there: what is left of it once the line
 * is at its end does not move what is around it, such as the pager of the Library's tabs.
 *
 * @param edgePadding the margin the parent keeps on each side of its content. The line reaches
 *   out of the parent by as much, and keeps as much before its first chip and after its last.
 */
@Composable
internal fun ChipRow(edgePadding: Dp, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = modifier
            .layout { measurable, constraints ->
                val edge = edgePadding.roundToPx()
                val placeable = measurable.measure(Constraints.fixedWidth(constraints.maxWidth + 2 * edge))
                layout(constraints.maxWidth, placeable.height) { placeable.place(-edge, 0) }
            }
            .testTag(ChipRowTag)
            .nestedScroll(KeepSidewaysScroll)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = edgePadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * Takes the sideways scroll and fling its child leaves, so that no parent gets them. The
 * up-and-down part goes on to the parents untouched.
 */
private object KeepSidewaysScroll : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        Offset(x = available.x, y = 0f)

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
        Velocity(x = available.x, y = 0f)
}

/**
 * A filter chip whose "on" state is a tick before its label, not only a colour. Its label is
 * on one line, whole: the chip is as wide as it takes, in a [ChipRow].
 */
@Composable
internal fun TickedFilterChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { SingleLineLabel(label) },
        modifier = modifier,
        leadingIcon = if (selected) {
            {
                Icon(
                    Icons.Outlined.Check,
                    contentDescription = null,
                    modifier = Modifier
                        .size(FilterChipDefaults.IconSize)
                        .testTag(FilterTickTag),
                )
            }
        } else {
            null
        },
    )
}

@Composable
internal fun PresentationButton(current: Presentation, onPresentation: (Presentation) -> Unit) {
    val other = if (current == Presentation.Grid) Presentation.List else Presentation.Grid
    val toList = other == Presentation.List
    IconButton(onClick = { onPresentation(other) }) {
        Icon(
            imageVector = if (toList) Icons.AutoMirrored.Outlined.ViewList else Icons.Outlined.GridView,
            contentDescription = stringResource(if (toList) R.string.view_as_list else R.string.view_as_grid),
        )
    }
}
