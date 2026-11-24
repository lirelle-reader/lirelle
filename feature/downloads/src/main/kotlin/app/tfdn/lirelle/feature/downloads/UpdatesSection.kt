package app.tfdn.lirelle.feature.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.designsystem.WrappingLabel
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ui.fileSize

/** The title of the section of the books to update. */
const val UpdatesHeadingTag = "downloads-updates"
const val UpdateAllTag = "downloads-update-all"

fun updateTag(bookId: BookId): String = "downloads-update-$bookId"

/**
 * The books with a newer version on the server: their heading, then each as its usual row with what its update
 * would fetch and the button that asks for it. Nothing when there is none.
 *
 * @param card on a wide window: the rows are cards of the grid.
 */
internal fun LazyGridScope.updatesSection(
    state: DownloadsUiState,
    card: Boolean,
    actions: UpdateActions,
    onOpenBook: (BookId) -> Unit,
    onAskDelete: (BookId) -> Unit,
) {
    if (state.updates.isEmpty()) return
    wholeLine("updates") { UpdatesHeading(state.updates.size, state.updatesBytes, actions.onUpdateAll) }
    items(state.updates, key = { "update-${it.book.bookId}" }) { update ->
        val bookId = update.book.bookId
        DownloadedBookRow(
            row = update.book,
            card = card,
            onOpen = { onOpenBook(bookId) },
            onDelete = { onAskDelete(bookId) },
            updateBytes = update.updateBytes,
            onUpdate = { actions.onUpdate(bookId) },
        )
    }
}

/**
 * "Updates", how many books and how much under it, and "Update all" at its end: under it when both do not fit on
 * one line, so neither is squeezed.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UpdatesHeading(count: Int, bytes: Long, onUpdateAll: () -> Unit) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(R.string.downloads_updates),
                modifier = Modifier
                    .testTag(UpdatesHeadingTag)
                    .semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(
                    R.string.downloads_updates_summary,
                    pluralStringResource(R.plurals.downloads_book_count, count, count),
                    fileSize(bytes),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onUpdateAll, modifier = Modifier.testTag(UpdateAllTag)) {
            WrappingLabel(stringResource(R.string.downloads_update_all))
        }
    }
}

/**
 * On the row of a book to update: what the update would fetch, in the accent colour, and "Update" at the end of
 * the line, or under it when the line is too short for both.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun UpdateOffer(row: DownloadedRow, bytes: Long, onUpdate: () -> Unit) {
    val description = stringResource(R.string.downloads_update_description, rowTitle(row.title))
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.downloads_update_size, fileSize(bytes)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium,
        )
        TextButton(
            onClick = onUpdate,
            modifier = Modifier
                .testTag(updateTag(row.bookId))
                .semantics { contentDescription = description },
            contentPadding = PaddingValues(horizontal = 8.dp),
        ) { WrappingLabel(stringResource(R.string.downloads_update)) }
    }
}

/** What a downloaded book's row says besides: that the server no longer has it, or that its update waits. */
@Composable
internal fun RowNoteLine(note: RowNote) {
    val text = when (note) {
        RowNote.Gone -> R.string.downloads_gone
        RowNote.UpdateReady -> R.string.downloads_update_ready
    }
    Text(
        text = stringResource(text),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.Medium,
    )
}
