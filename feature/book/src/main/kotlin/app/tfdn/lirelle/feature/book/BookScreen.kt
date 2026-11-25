package app.tfdn.lirelle.feature.book

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tfdn.lirelle.core.designsystem.LocalWindowWidthClass
import app.tfdn.lirelle.core.designsystem.WindowWidthClass
import app.tfdn.lirelle.core.ids.AuthorId
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.SeriesId
import app.tfdn.lirelle.core.library.SourceCapabilities
import app.tfdn.lirelle.core.library.model.BookDetail
import app.tfdn.lirelle.core.ui.DetailScaffold
import app.tfdn.lirelle.core.ui.Loadable
import app.tfdn.lirelle.core.ui.LoadableContent
import app.tfdn.lirelle.core.ui.NoNetworkState
import app.tfdn.lirelle.core.ui.NotificationsQuestion
import app.tfdn.lirelle.core.ui.OnShown
import app.tfdn.lirelle.core.ui.ServerFailureBar
import app.tfdn.lirelle.core.ui.isNoNetwork
import app.tfdn.lirelle.feature.book.download.BookDownloadUiState
import app.tfdn.lirelle.feature.book.download.BookDownloadViewModel
import app.tfdn.lirelle.feature.book.download.DownloadActions
import app.tfdn.lirelle.feature.book.download.FormatsSheet

const val RefreshIndicatorTag = "book-refresh-indicator"

private val BlockSpacing = 24.dp
private val PagePadding = 16.dp
private val SideColumnMaxWidth = 360.dp

/** Under the bar, whose back button already has room around its arrow. */
private val OneColumnTopPadding = 4.dp
private val CompactCoverWidth = 120.dp
private val MediumCoverWidth = 160.dp

/**
 * Where the links of the page lead, the downloads of its book, and what its source offers.
 *
 * @property onOpenAuthor null when the source has no author pages: the names open nothing.
 * @property offers what the source can do: a block or a fact it cannot give is left out (spec 2c).
 */
internal class BookLinks(
    val onOpenBook: (BookId) -> Unit,
    val onOpenAuthor: ((AuthorId) -> Unit)?,
    val onOpenGenre: (String) -> Unit,
    val starts: BookStarts,
    val download: DownloadPart = DownloadPart(),
    val onOpenSeries: (SeriesId) -> Unit = {},
    val offers: SourceCapabilities = SourceCapabilities.Everything,
)

/**
 * The page of a book.
 *
 * @param onOpenGenre opens the books of a genre, given its name.
 * @param onOpenSeries opens the page of the series of the book, given its id.
 * @param onRead opens the reader on the book: where it was left, or from its start when a finished
 *   book is read again.
 * @param onListen loads the book in the player, plays it and opens the player: where it was left, or
 *   from its start when a finished listening is done again.
 * @param onNarrate opens the reader on a book whose only narration is inside its read-along EPUB, narrating it
 *   from where it opens.
 * @param onOpenDownloads opens the downloaded books, from the page of a book that cannot be loaded for lack
 *   of a network.
 * @param downloads the downloads of the book: its tile, the line under it and the formats sheet.
 * @param onReadComic opens the comics reader on a book that is a comic, as [onRead] opens the reader.
 */
@Composable
fun BookScreen(
    bookId: BookId,
    onBack: () -> Unit,
    onOpenBook: (BookId) -> Unit,
    onOpenAuthor: (AuthorId) -> Unit,
    onOpenGenre: (String) -> Unit,
    onOpenSeries: (SeriesId) -> Unit,
    onRead: (bookId: BookId, fromStart: Boolean) -> Unit,
    onListen: (bookId: BookId, fromStart: Boolean) -> Unit,
    onNarrate: (bookId: BookId) -> Unit,
    modifier: Modifier = Modifier,
    onOpenDownloads: () -> Unit = {},
    onReadComic: (bookId: BookId, fromStart: Boolean) -> Unit = { _, _ -> },
    // The factories take the id's text: an assisted parameter cannot be a value class.
    viewModel: BookViewModel = hiltViewModel<BookViewModel, BookViewModel.Factory>(
        key = "book-$bookId",
        creationCallback = { it.create(bookId.value) },
    ),
    downloads: BookDownloadViewModel = hiltViewModel<BookDownloadViewModel, BookDownloadViewModel.Factory>(
        key = "book-download-$bookId",
        creationCallback = { it.create(bookId.value) },
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val download by downloads.state.collectAsStateWithLifecycle()
    OnShown(viewModel::onShown, viewModel::onHidden)
    // The formats of the book shown are what can be asked; a download asked keeps the page with the server's reading.
    val book = (state.book as? Loadable.Ready)?.value
    val reading = state.reading.takeUnless { state.offline }
    LaunchedEffect(book, reading) { book?.let { downloads.show(it, reading) } }
    NotificationsQuestion(download.askNotifications, downloads::notificationsHandled)
    BookContent(
        state = state,
        onBack = onBack,
        onRetry = viewModel::load,
        onRefresh = viewModel::refresh,
        onOpenBook = onOpenBook,
        onOpenAuthor = onOpenAuthor,
        onOpenGenre = onOpenGenre,
        onRead = onRead,
        onListen = onListen,
        onNarrate = onNarrate,
        modifier = modifier,
        download = download,
        downloadActions = DownloadActions(
            onTile = downloads::onTile,
            onLineAction = downloads::onLineAction,
            onCloseSheet = downloads::closeSheet,
            onApply = downloads::apply,
            onInUseShown = downloads::inUseShown,
        ),
        onOpenDownloads = onOpenDownloads,
        onOpenSeries = onOpenSeries,
        onReadComic = onReadComic,
        capabilities = viewModel.capabilities,
    )
}

/**
 * @param onRetry loads the page again from nothing, after it failed to load.
 * @param onRefresh reloads the page while it stays on screen: pulled down, or retried from the bar
 *   of a refresh that failed.
 * @param download the downloads of the book; a deletion refused because the book is in use is said in a
 *   snackbar, and the formats sheet opens over the page.
 * @param onOpenDownloads opens the downloaded books, from the "No network" state of a book that is not one.
 * @param onOpenSeries opens the page of the series of the book, given its id.
 * @param onReadComic opens the comics reader on a book that is a comic, where it was left or from its start.
 * @param capabilities what the source offers: without ratings no stars, without genres no genre chips, without
 *   authors names that open nothing, without reading sessions no time read nor sessions (spec 2c).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookContent(
    state: BookUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onRefresh: () -> Unit,
    onOpenBook: (BookId) -> Unit,
    onOpenAuthor: (AuthorId) -> Unit,
    onOpenGenre: (String) -> Unit,
    onRead: (bookId: BookId, fromStart: Boolean) -> Unit,
    onListen: (bookId: BookId, fromStart: Boolean) -> Unit,
    onNarrate: (bookId: BookId) -> Unit,
    modifier: Modifier = Modifier,
    download: BookDownloadUiState = BookDownloadUiState(),
    downloadActions: DownloadActions = DownloadActions(),
    onOpenDownloads: () -> Unit = {},
    onOpenSeries: (SeriesId) -> Unit = {},
    onReadComic: (bookId: BookId, fromStart: Boolean) -> Unit = { _, _ -> },
    capabilities: SourceCapabilities = SourceCapabilities.Everything,
) {
    val gone = state.offline && !state.noNetwork
    val part = DownloadPart(download, downloadActions, kept = state.keptFormats.takeIf { state.offline }, gone)
    val starts = BookStarts(onRead, onListen, onNarrate, onReadComic)
    val links = BookLinks(
        onOpenBook = onOpenBook,
        onOpenAuthor = onOpenAuthor.takeIf { capabilities.authors },
        onOpenGenre = onOpenGenre,
        starts = starts,
        download = part,
        onOpenSeries = onOpenSeries,
        offers = capabilities,
    )
    val snackbars = remember { SnackbarHostState() }
    val inUse = stringResource(R.string.download_in_use)
    LaunchedEffect(download.inUse) {
        if (download.inUse) {
            snackbars.showSnackbar(inUse)
            downloadActions.onInUseShown()
        }
    }
    // No title in the bar: it is in the page itself, where it can wrap.
    DetailScaffold(title = null, onBack = onBack, modifier = modifier, compactBar = true) {
        // Around the scrolling columns, which pass it the pull they have no use for.
        Refreshable(state, onRefresh) {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.weight(1f)) {
                    BookPage(state, links, onRetry, onOpenDownloads)
                    SnackbarHost(snackbars, modifier = Modifier.align(Alignment.BottomCenter))
                }
                state.refreshError?.let { error -> ServerFailureBar(error, onRetry = onRefresh) }
            }
        }
    }
    val sheet = download.sheet
    val book = (state.book as? Loadable.Ready)?.value
    if (sheet != null && book != null) {
        FormatsSheet(book.title, sheet, onDismiss = downloadActions.onCloseSheet, onApply = downloadActions.onApply)
    }
}

/**
 * The page pulled down to be refreshed, with the indicator of the pull.
 *
 * A page built from what the device keeps has nothing to refresh: it is not pulled, and has no indicator at
 * all. There, a refresh fails at once, and an indicator hidden as soon as it is shown can stay half drawn at
 * the top of the page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Refreshable(state: BookUiState, onRefresh: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    if (state.offline) {
        Box(content = content)
        return
    }
    val pull = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = state.refreshing,
        onRefresh = onRefresh,
        state = pull,
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = pull,
                isRefreshing = state.refreshing,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .testTag(RefreshIndicatorTag),
            )
        },
        content = content,
    )
}

/** The page, or what takes its place: without a network, the way to the downloaded books and not an error. */
@Composable
private fun BookPage(state: BookUiState, links: BookLinks, onRetry: () -> Unit, onOpenDownloads: () -> Unit) {
    val failure = (state.book as? Loadable.Failed)?.error
    if (failure?.isNoNetwork == true) {
        NoNetworkState(onRetry = onRetry, onOpenDownloads = onOpenDownloads)
        return
    }
    LoadableContent(state = state.book, onRetry = onRetry) { book ->
        if (LocalWindowWidthClass.current == WindowWidthClass.Expanded) {
            TwoColumns(book, state, links)
        } else {
            OneColumn(book, state, links)
        }
    }
}

/** As tall as the page even when its blocks are not, so that it can be pulled down from anywhere. */
@Composable
private fun OneColumn(book: BookDetail, state: BookUiState, links: BookLinks) {
    val isCompact = LocalWindowWidthClass.current == WindowWidthClass.Compact
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = PagePadding, top = OneColumnTopPadding, end = PagePadding, bottom = PagePadding),
        verticalArrangement = Arrangement.spacedBy(BlockSpacing),
    ) {
        if (state.offline) OfflineChip(state.noNetwork)
        HeaderBlock(
            book = book,
            onOpenAuthor = links.onOpenAuthor,
            sideCoverWidth = if (isCompact) CompactCoverWidth else MediumCoverWidth,
        )
        ActionsBlock(book, state.reading, state.listening, links.starts, links.download)
        FactsBlock(book, ratings = links.offers.ratings)
        // What only the server knows is not on a page built without it.
        if (!state.offline) EditionsBlock(book, links.onOpenBook)
        LowerBlocks(book, state, links)
    }
}

/**
 * The cover, title, actions and facts stay on the left; the rest scrolls on the right.
 * The left column is never wider than [SideColumnMaxWidth]; the right one takes what is left.
 * Both are as tall as the page even when their blocks are not, so that either can be pulled down.
 */
@Composable
private fun TwoColumns(book: BookDetail, state: BookUiState, links: BookLinks) {
    Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(BlockSpacing)) {
        Column(
            modifier = Modifier
                .widthIn(max = SideColumnMaxWidth)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(PagePadding),
            verticalArrangement = Arrangement.spacedBy(BlockSpacing),
        ) {
            if (state.offline) OfflineChip(state.noNetwork)
            HeaderBlock(book, links.onOpenAuthor)
            ActionsBlock(book, state.reading, state.listening, links.starts, links.download)
            FactsBlock(book, ratings = links.offers.ratings)
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(PagePadding),
            verticalArrangement = Arrangement.spacedBy(BlockSpacing),
        ) {
            if (!state.offline) EditionsBlock(book, links.onOpenBook, columns = 2)
            LowerBlocks(book, state, links)
        }
    }
}

@Composable
private fun LowerBlocks(book: BookDetail, state: BookUiState, links: BookLinks) {
    // The device's position: sent when the network is back, unless the book is gone from the server.
    val positionNote = when {
        !state.offline -> null
        state.noNetwork -> R.string.reading_offline_note
        else -> R.string.reading_device_note
    }
    ReadingBlock(book, state.reading, positionNote, sessions = links.offers.readingSessions)
    AboutBlock(book, links.onOpenGenre, genres = links.offers.genres)
    FilesBlock(book)
    DetailsBlock(book)
    // What only the server knows is not on a page built without it.
    if (state.offline) return
    // A series the server gives without an id has no page to open.
    val seriesId = book.series?.id
    RelatedBlock(
        title = R.string.section_in_series,
        tag = "book-in-series",
        books = state.related.inSeries,
        onOpenBook = links.onOpenBook,
        showSeriesIndex = true,
        onOpenAll = seriesId?.let { id -> { links.onOpenSeries(id) } },
    )
    RelatedBlock(R.string.section_same_author, "book-same-author", state.related.sameAuthor, links.onOpenBook)
    RelatedBlock(R.string.section_similar, "book-similar", state.related.similar, links.onOpenBook)
}
