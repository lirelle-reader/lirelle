package app.tfdn.lirelle.feature.library.series

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.designsystem.WordsLabel
import app.tfdn.lirelle.core.ui.fileSize
import app.tfdn.lirelle.feature.library.R

const val SeriesDownloadButtonTag = "series-download-button"
const val SeriesDownloadLineTag = "series-download-line"

/**
 * Under the heading of a series: the button that downloads it, and a line that says how much of it the device
 * has. The button stays when the device has every book: another format may be wanted. An account that may not
 * download has it disabled, the line saying why.
 */
@Composable
internal fun SeriesDownloadBlock(state: SeriesDownloadUiState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.series_download_button)
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FilledTonalButton(
            onClick = onClick,
            enabled = state.allowed,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(SeriesDownloadButtonTag)
                .semantics { contentDescription = label },
        ) {
            Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            WordsLabel(label)
        }
        state.line()?.let { line ->
            Text(
                text = line,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(SeriesDownloadLineTag),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Why the button is disabled; else how many books are on the device and what they take; nothing when none is. */
@Composable
private fun SeriesDownloadUiState.line(): String? = when {
    !allowed -> stringResource(R.string.series_download_not_allowed)
    onDevice == null -> null
    onDevice.books >= onDevice.of -> pluralStringResource(
        R.plurals.series_download_all_on_device,
        onDevice.of,
        onDevice.of,
        fileSize(onDevice.bytes),
    )
    else -> pluralStringResource(
        R.plurals.series_download_on_device,
        onDevice.books,
        booksOf(onDevice.books, onDevice.of),
        fileSize(onDevice.bytes),
    )
}

/** "2 of 7 books": each language puts the noun where it reads well, and agrees it with its own number. */
@Composable
internal fun booksOf(count: Int, of: Int): String = stringResource(
    R.string.series_download_of,
    pluralStringResource(R.plurals.series_download_part, count, count),
    pluralStringResource(R.plurals.series_download_whole, of, of),
)
