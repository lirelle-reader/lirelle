package app.tfdn.lirelle.feature.player

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The least the top keeps when the controls and the bottom row take the whole window: the column then scrolls. */
private val TopAtLeast = 96.dp

/**
 * [top], then [controls] under it, and [bottom] at the bottom of the column: the controls and the
 * bottom row keep their room whatever the window, the top takes what is left (it scrolls inside it
 * when taller), and the room to spare goes between the controls and the bottom row; with
 * [centreAbove], it goes above and below the top and the controls, centred in the room above the
 * bottom row; with [stacked], the bottom row follows the controls and the room to spare goes above
 * and below the three, which are centred. A window too low even for the controls and the bottom row
 * scrolls as a whole.
 */
@Composable
internal fun PinnedColumn(
    gap: Dp,
    modifier: Modifier = Modifier,
    stacked: Boolean = false,
    centreAbove: Boolean = false,
    top: @Composable () -> Unit,
    controls: @Composable () -> Unit,
    bottom: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier) {
        val window = constraints.maxHeight
        Layout(
            contents = listOf(top, controls, bottom),
            modifier = Modifier.verticalScroll(rememberScrollState()),
        ) { (topPart, controlsPart, bottomPart), constraints ->
            val width = constraints.maxWidth
            val space = gap.roundToPx()
            val loose = Constraints(maxWidth = width)
            val bottomPlaced = bottomPart.map { it.measure(loose) }
            val controlsPlaced = controlsPart.map { it.measure(loose) }
            val bottomHeight = bottomPlaced.maxOfOrNull { it.height } ?: 0
            val controlsHeight = controlsPlaced.maxOfOrNull { it.height } ?: 0
            val topRoom = maxOf(window - bottomHeight - controlsHeight - space * 2, TopAtLeast.roundToPx())
            val topPlaced = topPart.map { it.measure(Constraints(maxWidth = width, maxHeight = topRoom)) }
            val topHeight = topPlaced.maxOfOrNull { it.height } ?: 0
            val controlsY = topHeight + space
            val underControls = controlsY + controlsHeight + space
            val height = maxOf(window, underControls + bottomHeight)
            val bottomY = if (stacked) underControls else height - bottomHeight
            val shift = when {
                stacked -> (height - underControls - bottomHeight) / 2
                // The room above the bottom row, less the gap that keeps them apart.
                centreAbove -> ((height - bottomHeight - space) - (controlsY + controlsHeight)) / 2
                else -> 0
            }.coerceAtLeast(0)
            layout(width, height) {
                topPlaced.forEach { it.placeRelative(0, shift) }
                controlsPlaced.forEach { it.placeRelative(0, controlsY + shift) }
                bottomPlaced.forEach { it.placeRelative(0, if (stacked) bottomY + shift else bottomY) }
            }
        }
    }
}
