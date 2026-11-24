package app.tfdn.lirelle.feature.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tfdn.lirelle.core.designsystem.SingleLineLabel
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ui.NotOffered
import app.tfdn.lirelle.core.ui.OnShown
import app.tfdn.lirelle.feature.player.bookmarks.BookmarkEditing
import app.tfdn.lirelle.feature.player.bookmarks.BookmarkSnackbar
import app.tfdn.lirelle.feature.player.bookmarks.PlayerBookmarks
import app.tfdn.lirelle.feature.player.bookmarks.rememberPlayerBookmarks
import app.tfdn.lirelle.feature.player.controller.PlayerUiState
import java.time.Instant

internal const val PlayerMenuTag = "player-menu"
internal const val PlayerLoadingTag = "player-loading"
internal const val PlayerRetryTag = "player-retry"

/** The sheets the player opens over itself. */
internal enum class PlayerSheet { Chapters, Speed, Sleep }

/**
 * The player of the audiobook loaded, on the whole screen (spec 5). Each return to the front looks for
 * a newer position from elsewhere. A source without audiobooks has no player: reached by an old link, it goes
 * back (spec 2c).
 *
 * @param onMinimise back to where the user was, the mini-player shown.
 * @param onOpenBook the page of this book, from the menu.
 * @param onOpenSettings the player settings, from the menu.
 * @param onLeave the book was closed from the end panel.
 */
@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel,
    onMinimise: () -> Unit,
    onOpenBook: (BookId) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    onLeave: () -> Unit = onMinimise,
) {
    if (!viewModel.offered) {
        NotOffered(onMinimise, modifier)
        return
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    OnShown(viewModel::onShown)
    val leave by rememberUpdatedState(onLeave)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                PlayerEvent.Leave -> leave()
            }
        }
    }
    // The age of a newer position is told from the moment it is offered.
    val now = remember(state.newerPosition) { state.newerPosition?.let { Instant.now() } }
    PlayerContent(state, viewModel, onMinimise, onOpenBook, onOpenSettings, modifier, now)
}

/**
 * Everything the player shows, from its state: the top bar, a newer position or why playback waits,
 * the book being played (two panes on a wide landscape window, the chapters always shown), its
 * sheets, the end panel of a finished book, and the bookmarks' snackbars and edit dialog.
 *
 * @param now the moment it is, for the age of a newer position; null gives the place alone.
 */
@Composable
internal fun PlayerContent(
    state: PlayerUiState,
    actions: PlayerActions,
    onMinimise: () -> Unit,
    onOpenBook: (BookId) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    now: Instant? = null,
) {
    var sheet by rememberSaveable { mutableStateOf<PlayerSheet?>(null) }
    // None from a source without audio bookmarks: no button, no tab, no snackbar nor dialog (spec 2c).
    val bookmarks = rememberPlayerBookmarks(actions) { sheet = PlayerSheet.Chapters }
        .takeIf { state.capabilities.audioBookmarks }
    val panel = state.finished?.takeUnless { it.panelSeen }
    Box(modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                // While the end panel is open, a screen reader stays in it: the player behind is out of reach.
                .then(if (panel != null) Modifier.clearAndSetSemantics {} else Modifier),
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
        ) {
            Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
                PlayerTopBar(state.bookId, onMinimise, onOpenBook, onOpenSettings) {
                    // The book closes; the screen leaves as minimising does.
                    actions.stopListening()
                    onMinimise()
                }
                if (state.loading && state.bookId != null) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.newerPosition?.let {
                    NewerPositionBanner(it, now, actions::acceptNewerPosition, actions::ignoreNewerPosition)
                }
                PlayerStatus(state, actions::retryLoad)
                PlayerBody(state, actions, onSheet = { sheet = it }, bookmarks, Modifier.weight(1f))
            }
        }
        // The chapters sheet shows the bookmarks' snackbars over itself.
        if (bookmarks != null && sheet != PlayerSheet.Chapters) {
            val clear = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
            SnackbarHost(
                hostState = bookmarks.snackbars,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(clear),
                snackbar = { BookmarkSnackbar(it) },
            )
        }
        panel?.let {
            AudioEndPanel(it, state.title, state.listenedSeconds, actions, ratings = state.capabilities.ratings)
        }
    }
    PlayerSheets(sheet, state, actions, bookmarks, onDismiss = { sheet = null })
    bookmarks?.let { BookmarkEditing(state, it, actions) }
}

/**
 * Why playback waits, or that another book could not be loaded over the one playing: that one offers
 * to try again when the book asked for is known, and can be dismissed, so that it does not stay over
 * the book playing.
 */
@Composable
private fun PlayerStatus(state: PlayerUiState, onRetry: () -> Unit) {
    var dismissed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.loadFailed) { if (!state.loadFailed) dismissed = false }
    when {
        state.bookId != null && state.loadFailed && !dismissed -> StatusMessage(
            text = stringResource(R.string.player_load_failed),
            onRetry = onRetry.takeIf { state.canRetryLoad },
            onDismiss = { dismissed = true },
        )
        state.offline -> StatusMessage(stringResource(R.string.player_offline))
        state.unreachable -> StatusMessage(stringResource(R.string.player_unreachable))
    }
}

/** Minimise, "Now playing", and the menu: the book's page, the player settings and "Stop listening". */
@Composable
private fun PlayerTopBar(
    bookId: BookId?,
    onMinimise: () -> Unit,
    onOpenBook: (BookId) -> Unit,
    onOpenSettings: () -> Unit,
    onStop: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onMinimise) {
            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = stringResource(R.string.player_minimise))
        }
        Text(
            text = stringResource(R.string.player_now_playing),
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.testTag(PlayerMenuTag)) {
                Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.player_more))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                bookId?.let { id ->
                    DropdownMenuItem(
                        text = { SingleLineLabel(stringResource(R.string.player_menu_book)) },
                        onClick = {
                            menu = false
                            onOpenBook(id)
                        },
                    )
                }
                DropdownMenuItem(
                    text = { SingleLineLabel(stringResource(R.string.player_menu_settings)) },
                    onClick = {
                        menu = false
                        onOpenSettings()
                    },
                )
                if (bookId != null) {
                    DropdownMenuItem(
                        text = { SingleLineLabel(stringResource(R.string.player_stop_listening)) },
                        onClick = {
                            menu = false
                            onStop()
                        },
                    )
                }
            }
        }
    }
}

/** The book, or what stands in its place: a first book being read, one that could not be, or none. */
@Composable
private fun PlayerBody(
    state: PlayerUiState,
    actions: PlayerActions,
    onSheet: (PlayerSheet) -> Unit,
    bookmarks: PlayerBookmarks?,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        when {
            state.bookId != null -> when (val layout = playerLayout(maxWidth, maxHeight)) {
                is PlayerLayout.Portrait -> PortraitPlayer(state, actions, onSheet, layout, bookmarks)
                is PlayerLayout.Landscape -> LandscapePlayer(state, actions, onSheet, layout, bookmarks)
            }
            state.loading -> {
                val loading = stringResource(R.string.player_loading)
                CircularProgressIndicator(
                    Modifier
                        .testTag(PlayerLoadingTag)
                        .semantics { contentDescription = loading },
                )
            }
            else -> NoBook(state.loadFailed, actions::retryLoad.takeIf { state.canRetryLoad })
        }
    }
}

/** Nothing playing, or a first book that could not be loaded, which [onRetry] tries again when it is known. */
@Composable
private fun NoBook(failed: Boolean, onRetry: (() -> Unit)?) {
    Column(
        modifier = Modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(if (failed) R.string.player_load_failed else R.string.player_nothing),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        if (failed && onRetry != null) {
            Button(onClick = onRetry, modifier = Modifier.testTag(PlayerRetryTag)) {
                SingleLineLabel(stringResource(R.string.player_load_retry))
            }
        }
    }
}

/** The sheet open, if any; choosing a chapter or a bookmark there goes to it, with "Go back". */
@Composable
private fun PlayerSheets(
    sheet: PlayerSheet?,
    state: PlayerUiState,
    actions: PlayerActions,
    bookmarks: PlayerBookmarks?,
    onDismiss: () -> Unit,
) {
    when (sheet) {
        PlayerSheet.Chapters -> ChaptersSheet(
            state = state,
            onChoose = actions::goToChapter,
            onDismiss = {
                bookmarks?.sheetClosed()
                onDismiss()
            },
            bookmarks = bookmarks,
        )
        PlayerSheet.Speed -> SpeedSheet(state, actions::setSpeed, onDismiss)
        PlayerSheet.Sleep -> SleepSheet(state, actions, actions, onDismiss)
        null -> Unit
    }
}
