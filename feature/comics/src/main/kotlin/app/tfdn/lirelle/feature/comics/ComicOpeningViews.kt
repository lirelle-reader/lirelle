package app.tfdn.lirelle.feature.comics

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.comics.OpeningProgress
import app.tfdn.lirelle.core.designsystem.SingleLineLabel
import app.tfdn.lirelle.core.ui.canRetry
import app.tfdn.lirelle.core.ui.fileSize
import app.tfdn.lirelle.core.ui.libraryMessage

/** The mark of an album that is being opened. */
const val ComicOpeningTag = "comic-opening"

/** The mark of a document that is being fetched whole before it opens. */
const val ComicDownloadTag = "comic-download"

private val FailureWidth = 420.dp

/** How much of the ink the part of the mark not reached yet has. */
private const val TrackAlpha = 0.24f

/**
 * The album is being opened: a progress mark in the middle of the reader's background. A document fetched whole
 * first says how far that is ([fetch]).
 *
 * @param ink the colour of what is drawn on that background.
 */
@Composable
internal fun ComicLoading(ink: Color, modifier: Modifier = Modifier, fetch: OpeningProgress? = null) {
    if (fetch == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(40.dp).testTag(ComicOpeningTag), color = ink, strokeWidth = 3.dp)
        }
    } else {
        Centred(modifier) { ComicDownloading(fetch, ink) }
    }
}

/**
 * How far the fetch of the document is, as the book reader says it of a book: a mark that fills as it comes and
 * "3.2 MB of 9.4 MB" under it; when the server did not say the size, a mark that only moves and what came.
 */
@Composable
private fun ComicDownloading(fetch: OpeningProgress, ink: Color) {
    val label = stringResource(R.string.comics_downloading)
    val total = fetch.totalBytes?.takeIf { it > 0 }
    val received = fileSize(fetch.receivedBytes)
    val sizes = when (total) {
        null -> received
        else -> stringResource(R.string.comics_download_size, received, fileSize(total))
    }
    val mark = Modifier.fillMaxWidth().testTag(ComicDownloadTag).semantics { contentDescription = label }
    val track = ink.copy(alpha = TrackAlpha)
    if (total != null) {
        LinearProgressIndicator(
            progress = { (fetch.receivedBytes.toFloat() / total).coerceIn(0f, 1f) },
            modifier = mark,
            color = ink,
            trackColor = track,
        )
    } else {
        LinearProgressIndicator(modifier = mark, color = ink, trackColor = track)
    }
    Text(
        text = sizes,
        color = ink,
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodyMedium,
    )
}

/** A column in the middle of what is under the bar, clear of the window's edges; it scrolls when short of room. */
@Composable
private fun Centred(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val sides = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
    Box(
        modifier.fillMaxSize().windowInsetsPadding(sides).verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.widthIn(max = FailureWidth).padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content,
        )
    }
}

/**
 * The album could not be opened: why, as the app says it elsewhere, and "Try again" unless trying again cannot
 * help (a book that is gone, a document that asks for a password, a damaged copy the device keeps). It scrolls
 * when short of room.
 *
 * @param ink the colour of what is drawn on the reader's background.
 */
@Composable
internal fun ComicOpeningFailed(
    reason: ComicFailure,
    ink: Color,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Centred(modifier) {
        Text(
            text = reason.message(),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            color = ink,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge,
        )
        if (reason.canRetry) {
            OutlinedButton(
                onClick = onRetry,
                modifier = Modifier.heightIn(min = MinTarget),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = ink),
                border = BorderStroke(1.dp, ink),
            ) {
                SingleLineLabel(stringResource(R.string.comics_page_retry))
            }
        }
    }
}

@Composable
private fun ComicFailure.message(): String = when (this) {
    is ComicFailure.Load -> error.libraryMessage()
    ComicFailure.NoComic -> stringResource(R.string.comics_no_comic)
    ComicFailure.Protected -> stringResource(R.string.comics_protected)
    ComicFailure.Damaged, ComicFailure.KeptDamaged -> stringResource(R.string.comics_damaged)
}

/**
 * A comic file may have been added to the book since, and a damaged document is fetched anew; a book that is
 * gone stays gone, a password is not something a new try gives, and a damaged copy the device keeps would be
 * opened again as it is.
 */
private val ComicFailure.canRetry: Boolean
    get() = when (this) {
        is ComicFailure.Load -> error.canRetry
        ComicFailure.NoComic, ComicFailure.Damaged -> true
        ComicFailure.Protected, ComicFailure.KeptDamaged -> false
    }
