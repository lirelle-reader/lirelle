package app.tfdn.lirelle.core.designsystem

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow

/**
 * The label of a button or of a navigation item: one line, at the type size of its role
 * whatever the system text size.
 *
 * Such a slot does not grow with its text, so a label too long for it ends with an ellipsis
 * instead of wrapping or breaking in the middle of a word. Labels are never shrunk to fit:
 * the labels of a group keep the same size.
 */
@Composable
fun SingleLineLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
    )
}
