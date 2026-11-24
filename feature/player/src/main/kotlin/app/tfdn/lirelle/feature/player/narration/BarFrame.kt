package app.tfdn.lirelle.feature.player.narration

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private val HairlineHeight = 1.dp
private val ProgressHeight = 3.dp

/**
 * The frame of the narration's bars: the background, a hairline above, and the line of [progress] (from 0 to 1;
 * null for none) in the accent on the track.
 */
@Composable
internal fun BarFrame(
    colors: NarrationColors,
    progress: Float?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier.fillMaxWidth().background(colors.background)) {
        Box(Modifier.fillMaxWidth().height(HairlineHeight).background(colors.outline))
        progress?.let {
            Box(Modifier.fillMaxWidth().height(ProgressHeight).background(colors.track)) {
                Box(Modifier.fillMaxWidth(it.coerceIn(0f, 1f)).fillMaxHeight().background(colors.accent))
            }
        }
        content()
    }
}
