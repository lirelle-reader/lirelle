package app.tfdn.lirelle.feature.player.narration

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.designsystem.SingleLineLabel
import app.tfdn.lirelle.feature.player.R

private val StripButtonHeight = 32.dp
private val StripElevation = 3.dp
private val StripIconSize = 16.dp
private val StripButtonPadding = PaddingValues(start = 10.dp, end = 14.dp)
private val IconGap = 6.dp

/**
 * The ways back to the narration, floating over the top of the page once the reader left the sentence heard: "Back
 * to the sentence" and, when [onReadPage] is given (paused, the sentence elsewhere), "Read this page". Over the
 * page rather than beside it, they come and go without the page being laid out again. The two sit side by side,
 * centred; where they do not fit, the second goes under the first, and a button without room for its icon leaves
 * it out: its label is never cut short.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NarrationStrip(
    colors: NarrationColors,
    onBack: () -> Unit,
    onReadPage: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    NarrationTheme(colors) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = onBack,
                    modifier = Modifier.heightIn(min = StripButtonHeight),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = StripElevation),
                    contentPadding = StripButtonPadding,
                ) {
                    IconLabel(Icons.AutoMirrored.Rounded.Undo, stringResource(R.string.narration_back_to_sentence))
                }
                onReadPage?.let {
                    // On the page's text: filled with the bar's background, to be read over it.
                    OutlinedButton(
                        onClick = it,
                        modifier = Modifier.heightIn(min = StripButtonHeight),
                        border = BorderStroke(1.dp, colors.outline),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = colors.background,
                            contentColor = colors.foreground,
                        ),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = StripElevation),
                        contentPadding = StripButtonPadding,
                    ) {
                        IconLabel(Icons.Rounded.PlayArrow, stringResource(R.string.narration_read_page))
                    }
                }
            }
        }
    }
}

/** The icon, then the label on one line; where both do not fit, the icon is left out rather than the label cut. */
@Composable
private fun IconLabel(icon: ImageVector, label: String) {
    Layout(
        content = {
            Icon(icon, contentDescription = null, Modifier.size(StripIconSize))
            SingleLineLabel(label)
        },
    ) { measurables, constraints ->
        val (iconMeasurable, labelMeasurable) = measurables
        val gap = IconGap.roundToPx()
        val iconPlaceable = iconMeasurable.measure(Constraints())
        val iconWidth = iconPlaceable.width + gap
        val withIcon = iconWidth + labelMeasurable.maxIntrinsicWidth(constraints.maxHeight) <= constraints.maxWidth
        val labelMax = if (withIcon) constraints.maxWidth - iconWidth else constraints.maxWidth
        val labelPlaceable = labelMeasurable.measure(constraints.copy(minWidth = 0, maxWidth = labelMax))
        val width = labelPlaceable.width + if (withIcon) iconWidth else 0
        val height = maxOf(labelPlaceable.height, if (withIcon) iconPlaceable.height else 0)
        layout(width.coerceAtLeast(constraints.minWidth), height.coerceAtLeast(constraints.minHeight)) {
            val start = (width.coerceAtLeast(constraints.minWidth) - width) / 2
            if (withIcon) iconPlaceable.place(start, (height - iconPlaceable.height) / 2)
            labelPlaceable.place(start + if (withIcon) iconWidth else 0, (height - labelPlaceable.height) / 2)
        }
    }
}
