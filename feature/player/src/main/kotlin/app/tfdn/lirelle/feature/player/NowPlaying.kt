package app.tfdn.lirelle.feature.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Where the chapter's bar is: the chapter, the time played in it, its length, and the speed its time left is at. */
internal data class BarPlace(val chapterIndex: Int, val elapsedMs: Long, val durationMs: Long?, val speed: Float)

/**
 * What the five controls show: play or pause, waiting, a finished book, the skips' intervals, and whether the
 * source takes [ratings].
 */
internal data class Transport(
    val playing: Boolean,
    val buffering: Boolean,
    val finished: Boolean,
    val backSeconds: Int,
    val forwardSeconds: Int,
    val ratings: Boolean = true,
)


/**
 * The title and the credits, each on one line that ends with an ellipsis when too long (spec 5.1),
 * and the chapter's title in the accent colour, on the lines it needs.
 */
@Composable
internal fun NowPlayingTexts(title: String, credits: String?, chapterTitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        credits?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (chapterTitle.isNotEmpty()) {
            Text(
                text = chapterTitle,
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** The chapter's bar, the book's [line] ("Finished" once it is), and the room of "Go back" when [goBackHere]. */
@Composable
internal fun PlayingDetails(
    bar: BarPlace,
    line: String?,
    finished: Boolean,
    goBackShown: Boolean,
    goBackHere: Boolean,
    actions: PlayerActions,
    centred: Boolean,
) {
    val align = if (centred) Alignment.CenterHorizontally else Alignment.Start
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = align) {
        ChapterBar(bar, actions::seekInChapter)
        BookLine(if (finished) stringResource(R.string.player_finished) else line, finished, centred)
        if (goBackHere) {
            // Always laid out, unseen and out of reach while not offered: its room never changes.
            GoBackButton(
                onGoBack = actions::goBack,
                modifier = if (goBackShown) Modifier else Modifier.alpha(0f).clearAndSetSemantics {},
                enabled = goBackShown,
            )
        }
    }
}

@Composable
private fun BookLine(text: String?, finished: Boolean, centred: Boolean) {
    val colours = MaterialTheme.colorScheme
    text?.let {
        Text(
            text = it,
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodySmall,
            color = if (finished) colours.primary else colours.onSurfaceVariant,
            textAlign = if (centred) TextAlign.Center else TextAlign.Start,
        )
    }
}
