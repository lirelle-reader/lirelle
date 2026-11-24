package app.tfdn.lirelle.feature.reader

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.designsystem.SingleLineLabel
import app.tfdn.lirelle.core.library.model.CoverShape
import app.tfdn.lirelle.core.library.model.DownloadProgress
import app.tfdn.lirelle.core.ui.BookCover
import app.tfdn.lirelle.core.ui.canRetry
import app.tfdn.lirelle.core.ui.libraryMessage
import app.tfdn.lirelle.core.ui.R as UiR

internal const val DownloadProgressTag = "reader-download-progress"
internal const val ReaderOpeningTag = "reader-opening"

private val CoverWidth = 160.dp
private val OpeningWidth = 420.dp

/** A column in the middle of the screen, which scrolls when the text is too large for it. */
@Composable
private fun CentredColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = OpeningWidth)
                .padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content,
        )
    }
}

/** The book is being fetched: its cover, its title, how far the download is, and a way out. */
@Composable
internal fun DownloadingView(
    title: String,
    coverUrl: String?,
    progress: DownloadProgress?,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(R.string.reader_downloading)
    val total = progress?.totalBytes?.takeIf { it > 0 }
    CentredColumn(modifier) {
        BookCover(coverUrl, title, CoverShape.Portrait, Modifier.width(CoverWidth), showTitleWhenMissing = false)
        Text(
            text = title,
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        val bar = Modifier
            .fillMaxWidth()
            .testTag(DownloadProgressTag)
            .semantics { contentDescription = label }
        if (progress != null && total != null) {
            LinearProgressIndicator(
                progress = { (progress.bytesRead.toFloat() / total).coerceIn(0f, 1f) },
                modifier = bar,
            )
        } else {
            LinearProgressIndicator(modifier = bar)
        }
        Text(
            text = progress?.sizeText() ?: label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        OutlinedButton(onClick = onCancel) { SingleLineLabel(stringResource(R.string.reader_cancel)) }
    }
}

/** "1.2 MB of 4.5 MB", or what was received alone while the server has not said the size. */
@Composable
private fun DownloadProgress.sizeText(): String {
    val context = LocalContext.current
    val read = Formatter.formatShortFileSize(context, bytesRead)
    val total = totalBytes?.takeIf { it > 0 } ?: return read
    return stringResource(R.string.reader_download_size, read, Formatter.formatShortFileSize(context, total))
}

/** The book cannot be shown: why, "Try again" where that can help, and "Back" always. */
@Composable
internal fun FailureView(
    reason: ReaderFailure,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CentredColumn(modifier) {
        Text(
            text = reason.message(),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        if (reason.canRetry) {
            Button(onClick = onRetry) { SingleLineLabel(stringResource(UiR.string.state_retry)) }
        }
        OutlinedButton(onClick = onBack) { SingleLineLabel(stringResource(UiR.string.navigate_back)) }
    }
}

@Composable
private fun ReaderFailure.message(): String = when (this) {
    is ReaderFailure.Download -> error.libraryMessage()
    ReaderFailure.DownloadNotAllowed -> stringResource(R.string.reader_failure_download_not_allowed)
    ReaderFailure.NotAnEpub -> stringResource(R.string.reader_failure_not_an_epub)
    ReaderFailure.Protected -> stringResource(R.string.reader_failure_protected)
    ReaderFailure.Engine -> stringResource(R.string.reader_failure_engine)
    ReaderFailure.CannotOpen -> stringResource(R.string.reader_failure_cannot_open)
    ReaderFailure.NoEpub -> stringResource(R.string.reader_failure_no_epub)
    ReaderFailure.OldWebView -> stringResource(R.string.reader_failure_old_web_view)
}

/** Trying again can help when the server or the page failed, not when the file itself is the problem. */
private val ReaderFailure.canRetry: Boolean
    get() = when (this) {
        is ReaderFailure.Download -> error.canRetry
        ReaderFailure.Engine -> true
        ReaderFailure.DownloadNotAllowed,
        ReaderFailure.NotAnEpub,
        ReaderFailure.Protected,
        ReaderFailure.CannotOpen,
        ReaderFailure.NoEpub,
        ReaderFailure.OldWebView,
        -> false
    }

/** Over the page while it opens the book: the cover and a spinner, on the page's colour. */
@Composable
internal fun OpeningCover(title: String, coverUrl: String?, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.reader_opening)
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag(ReaderOpeningTag),
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BookCover(coverUrl, title, CoverShape.Portrait, Modifier.width(CoverWidth), showTitleWhenMissing = false)
        CircularProgressIndicator(modifier = Modifier.semantics { contentDescription = label })
    }
}
