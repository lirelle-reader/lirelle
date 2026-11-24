package app.tfdn.lirelle.feature.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private val ListPaneWidth = 340.dp

/**
 * The layout of a wide window: a list in a pane of fixed width at the start, a line, and what was chosen from
 * the list in the rest of the width.
 *
 * Each pane keeps clear of the system's bars and cutouts on its own sides, so what it holds need not: the
 * list at the start, the detail at the end, both at the top and the bottom.
 */
@Composable
internal fun TwoPanes(
    list: @Composable () -> Unit,
    detail: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listSides = WindowInsetsSides.Start + WindowInsetsSides.Vertical
    val detailSides = WindowInsetsSides.End + WindowInsetsSides.Vertical
    Row(modifier.fillMaxSize()) {
        Box(
            Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing.only(listSides))
                .width(ListPaneWidth)
                .fillMaxHeight(),
        ) { list() }
        VerticalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(detailSides)),
        ) { detail() }
    }
}
