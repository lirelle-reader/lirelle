package app.tfdn.lirelle.feature.book

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.designsystem.SingleLineLabel

// ---------- The round button of a reading action ----------

/** Whether the button is the filled one, the mode used last: for the tests, a screen reader is not told. */
internal val ActionRingFilledKey = SemanticsPropertyKey<Boolean>("ActionRingFilled")

/** How much of the ring is filled, from 0 to 1: for the tests, the status says it to a screen reader. */
internal val ActionRingFractionKey = SemanticsPropertyKey<Float>("ActionRingFraction")

/** The room around the disc and the texts that is part of the touch target. */
internal val ActionRingPadding = 4.dp

/** Between the disc and the texts beside it. */
internal val ActionRingBesideGap = 12.dp

private val UnderGap = 6.dp
private val MinTouchTarget = 48.dp

/** The space between two words, as a share of the size of their text. */
private const val WordGapEm = 0.28f

/** The style of the action's name. */
@Composable
internal fun ringNameStyle(): TextStyle = MaterialTheme.typography.titleSmall

/** The style of the status. */
@Composable
internal fun ringStatusStyle(): TextStyle = MaterialTheme.typography.bodySmall

/**
 * A reading action: a disc with its icon, the progress as a ring, its name and its status. The whole of it is
 * the button. A screen reader is given one sentence for it (see [spoken]), which stands for the texts.
 *
 * @param beside the texts are at the side of the disc, the form of a button alone on its line; else under it.
 */
@Composable
internal fun ActionRing(
    ui: ActionRingUi,
    tag: String,
    beside: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spoken = ui.spoken()
    val button = modifier
        .testTag(tag)
        .clip(MaterialTheme.shapes.medium)
        .clickable(enabled = ui.enabled, role = Role.Button, onClick = onClick)
        .semantics {
            contentDescription = spoken
            this[ActionRingFilledKey] = ui.filled
            this[ActionRingFractionKey] = ui.fraction
        }
        .defaultMinSize(minWidth = MinTouchTarget, minHeight = MinTouchTarget)
        .padding(ActionRingPadding)
    if (beside) {
        Row(
            modifier = button,
            horizontalArrangement = Arrangement.spacedBy(ActionRingBesideGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ActionDisc(ui)
            RingTexts(ui, Alignment.Start, Modifier.weight(1f, fill = false))
        }
    } else {
        Column(
            modifier = button,
            verticalArrangement = Arrangement.spacedBy(UnderGap),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ActionDisc(ui)
            RingTexts(ui, Alignment.CenterHorizontally)
        }
    }
}

/** The name, then the status when there is one. Those of a button that cannot be used are dimmed. */
@Composable
private fun RingTexts(ui: ActionRingUi, alignment: Alignment.Horizontal, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val off = scheme.onSurfaceVariant.copy(alpha = RingDisabledAlpha)
    Column(modifier = modifier, horizontalAlignment = alignment) {
        Words(ui.kind.label(), ringNameStyle(), if (ui.enabled) scheme.onSurface else off, alignment)
        ui.statusText()?.let { status ->
            Words(status, ringStatusStyle(), if (ui.enabled) scheme.onSurfaceVariant else off, alignment)
        }
    }
}

/**
 * A text of the button: each word is a text of one line, and the words that do not fit go on further lines, so
 * that a line only breaks between words. A no-break space is not a place to cut: "62 %" stays in one piece,
 * and the middle dot of a status stays at the end of the word before it, never at the start of a line.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Words(text: String, style: TextStyle, color: Color, alignment: Alignment.Horizontal) {
    val gap = with(LocalDensity.current) { (style.fontSize * WordGapEm).toDp() }
    ProvideTextStyle(style) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(gap, alignment)) {
            text.split(' ').forEach { word -> SingleLineLabel(word, color = color) }
        }
    }
}
