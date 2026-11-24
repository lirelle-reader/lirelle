package app.tfdn.lirelle.core.designsystem

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow

/**
 * The label of a button that stands alone and may grow: a second line, centred, when one is too short for it
 * at a large text size, instead of the ellipsis of a [SingleLineLabel].
 *
 * For a button whose label is the only way to know what it does, where nothing beside it has to keep its height.
 */
@Composable
fun WrappingLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        textAlign = TextAlign.Center,
        maxLines = WrappingLabelLines,
        overflow = TextOverflow.Ellipsis,
    )
}

private const val WrappingLabelLines = 2
