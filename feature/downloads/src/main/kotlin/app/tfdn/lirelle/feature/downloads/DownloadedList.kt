package app.tfdn.lirelle.feature.downloads

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ui.fileSize
import app.tfdn.lirelle.core.ui.R as UiR

/** The part of a downloaded book's row that opens its page. */
fun downloadedRowTag(bookId: BookId): String = "downloaded-$bookId"

fun rowMenuTag(bookId: BookId): String = "downloaded-menu-$bookId"

const val DeleteMenuItemTag = "downloads-delete-item"

private val RowCoverWidth = 56.dp

/**
 * A downloaded book: its cover, its title and author, the formats kept and their size on the device, and how far
 * the user is when the book was started. A tap opens its page; its menu button and a long press offer to delete
 * it from the device. A book to update says what its update would fetch, with the button that asks for it.
 *
 * @param card on a wide window: a card of the grid.
 * @param updateBytes what its update would fetch; null for a book with no update to ask for.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DownloadedBookRow(
    row: DownloadedRow,
    card: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    updateBytes: Long? = null,
    onUpdate: () -> Unit = {},
) {
    var menuShown by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier,
        shape = RowShape,
        color = if (card) MaterialTheme.colorScheme.surfaceContainer else Color.Transparent,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .testTag(downloadedRowTag(row.bookId))
                    .clip(RowShape)
                    .combinedClickable(
                        onClickLabel = stringResource(UiR.string.book_open_page),
                        onLongClickLabel = stringResource(R.string.downloads_row_menu_action),
                        onLongClick = { menuShown = true },
                        onClick = onOpen,
                    )
                    .padding(start = if (card) 10.dp else 0.dp, top = 6.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                KeptCover(row.cover, row.coverShape, Modifier.width(RowCoverWidth))
                RowTexts(row, updateBytes, onUpdate, Modifier.weight(1f))
            }
            Box {
                IconButton(onClick = { menuShown = true }, modifier = Modifier.testTag(rowMenuTag(row.bookId))) {
                    Icon(
                        imageVector = Icons.Outlined.MoreVert,
                        contentDescription = stringResource(R.string.downloads_row_menu, rowTitle(row.title)),
                    )
                }
                DropdownMenu(expanded = menuShown, onDismissRequest = { menuShown = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.downloads_delete)) },
                        onClick = {
                            menuShown = false
                            onDelete()
                        },
                        modifier = Modifier.testTag(DeleteMenuItemTag),
                        leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                    )
                }
            }
        }
    }
}

/** The title and the authors on one line each, as in the other lists; the size, the notes and the progress whole. */
@Composable
private fun RowTexts(row: DownloadedRow, updateBytes: Long?, onUpdate: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = rowTitle(row.title),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (row.authors.isNotEmpty()) {
            Text(
                text = row.authors,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            // The glyphs have a fixed size and are measured first; the size has the rest of the line.
            FormatIcons(row.formats, row.textName)
            Text(
                text = fileSize(row.bytes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        row.note?.let { RowNoteLine(it) }
        updateBytes?.let { UpdateOffer(row, it, onUpdate) }
        row.percent?.let { ReadingProgress(it, Modifier.fillMaxWidth().padding(top = 2.dp)) }
    }
}
