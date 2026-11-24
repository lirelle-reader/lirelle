package app.tfdn.lirelle.feature.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DownloadForOffline
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tfdn.lirelle.core.designsystem.LocalWindowWidthClass
import app.tfdn.lirelle.core.designsystem.WrappingLabel
import app.tfdn.lirelle.core.designsystem.WindowWidthClass
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ui.CollectionEdgePadding
import app.tfdn.lirelle.core.ui.OnShown

/** The lists on a phone: one column. */
const val DownloadsListTag = "downloads-list"

/** The lists on a wide window: cards on three columns. */
const val DownloadsGridTag = "downloads-grid"
const val DownloadsEmptyTag = "downloads-empty"
const val DownloadsBrowseTag = "downloads-browse"

private const val WideColumns = 3
private val CardGap = 12.dp
private val StorageMaxWidth = 420.dp

/**
 * What the screen asks: of the queue, of the Wi-Fi rule, of what the device keeps, of the updates, and of its own
 * messages.
 */
class DownloadsActions(
    val onCancel: (BookId) -> Unit = {},
    val onRetry: (BookId) -> Unit = {},
    val onDownloadNow: () -> Unit = {},
    val onAlwaysAllowMobile: () -> Unit = {},
    val onAskDelete: (BookId) -> Unit = {},
    val onConfirmDelete: (Set<DownloadFormat>) -> Unit = {},
    val onDismissDelete: () -> Unit = {},
    val onSnackbarDone: (snackbar: DownloadsSnackbar, undo: Boolean) -> Unit = { _, _ -> },
    val updates: UpdateActions = UpdateActions(),
)

/** What the screen asks of the updates: one, all of them, and, pulled down, to look for new ones. */
class UpdateActions(
    val onUpdate: (BookId) -> Unit = {},
    val onUpdateAll: () -> Unit = {},
    val onRefresh: () -> Unit = {},
)

/**
 * The Downloads destination: what the downloads take on the device, those in progress, and the downloaded
 * books, usable without a network.
 *
 * @param onOpenBook opens a book's page.
 * @param onBrowseLibrary leads to the library, from the screen with nothing downloaded.
 */
@Composable
fun DownloadsRoute(
    onOpenBook: (BookId) -> Unit,
    onBrowseLibrary: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DownloadsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    OnShown(viewModel::onShown)
    val actions = remember(viewModel) {
        DownloadsActions(
            onCancel = viewModel::cancel,
            onRetry = viewModel::retry,
            onDownloadNow = viewModel::downloadNow,
            onAlwaysAllowMobile = viewModel::alwaysAllowMobile,
            onAskDelete = viewModel::askDelete,
            onConfirmDelete = viewModel::confirmDelete,
            onDismissDelete = viewModel::dismissDelete,
            onSnackbarDone = viewModel::snackbarDone,
            updates = viewModel.updating.let { UpdateActions(it::update, it::updateAll, it::refresh) },
        )
    }
    DownloadsScreen(state, actions, onOpenBook, onBrowseLibrary, modifier)
}

/** The lists, with the screen's messages over their bottom and the sheet that asks which formats to delete. */
@Composable
fun DownloadsScreen(
    state: DownloadsUiState,
    actions: DownloadsActions,
    onOpenBook: (BookId) -> Unit,
    onBrowseLibrary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbars = remember { SnackbarHostState() }
    DownloadsMessages(state.snackbar, snackbars, actions.onSnackbarDone)
    Box(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding(),
    ) {
        if (state.loaded) DownloadsLists(state, actions, onOpenBook, onBrowseLibrary)
        SnackbarHost(
            hostState = snackbars,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .testTag(DownloadsSnackbarsTag),
        ) { MessageSnackbar(it) }
    }
    state.deleteSheet?.let { DeleteSheet(it, actions.onConfirmDelete, actions.onDismissDelete) }
}

/**
 * One column on a phone; on a wide window three, each row a card, and the storage beside the title. Pulled down,
 * the lists ask the server what changed.
 */
@Composable
private fun DownloadsLists(
    state: DownloadsUiState,
    actions: DownloadsActions,
    onOpenBook: (BookId) -> Unit,
    onBrowseLibrary: () -> Unit,
) {
    val wide = LocalWindowWidthClass.current == WindowWidthClass.Expanded
    PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = actions.updates.onRefresh) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(if (wide) WideColumns else 1),
            modifier = Modifier
                .fillMaxSize()
                .testTag(if (wide) DownloadsGridTag else DownloadsListTag),
            contentPadding = PaddingValues(CollectionEdgePadding),
            verticalArrangement = Arrangement.spacedBy(if (wide) CardGap else 4.dp),
            horizontalArrangement = Arrangement.spacedBy(CardGap),
        ) {
            wholeLine("heading") { DownloadsHeading(state.storage, wide) }
            state.wifi?.let { banner ->
                wholeLine("wifi") { WifiBanner(banner, actions.onDownloadNow, actions.onAlwaysAllowMobile) }
            }
            if (state.waitingForNetwork) wholeLine("waiting") { WaitingForNetwork() }
            if (state.inProgress.isNotEmpty()) {
                wholeLine("in-progress") { SectionTitle(R.string.downloads_in_progress, InProgressHeadingTag) }
                items(state.inProgress, key = { "progress-${it.bookId}" }) { row ->
                    InProgressRow(row, wide, { actions.onCancel(row.bookId) }, { actions.onRetry(row.bookId) })
                }
            }
            updatesSection(state, wide, actions.updates, onOpenBook) { actions.onAskDelete(it) }
            if (state.downloadedHeading) wholeLine("downloaded") { SectionTitle(R.string.downloads_downloaded) }
            items(state.books, key = { "book-${it.bookId}" }) { row ->
                DownloadedBookRow(row, wide, { onOpenBook(row.bookId) }, { actions.onAskDelete(row.bookId) })
            }
            if (state.isEmpty) wholeLine("empty") { EmptyDownloads(onBrowseLibrary) }
        }
    }
}

/** An item as wide as the window, whatever the number of columns. */
internal fun LazyGridScope.wholeLine(key: String, content: @Composable () -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }
}

/** The title of the destination, the storage under it on a phone and beside it on a wide window. */
@Composable
private fun DownloadsHeading(storage: StorageUi, wide: Boolean) {
    val title: @Composable () -> Unit = {
        Text(
            text = stringResource(R.string.downloads_title),
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineMedium,
        )
    }
    if (wide) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            title()
            StorageHeader(
                storage,
                Modifier
                    .padding(start = 32.dp)
                    .widthIn(max = StorageMaxWidth),
            )
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            title()
            StorageHeader(storage, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun SectionTitle(text: Int, tag: String = "") {
    Text(
        text = stringResource(text),
        modifier = Modifier
            .padding(top = 12.dp, bottom = 4.dp)
            .testTag(tag)
            .semantics { heading() },
        style = MaterialTheme.typography.titleMedium,
    )
}

/** Nothing downloaded and nothing asked: how to keep a book, and the way to the library. */
@Composable
private fun EmptyDownloads(onBrowseLibrary: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 40.dp)
            .testTag(DownloadsEmptyTag),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Outlined.DownloadForOffline,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.outline,
        )
        Text(
            text = stringResource(R.string.downloads_empty_title),
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.downloads_empty_message),
            modifier = Modifier.widthIn(max = 480.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        FilledTonalButton(onClick = onBrowseLibrary, modifier = Modifier.testTag(DownloadsBrowseTag)) {
            WrappingLabel(stringResource(R.string.downloads_browse))
        }
    }
}
