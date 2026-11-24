package app.tfdn.lirelle.feature.book

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ---------- The line of the reading actions' buttons ----------

private val RingsGap = 8.dp

/** A button of the line: what it shows, its test tag and what a tap on it does. */
internal class RingAction(val ui: ActionRingUi, val tag: String, val onClick: () -> Unit)

/**
 * The buttons of the reading actions. Two of them sit side by side in two equal columns, each centred in its
 * own, its texts under its disc. Where a word of a name or of a status cannot be whole in a column, a narrow
 * window with large text, they stack instead, each with its texts beside its disc: a word is never cut. A
 * button alone has that form too, in the middle of the line.
 */
@Composable
internal fun ActionRingsRow(actions: List<RingAction>, modifier: Modifier = Modifier) {
    if (actions.isEmpty()) return
    val widestWord = widestWord(actions.map { it.ui })
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val column = (maxWidth - RingsGap * (actions.size - 1)) / actions.size - ActionRingPadding * 2
        if (actions.size > 1 && widestWord <= column) {
            Row(horizontalArrangement = Arrangement.spacedBy(RingsGap)) {
                actions.forEach { action ->
                    Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
                        ActionRing(action.ui, action.tag, beside = false, onClick = action.onClick)
                    }
                }
            }
        } else {
            // As wide as the widest of them, in the middle of the line: the discs are one above the other.
            Column(verticalArrangement = Arrangement.spacedBy(RingsGap)) {
                actions.forEach { action ->
                    ActionRing(action.ui, action.tag, beside = true, onClick = action.onClick)
                }
            }
        }
    }
}

/** The room the longest word of the buttons' names and statuses takes, at the current text size. */
@Composable
private fun widestWord(rings: List<ActionRingUi>): Dp {
    val texts = rings.flatMap { ring ->
        listOfNotNull(ring.kind.label() to ringNameStyle(), ring.statusText()?.let { it to ringStatusStyle() })
    }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(texts, density) {
        val widest = texts.maxOf { (text, style) ->
            text.split(' ').maxOf { measurer.measure(it, style, softWrap = false, maxLines = 1).size.width }
        }
        // One pixel more: the width was rounded.
        with(density) { (widest + 1).toDp() }
    }
}
