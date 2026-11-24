package app.tfdn.lirelle.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp

/**
 * The title of a top-level screen, a heading, with an action at its end — the profile button.
 *
 * The row is as high as the title alone, whether there is an action or not: a screen must not move by a few
 * pixels when a second profile appears. An action taller than the title — a 48 dp button beside a 36 dp line —
 * is centred on it and hangs out of the row, above and below, into the space the screen leaves around its
 * title. The title takes the width the action leaves, and wraps there.
 *
 * @param action what stands at the end of the row; nothing takes no room.
 * @param roomBelow how far the action may hang below the row before it would cover what comes next: past
 *   that, it is moved up rather than centred. Unspecified when the screen leaves enough room.
 */
@Composable
fun ScreenTitleRow(
    title: String,
    modifier: Modifier = Modifier,
    roomBelow: Dp = Dp.Unspecified,
    action: @Composable () -> Unit = {},
) {
    Layout(
        content = {
            Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium)
            Box { action() }
        },
        modifier = modifier,
    ) { (titleMeasurable, actionMeasurable), constraints ->
        val action = actionMeasurable.measure(Constraints(maxWidth = constraints.maxWidth))
        val title = titleMeasurable.measure(
            Constraints(maxWidth = (constraints.maxWidth - action.width).coerceAtLeast(0)),
        )
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else title.width + action.width
        val centred = (title.height - action.height) / 2
        val lowest = if (roomBelow == Dp.Unspecified) centred else title.height + roomBelow.roundToPx() - action.height
        layout(width, title.height) {
            title.placeRelative(0, 0)
            action.placeRelative(width - action.width, minOf(centred, lowest))
        }
    }
}
