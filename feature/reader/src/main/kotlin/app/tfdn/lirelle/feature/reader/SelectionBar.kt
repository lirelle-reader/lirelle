package app.tfdn.lirelle.feature.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.designsystem.SingleLineLabel
import app.tfdn.lirelle.core.reader.marks.HighlightColour
import app.tfdn.lirelle.feature.reader.narration.ReadFromHereRow
import app.tfdn.lirelle.feature.reader.text.TextAction
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

internal const val SelectionBarTag = "reader-selection-bar"

/** Between the bar and the edges of the page. */
private val BarMargin = 8.dp
private val BarMaxWidth = 420.dp

/** Between the bar and the top of the selection when the bar is above it. */
private val GapAbove = 8.dp

/** Under the selection, where the device draws its handles: a bar below it starts lower. */
private val HandleRoom = 24.dp

private val ButtonPadding = 8.dp

/** One button of the bar, or one entry of "More". */
private class BarAction(val label: String, val onClick: () -> Unit)

private enum class Slot { Shown, More }

/**
 * The bar over a selection: "Read from here" when the file has a narration, the colour dots, then Note,
 * Translate, Define and Search, then "More". Without [annotations] the app's own actions go, the dots and
 * Note: every action of the device stays, as the search in the book does (spec 2c).
 *
 * Give it the page's own area, the one [SelectionUi.bounds] are fractions of: the bar is laid out in
 * it, above the selection, or below it, past the device's handles, when there is no room above.
 *
 * @param offered what the device offers, as [ReaderUiState.textActions].
 * @param current the colour to mark, `#RRGGBB`, or null: see [selectionColour].
 * @param onReadFromHere "Read from here", heading the card, when the file has a narration.
 * @param annotations false for a source without highlights nor notes.
 */
@Composable
internal fun SelectionBar(
    selection: SelectionUi,
    offered: List<TextAction>,
    current: String?,
    actions: SelectionActions,
    modifier: Modifier = Modifier,
    onReadFromHere: (() -> Unit)? = null,
    annotations: Boolean = true,
) {
    Layout(
        content = { BarCard(offered, current, actions, onReadFromHere, annotations) },
        modifier = modifier.fillMaxSize(),
    ) { measurables, constraints ->
        val margin = BarMargin.roundToPx()
        val width = (constraints.maxWidth - 2 * margin).coerceIn(0, BarMaxWidth.roundToPx())
        val bar = measurables.single().measure(
            Constraints.fixedWidth(width).copy(maxHeight = constraints.maxHeight),
        )
        val bounds = selection.bounds
        val top = barTop(
            pageHeight = constraints.maxHeight,
            barHeight = bar.height,
            selectionTop = floor(bounds.top * constraints.maxHeight).toInt(),
            selectionBottom = ceil(bounds.bottom * constraints.maxHeight).toInt(),
        )
        val centre = ((bounds.left + bounds.right) / 2 * constraints.maxWidth).roundToInt()
        val rightmost = (constraints.maxWidth - margin - bar.width).coerceAtLeast(0)
        val left = (centre - bar.width / 2).coerceIn(minOf(margin, rightmost), rightmost)
        layout(constraints.maxWidth, constraints.maxHeight) { bar.place(left, top) }
    }
}

/**
 * Where the bar's top goes: above the selection when the bar and its gap fit there, else below it
 * past the handles; when neither fits (a selection over most of the page), across the middle of the
 * selection, away from the handles at its two ends, and inside the page.
 */
private fun Density.barTop(pageHeight: Int, barHeight: Int, selectionTop: Int, selectionBottom: Int): Int {
    val above = selectionTop - GapAbove.roundToPx() - barHeight
    val below = selectionBottom + HandleRoom.roundToPx()
    return when {
        above >= 0 -> above
        below + barHeight <= pageHeight -> below
        else -> ((selectionTop + selectionBottom - barHeight) / 2).coerceIn(0, maxOf(pageHeight - barHeight, 0))
    }
}

/** The colour the bar marks: that of the exact highlight under the selection, the one a dot recolours; else none. */
internal fun ReaderUiState.selectionColour(selection: SelectionUi): String? =
    selection.exactId
        ?.let { id -> marks.highlights.firstOrNull { it.id == id } }
        ?.let { HighlightColour.display(it.color) }

@Composable
private fun BarCard(
    offered: List<TextAction>,
    current: String?,
    actions: SelectionActions,
    onReadFromHere: (() -> Unit)?,
    annotations: Boolean,
) {
    val translate = stringResource(R.string.reader_selection_translate)
    val define = stringResource(R.string.reader_selection_define)
    val note = stringResource(R.string.reader_selection_note)
    val buttons = buildList {
        if (annotations) add(BarAction(note, actions::noteOnSelection))
        if (TextAction.Translate in offered) add(BarAction(translate) { actions.selectionAction(TextAction.Translate) })
        if (TextAction.Define in offered) add(BarAction(define) { actions.selectionAction(TextAction.Define) })
        add(BarAction(stringResource(R.string.reader_selection_search), actions::searchSelection))
    }
    val menu = offered.mapNotNull { action ->
        val label = when (action) {
            TextAction.Copy -> stringResource(R.string.reader_selection_copy)
            TextAction.Share -> stringResource(R.string.reader_selection_share)
            TextAction.SearchWeb -> stringResource(R.string.reader_selection_search_web)
            is TextAction.App -> action.label
            TextAction.Translate, TextAction.Define -> null
        }
        label?.let { BarAction(it) { actions.selectionAction(action) } }
    }
    val pane = stringResource(R.string.reader_selection_pane)
    Surface(
        modifier = Modifier
            .testTag(SelectionBarTag)
            .semantics { paneTitle = pane },
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 6.dp,
    ) {
        Column(Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
            if (onReadFromHere != null) {
                ReadFromHereRow(MaterialTheme.colorScheme.primary, onReadFromHere)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            if (annotations) {
                ColourDots(current, actions::highlightSelection)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            BarButtons(buttons, menu)
        }
    }
}

/**
 * [buttons] in their order as long as they fit beside "More", whole: the first that does not fit
 * and those after it go to the top of "More", above [menu]. Each label is measured in the style it
 * is drawn in, at the text size of the system, so none is ever shortened.
 */
@Composable
private fun BarButtons(buttons: List<BarAction>, menu: List<BarAction>) {
    val measurer = rememberTextMeasurer()
    val style = LocalTextStyle.current.merge(MaterialTheme.typography.labelLarge)
    SubcomposeLayout(Modifier.fillMaxWidth()) { constraints ->
        val padding = ButtonPadding.roundToPx() * 2
        val more = MinTarget.roundToPx()
        val widths = buttons.map { button ->
            val label = measurer.measure(button.label, style, maxLines = 1, softWrap = false).size.width
            maxOf(label + padding, more)
        }
        val room = constraints.maxWidth - more
        val fitting = widths.runningReduce(Int::plus).takeWhile { it <= room }.size
        val shown = subcompose(Slot.Shown) {
            buttons.take(fitting).forEach { ActionButton(it, style) }
        }.map { it.measure(Constraints(maxWidth = room.coerceAtLeast(0))) }
        val moreButton = subcompose(Slot.More) { MoreButton(buttons.drop(fitting) + menu) }
            .single()
            .measure(Constraints.fixed(more, more))
        val height = maxOf(more, shown.maxOfOrNull { it.height } ?: 0)
        layout(constraints.maxWidth, height) {
            var x = 0
            shown.forEach { button ->
                button.placeRelative(x, (height - button.height) / 2)
                x += button.width
            }
            moreButton.placeRelative(constraints.maxWidth - more, (height - more) / 2)
        }
    }
}

@Composable
private fun ActionButton(action: BarAction, style: TextStyle) {
    Box(
        modifier = Modifier
            .heightIn(min = MinTarget)
            .widthIn(min = MinTarget)
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = action.onClick)
            .padding(horizontal = ButtonPadding),
        contentAlignment = Alignment.Center,
    ) {
        ProvideTextStyle(style) { SingleLineLabel(action.label) }
    }
}

/** "More" and its menu, whose entries wrap rather than being cut. Each entry closes the menu. */
@Composable
private fun MoreButton(entries: List<BarAction>) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(MinTarget)) {
            Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.reader_selection_more))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            entries.forEach { entry ->
                DropdownMenuItem(
                    text = { Text(entry.label) },
                    onClick = {
                        open = false
                        entry.onClick()
                    },
                )
            }
        }
    }
}
