package app.tfdn.lirelle.feature.comics.end

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.designsystem.SingleLineLabel
import app.tfdn.lirelle.core.designsystem.WordsLabel
import app.tfdn.lirelle.core.library.model.PagedKind
import app.tfdn.lirelle.core.ui.GripSheet
import app.tfdn.lirelle.feature.comics.ComicEndActions
import app.tfdn.lirelle.feature.comics.ComicEndPanel
import app.tfdn.lirelle.feature.comics.R

private const val StarCount = 5

/** On a wide window the sheet's content stays a column in its middle. */
private val SheetMaxWidth = 640.dp

/** The smallest side of anything that takes a tap. */
internal val TouchTarget = 48.dp
private val StarSize = 32.dp
private val ProgressSize = 24.dp
private val ProgressStroke = 3.dp

/**
 * What was read to its end.
 *
 * @property kind what it is called by: an album for a comic, a book for a PDF or a DjVu.
 */
internal class EndedBook(val title: String, val kind: PagedKind)

/** "You finished this album", or "this book" for what is not a comic. */
private val PagedKind.finishedRes: Int
    @StringRes get() = if (this == PagedKind.Comic) R.string.comics_end_finished else R.string.comics_end_finished_book

/** "Close the album", or "the book". */
private val PagedKind.closeRes: Int
    @StringRes get() = if (this == PagedKind.Comic) R.string.comics_end_close else R.string.comics_end_close_book

/**
 * The end of an album: a sheet over the bottom of its last page, which is dimmed behind it, in the app's
 * theme. It says the album is finished, names it, takes an optional rating (none from a source without ratings,
 * spec 2c), offers the series' next album
 * and closes the album. A PDF and a DjVu are called a book there, where a comic is an album; a book the device
 * keeps has the row of what it keeps, to delete it from there. Its grip, pulled
 * down or asked by a screen reader, a tap above it and back dismiss it. Show it while the state has a panel.
 */
@Composable
internal fun ComicEndSheet(panel: ComicEndPanel, book: EndedBook, actions: ComicEndActions) {
    GripSheet(
        onDismissRequest = actions::dismissEndPanel,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        ComicEndContent(
            panel = panel,
            book = book,
            actions = actions,
            // The sheet is never taller than the window: its content scrolls in what is left under the grip.
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .widthIn(max = SheetMaxWidth)
                .weight(1f, fill = false),
        )
    }
}

/** What the sheet holds. It scrolls when a large text size makes it taller than the screen. */
@Composable
private fun ComicEndContent(panel: ComicEndPanel, book: EndedBook, actions: ComicEndActions, modifier: Modifier) {
    val enabled = !panel.sending
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 24.dp, end = 24.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(book.kind.finishedRes),
            modifier = Modifier.semantics { heading() },
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelLarge,
        )
        Text(text = book.title, style = MaterialTheme.typography.titleLarge)
        if (panel.ratings) {
            Text(text = stringResource(R.string.comics_end_rating), style = MaterialTheme.typography.bodyMedium)
            Stars(rating = panel.rating, enabled = enabled, onRate = actions::setRating)
        }
        if (panel.failed) RatingFailed(enabled, actions::retryRating)
        panel.nextVolume?.let { NextAlbumRow(it, enabled, actions::readNext) }
        panel.device?.let { DeviceRow(it, enabled, actions) }
        ClosingRow(stringResource(book.kind.closeRes), sending = panel.sending, onClose = actions::closeAlbum)
    }
}

/** Five stars; the one chosen is the last one filled. A tap on it takes the rating away. */
@Composable
private fun Stars(rating: Int?, enabled: Boolean, onRate: (Int?) -> Unit) {
    Row {
        for (stars in 1..StarCount) {
            val chosen = rating == stars
            val filled = rating != null && stars <= rating
            IconButton(
                onClick = { onRate(if (chosen) null else stars) },
                modifier = Modifier
                    .size(TouchTarget)
                    .semantics { selected = chosen },
                enabled = enabled,
            ) {
                Icon(
                    imageVector = if (filled) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                    contentDescription = pluralStringResource(R.plurals.comics_end_stars, stars, stars),
                    modifier = Modifier.size(StarSize),
                    // While the buttons wait, the stars are dimmed with them.
                    tint = if (filled && enabled) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                )
            }
        }
    }
}

/** The rating could not be sent: the message, told to a screen reader as it appears, and "Try again". */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RatingFailed(enabled: Boolean, onRetry: () -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.comics_end_rating_failed),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
        TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = TouchTarget), enabled = enabled) {
            SingleLineLabel(stringResource(R.string.comics_end_retry))
        }
    }
}

/**
 * "Close the album" (the [label]), which also sends the rating, and what shows that it is on its way. The label
 * is never cut short: the words that do not fit beside the others go on further lines; a screen reader is given
 * it whole.
 */
@Composable
private fun ClosingRow(label: String, sending: Boolean, onClose: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (sending) {
            val name = stringResource(R.string.comics_end_sending)
            CircularProgressIndicator(
                modifier = Modifier
                    .size(ProgressSize)
                    .semantics { contentDescription = name },
                strokeWidth = ProgressStroke,
            )
        }
        Button(
            onClick = onClose,
            modifier = Modifier
                // The progress beside it keeps its place: the button takes what is left, at most.
                .weight(1f, fill = false)
                .heightIn(min = TouchTarget)
                .semantics { contentDescription = label },
            enabled = !sending,
        ) {
            WordsLabel(label)
        }
    }
}
