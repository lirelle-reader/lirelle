package app.tfdn.lirelle.feature.comics.end

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.designsystem.WordsLabel
import app.tfdn.lirelle.core.library.model.NextVolume
import app.tfdn.lirelle.feature.comics.R

/** The room between the title and the button beside it, and between the title and the button under it. */
private val Beside = 16.dp
private val Under = 8.dp

/** The least width worth giving the title beside the button: narrower, the button goes under it. */
private val TitleMinWidth = 120.dp

/**
 * The series' next album: "Next volume", its title on one line, cut short when it is long, and "Read the next
 * one". The button stands beside the title when its label fits there on one line, and under it otherwise, as on
 * a narrow window at a large text size; its label is whole either way. A screen reader is given it whole.
 */
@Composable
internal fun NextAlbumRow(next: NextVolume, enabled: Boolean, onRead: () -> Unit) {
    Layout(
        content = {
            Column {
                Text(
                    text = stringResource(R.string.comics_end_next_volume),
                    style = MaterialTheme.typography.labelLarge,
                )
                next.title?.let {
                    Text(
                        text = it,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
            val label = stringResource(R.string.comics_end_read_next)
            FilledTonalButton(
                onClick = onRead,
                modifier = Modifier
                    .heightIn(min = TouchTarget)
                    .semantics { contentDescription = label },
                enabled = enabled,
            ) {
                WordsLabel(label)
            }
        },
        measurePolicy = BesideOrUnder,
    )
}

/** Two children, the texts then the button, in the width given: side by side, or one under the other. */
private val BesideOrUnder = MeasurePolicy { measurables, constraints ->
    val (texts, button) = measurables
    val loose = constraints.copy(minWidth = 0, minHeight = 0)
    // The width of the button with its label on one line.
    val wanted = button.maxIntrinsicWidth(constraints.maxHeight)
    val left = constraints.maxWidth - wanted - Beside.roundToPx()
    if (left >= TitleMinWidth.roundToPx()) beside(texts, button, loose, left) else under(texts, button, loose)
}

private fun MeasureScope.beside(texts: Measurable, button: Measurable, loose: Constraints, left: Int): MeasureResult {
    val title = texts.measure(loose.copy(maxWidth = left))
    val action = button.measure(loose.copy(maxWidth = loose.maxWidth - left - Beside.roundToPx()))
    val height = maxOf(title.height, action.height)
    return layout(loose.maxWidth, height) {
        title.placeRelative(0, (height - title.height) / 2)
        action.placeRelative(loose.maxWidth - action.width, (height - action.height) / 2)
    }
}

private fun MeasureScope.under(texts: Measurable, button: Measurable, loose: Constraints): MeasureResult {
    val title = texts.measure(loose)
    val action = button.measure(loose)
    val gap = Under.roundToPx()
    return layout(loose.maxWidth, title.height + gap + action.height) {
        title.placeRelative(0, 0)
        action.placeRelative(loose.maxWidth - action.width, title.height + gap)
    }
}
