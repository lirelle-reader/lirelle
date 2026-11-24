package app.tfdn.lirelle.feature.player.narration

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.designsystem.SingleLineLabel
import app.tfdn.lirelle.feature.player.R

private val EndedRowMinHeight = 60.dp

/** Narrower than this beside the button, the texts go above it: no word is cut in two. */
private val EndedTextsMinWidth = 160.dp
private val WaveSize = 16.dp
private val TouchTarget = 48.dp

/**
 * The narration's bar once the narration ended with pages left: the same frame, the wave muted, "Narration
 * finished" and "The rest of the book has no narration" (one line, cut short), and "Finish the book"
 * ([onFinishBook]). Where the texts have no room beside the button, the button goes under them. Like the bar, it
 * does not keep clear of the navigation bar.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NarrationEndedBar(colors: NarrationColors, onFinishBook: () -> Unit, modifier: Modifier = Modifier) {
    NarrationTheme(colors) {
        BarFrame(colors, progress = 1f, modifier) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = EndedRowMinHeight)
                    .padding(start = 16.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    EndedTexts(colors, Modifier.weight(1f).widthIn(min = EndedTextsMinWidth))
                    Button(onClick = onFinishBook, modifier = Modifier.heightIn(min = TouchTarget)) {
                        SingleLineLabel(stringResource(R.string.narration_finish_book))
                    }
                }
            }
        }
    }
}

@Composable
private fun EndedTexts(colors: NarrationColors, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.GraphicEq, contentDescription = null, Modifier.size(WaveSize), tint = colors.muted)
            Text(
                text = stringResource(R.string.narration_finished),
                style = MaterialTheme.typography.titleSmall,
                color = colors.foreground,
            )
        }
        Text(
            text = stringResource(R.string.narration_finished_rest),
            style = MaterialTheme.typography.bodySmall,
            color = colors.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
