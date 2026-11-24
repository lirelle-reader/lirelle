package app.tfdn.lirelle.feature.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.ui.fileSize

const val StorageHeaderTag = "downloads-storage"
const val StorageBarTag = "downloads-storage-bar"

private val BarHeight = 8.dp

/** The least width of the downloads on the bar: a few megabytes on a large storage still show. */
private val LeastShare = 4.dp

/**
 * A quiet card: what the downloads take and how many books they are, a bar placing it against the device's
 * storage, and the device's free space. With nothing complete, it says so: "not yet" while something is asked.
 */
@Composable
internal fun StorageHeader(storage: StorageUi, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.testTag(StorageHeaderTag),
        shape = RowShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StorageSummary(storage)
            StorageBar(storage)
            Text(
                text = stringResource(R.string.downloads_storage_free, fileSize(storage.freeBytes)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One line when it fits: the size and, beside it, the number of books; or that nothing is downloaded. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StorageSummary(storage: StorageUi) {
    if (storage.bookCount == 0) {
        val nothing = if (storage.queued) R.string.downloads_storage_none_yet else R.string.downloads_storage_none
        Text(text = stringResource(nothing), style = MaterialTheme.typography.titleSmall)
        return
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(R.string.downloads_storage_used, fileSize(storage.downloadsBytes)),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = pluralStringResource(R.plurals.downloads_book_count, storage.bookCount, storage.bookCount),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** From its start: the device's other data, the downloads, then what is free. */
@Composable
private fun StorageBar(storage: StorageUi) {
    val description = stringResource(
        R.string.downloads_storage_bar,
        fileSize(storage.downloadsBytes),
        fileSize(storage.freeBytes),
        fileSize(storage.totalBytes),
    )
    val other = MaterialTheme.colorScheme.outline
    val downloads = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .testTag(StorageBarTag)
            .fillMaxWidth()
            .height(BarHeight)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .semantics { contentDescription = description }
            .drawBehind {
                val total = storage.totalBytes.coerceAtLeast(1).toFloat()
                val otherBytes = (storage.totalBytes - storage.freeBytes - storage.downloadsBytes).coerceAtLeast(0)
                val otherWidth = size.width * otherBytes / total
                val downloadsWidth = if (storage.downloadsBytes > 0) {
                    (size.width * storage.downloadsBytes / total).coerceAtLeast(LeastShare.toPx())
                } else {
                    0f
                }
                // Mirrored with the layout: the bar starts where the text does.
                val mirrored = layoutDirection == LayoutDirection.Rtl
                fun x(start: Float, width: Float) = if (mirrored) size.width - start - width else start
                drawRect(other, Offset(x(0f, otherWidth), 0f), Size(otherWidth, size.height))
                drawRect(downloads, Offset(x(otherWidth, downloadsWidth), 0f), Size(downloadsWidth, size.height))
            },
    )
}
