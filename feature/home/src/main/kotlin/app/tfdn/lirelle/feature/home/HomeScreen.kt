package app.tfdn.lirelle.feature.home

import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tfdn.lirelle.core.designsystem.LocalWindowWidthClass
import app.tfdn.lirelle.core.designsystem.WindowWidthClass
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.model.BookSummary
import app.tfdn.lirelle.core.library.model.HomeRow
import app.tfdn.lirelle.core.library.model.HomeRowKind
import app.tfdn.lirelle.core.ui.CollectionEdgePadding
import app.tfdn.lirelle.core.ui.ContinueCard
import app.tfdn.lirelle.core.ui.CoverRow
import app.tfdn.lirelle.core.ui.EmptyMessage
import app.tfdn.lirelle.core.ui.FailedState
import app.tfdn.lirelle.core.ui.Loadable
import app.tfdn.lirelle.core.ui.OnShown
import app.tfdn.lirelle.core.ui.PagingErrorBar
import app.tfdn.lirelle.core.ui.ScreenTitleRow
import app.tfdn.lirelle.core.ui.SectionHeading
import app.tfdn.lirelle.core.ui.canRetry
import app.tfdn.lirelle.core.ui.libraryMessage

// A card is narrower than the screen, so the next one shows its edge.
private val PhoneCardWidth = 286.dp
private val WideCardWidth = 460.dp
private val CardSpacing = 12.dp
private val RowSpacing = 16.dp
private const val PlaceholderBooks = 6

@StringRes
fun HomeRowKind.titleRes(): Int = when (this) {
    HomeRowKind.Continue -> R.string.home_row_continue
    HomeRowKind.WantToRead -> R.string.home_row_want_to_read
    HomeRowKind.UpNext -> R.string.home_row_up_next
    HomeRowKind.RecentlyAdded -> R.string.home_row_recently_added
}

/**
 * Where a book of Home leads: its page, the reader or the player for one being read or listened to, the reader
 * narrating it for one whose only narration is inside its read-along EPUB, the comics reader for a comic.
 */
internal class HomeLinks(
    val onOpenBook: (BookId) -> Unit,
    val onRead: (BookId) -> Unit,
    val onListen: (BookId) -> Unit,
    val onNarrate: (BookId) -> Unit,
    val onReadComic: (BookId) -> Unit = {},
)

/**
 * The Home destination: what is being read or listened to, what comes next, what is new.
 *
 * @param onRead opens the reader on a book being read, where it was left; [onOpenBook] opens a book's page.
 * @param onListen loads a book being listened to in the player, plays it where it was left and opens the player.
 * @param onNarrate opens the reader on a book whose only narration is inside its read-along EPUB, narrating it.
 * @param onReadComic opens the comics reader on a comic being read, where it was left.
 * @param onSignInAgain opens the sign-in screens for the profile of that id, whose session expired: what the
 *   notice's button does. The app gives it, as it gives [titleAction]: Home knows nothing of the sign-in.
 * @param onOpenDownloads selects the Downloads destination: where Home sends from under the notice while it
 *   has no row to show. Neither has a default: a Home wired without them does not compile.
 * @param titleAction what stands at the end of the title's row: the profile button, which the app gives.
 */
@Composable
fun HomeRoute(
    onOpenBook: (BookId) -> Unit,
    onRead: (BookId) -> Unit,
    onListen: (BookId) -> Unit,
    onNarrate: (BookId) -> Unit,
    onSeeAll: (HomeRowKind) -> Unit,
    onSignInAgain: (profileId: String) -> Unit,
    onOpenDownloads: () -> Unit,
    modifier: Modifier = Modifier,
    onReadComic: (BookId) -> Unit = {},
    titleAction: @Composable () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    OnShown(viewModel::onShown, viewModel::onHidden)
    HomeContent(
        state = state,
        onRetry = viewModel::load,
        onRefresh = viewModel::refresh,
        onOpenBook = onOpenBook,
        onRead = onRead,
        onListen = onListen,
        onNarrate = onNarrate,
        onSeeAll = onSeeAll,
        onSignInAgain = onSignInAgain,
        onOpenDownloads = onOpenDownloads,
        modifier = modifier,
        onReadComic = onReadComic,
        titleAction = titleAction,
    )
}

/**
 * The title and the rows, scrolling as a whole, under pull to refresh. When no row could be loaded, the title
 * and what failed, in place of the rows.
 *
 * While the profile waits for a sign-in ([HomeUiState.expired]), a notice stands under the title in every one
 * of these states, with the rows Home has, and no failure is said: the notice is the reason for all of them.
 * The screen that failure would show for lack of network is the one that leads to the downloaded books, so
 * while Home has no row ([HomeUiState.hasRows]) that way is offered under the notice instead.
 *
 * @param onSignInAgain opens the sign-in screens for the profile of that id: the notice's button.
 * @param onOpenDownloads selects the Downloads destination: the action under the notice.
 * @param onRetry loads everything again from nothing: after a row failed to load.
 * @param onRefresh loads again and keeps what is shown meanwhile.
 * @param onRead opens the reader on a book of the "Continue" row that has a text.
 * @param onListen plays a book of the "Continue" row that has an audio file, in the player.
 * @param onNarrate narrates in the reader a book of the "Continue" row whose only audio is its read-along EPUB's.
 * @param onReadComic opens the comics reader on a comic of the "Continue" row.
 * @param titleAction what stands at the end of the title's row, centred on the title; the row is as high
 *   with it as without.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeContent(
    state: HomeUiState,
    onRetry: () -> Unit,
    onRefresh: () -> Unit,
    onOpenBook: (BookId) -> Unit,
    onRead: (BookId) -> Unit,
    onListen: (BookId) -> Unit,
    onNarrate: (BookId) -> Unit,
    onSeeAll: (HomeRowKind) -> Unit,
    onSignInAgain: (profileId: String) -> Unit,
    onOpenDownloads: () -> Unit,
    modifier: Modifier = Modifier,
    onReadComic: (BookId) -> Unit = {},
    titleAction: @Composable () -> Unit = {},
) {
    val links = HomeLinks(onOpenBook, onRead, onListen, onNarrate, onReadComic)
    val expired = state.expired
    // While the profile waits for a sign-in its server is not asked: whatever failed, failed for that. The
    // notice says so and offers what helps; "check your connection" and "Retry" beside it would be wrong.
    val failure = state.failure.takeIf { expired == null }
    val refreshError = state.refreshError.takeIf { expired == null }
    if (state.allFailed && failure != null) {
        // The title stays, where it is when the rows are there: its action — the profile button — is the way
        // to another profile, which is wanted most when this one's server does not answer.
        Column(
            modifier = modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(top = CollectionEdgePadding),
        ) {
            HomeTitle(titleAction)
            FailedState(failure, onRetry, scrolls = true)
        }
        return
    }
    PullToRefreshBox(
        isRefreshing = state.refreshing,
        onRefresh = onRefresh,
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // As tall as the room left, even with little in it: a pull anywhere refreshes.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = CollectionEdgePadding),
                verticalArrangement = Arrangement.spacedBy(RowSpacing),
            ) {
                HomeTitle(titleAction)
                if (expired != null) {
                    SignInNoticeBlock(
                        expired = expired,
                        offersOfflineBooks = !state.hasRows,
                        onSignInAgain = { onSignInAgain(expired.profileId) },
                        onOpenDownloads = onOpenDownloads,
                        modifier = Modifier.padding(horizontal = CollectionEdgePadding),
                    )
                }
                if (state.isEmpty) {
                    EmptyMessage(stringResource(R.string.home_empty))
                } else {
                    state.rows.forEach { (kind, row) -> HomeRowSlot(kind, row, links, onSeeAll, state) }
                }
            }
            // A row that never loaded is loaded from nothing; a refresh that failed is tried again as a refresh,
            // which leaves the rows on screen.
            (failure ?: refreshError)?.let { error ->
                PagingErrorBar(
                    message = error.libraryMessage(),
                    onRetry = if (failure != null) onRetry else onRefresh,
                    canRetry = error.canRetry,
                )
            }
        }
    }
}

/** Home's title and what stands at its end, the same in every state of Home: neither moves from one to the next. */
@Composable
private fun HomeTitle(action: @Composable () -> Unit) {
    ScreenTitleRow(
        title = stringResource(R.string.home_title),
        modifier = Modifier.padding(horizontal = CollectionEdgePadding),
        action = action,
    )
}

/** One row in its state: placeholders while it loads, nothing when it failed or has no book. */
@Composable
private fun HomeRowSlot(
    kind: HomeRowKind,
    row: Loadable<HomeRow>,
    links: HomeLinks,
    onSeeAll: (HomeRowKind) -> Unit,
    state: HomeUiState,
) {
    val title = stringResource(kind.titleRes())
    when (row) {
        Loadable.Loading -> CoverRow(title, List(PlaceholderBooks) { null }, onOpenBook = {})
        is Loadable.Failed -> Unit
        is Loadable.Ready -> if (row.value.books.isNotEmpty()) {
            val seeAll = { onSeeAll(kind) }.takeIf { row.value.hasMore }
            if (kind == HomeRowKind.Continue) {
                ContinueRow(title, row.value.books, links, seeAll, state)
            } else {
                CoverRow(
                    title = title,
                    books = row.value.books,
                    onOpenBook = links.onOpenBook,
                    onSeeAll = seeAll,
                    showSeries = kind == HomeRowKind.UpNext,
                )
            }
        }
    }
}

/**
 * The books being read or listened to, as wide cards in a row that scrolls sideways. The cover opens
 * the book's page; a button per medium goes on reading or listening. The listening time left fills in
 * as it arrives.
 */
@Composable
private fun ContinueRow(
    title: String,
    books: List<BookSummary>,
    links: HomeLinks,
    onSeeAll: (() -> Unit)?,
    state: HomeUiState,
) {
    val wide = LocalWindowWidthClass.current == WindowWidthClass.Expanded
    Column(modifier = Modifier.fillMaxWidth()) {
        SectionHeading(title, Modifier.padding(horizontal = CollectionEdgePadding), onSeeAll = onSeeAll)
        // Every card as tall as the tallest: their buttons share one bottom line.
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .height(IntrinsicSize.Max)
                .padding(horizontal = CollectionEdgePadding),
            horizontalArrangement = Arrangement.spacedBy(CardSpacing),
        ) {
            books.forEach { book ->
                val listening = state.listening[book.id]
                ContinueCard(
                    book = book.copy(timeLeftSeconds = listening?.timeLeftSeconds),
                    onOpenBook = { links.onOpenBook(book.id) },
                    onRead = { if (book.isPaged) links.onReadComic(book.id) else links.onRead(book.id) },
                    onListen = { if (book.hasAudioFiles) links.onListen(book.id) else links.onNarrate(book.id) },
                    modifier = Modifier
                        .width(if (wide) WideCardWidth else PhoneCardWidth)
                        .fillMaxHeight(),
                    now = state.now,
                    lastMedium = book.lastMedium(listening),
                )
            }
        }
    }
}
