package app.tfdn.lirelle.feature.comics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp

/** Half the least height of a segment: the group and the chosen segment are pills. */
private val PillCorner = 24.dp
private val SegmentSidePadding = 8.dp
private val SegmentUprightPadding = 6.dp

/** A label side by side with the others takes two lines at most. */
private const val MaxLabelLines = 2

/** Slack for the rounding of the segments' widths to whole pixels. */
private const val RoundingSlack = 2

/**
 * The values of a setting, each with what the sheet calls it, as the segments of one pill, the chosen one
 * filled. A choice is taken at once.
 *
 * The segments stand side by side, each label whole on two lines at most; when a label does not fit so (a
 * narrow window, a large text size), they go one under the other, each as wide as the group: no label is ever
 * cut short or broken inside a word.
 */
@Composable
internal fun <T> SegmentedChoice(
    choices: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val style = MaterialTheme.typography.labelLarge.copy(textAlign = TextAlign.Center)
    val measurer = rememberTextMeasurer()
    val padding = with(LocalDensity.current) { SegmentSidePadding.roundToPx() }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val room = constraints.maxWidth / choices.size - 2 * padding - RoundingSlack
        val sideBySide = choices.all { (_, label) -> measurer.fitsWhole(label, style, room) }
        val group = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PillCorner))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .selectableGroup()
        if (sideBySide) {
            Row(group.height(IntrinsicSize.Min)) {
                choices.forEach { (value, label) ->
                    Segment(label, style, value == selected, Modifier.weight(1f).fillMaxHeight()) {
                        onSelect(value)
                    }
                }
            }
        } else {
            Column(group) {
                choices.forEach { (value, label) ->
                    Segment(label, style, value == selected, Modifier.fillMaxWidth()) {
                        onSelect(value)
                    }
                }
            }
        }
    }
}

/** One value: a radio button to a screen reader, as high as a touch target at least. */
@Composable
private fun Segment(label: String, style: TextStyle, selected: Boolean, modifier: Modifier, onSelect: () -> Unit) {
    val colours = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(PillCorner))
            .background(if (selected) colours.primary else Color.Transparent)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .heightIn(min = MinTarget)
            .padding(horizontal = SegmentSidePadding, vertical = SegmentUprightPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, color = if (selected) colours.onPrimary else colours.onSurface, style = style)
    }
}

/** Whether [label] is whole in [width] pixels: on two lines at most, broken between words only. */
private fun TextMeasurer.fitsWhole(label: String, style: TextStyle, width: Int): Boolean {
    if (width <= 0) return false
    val layout = measure(label, style, constraints = Constraints(maxWidth = width))
    val brokenInWord = (0 until layout.lineCount - 1).any { line ->
        val end = layout.getLineEnd(line)
        end in 1 until label.length && label[end - 1].isLetterOrDigit() && label[end].isLetterOrDigit()
    }
    return layout.lineCount <= MaxLabelLines && !layout.didOverflowWidth && !brokenInWord
}
