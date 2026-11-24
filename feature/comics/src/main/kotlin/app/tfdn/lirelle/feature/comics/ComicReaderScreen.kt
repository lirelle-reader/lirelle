package app.tfdn.lirelle.feature.comics

import android.content.pm.ApplicationInfo
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.feature.comics.end.ComicEndSheet
import app.tfdn.lirelle.feature.comics.end.EndedBook
import app.tfdn.lirelle.feature.comics.page.ComicSpreadView
import app.tfdn.lirelle.feature.comics.page.SpreadColours
import app.tfdn.lirelle.feature.comics.page.colours

/** How long the bars take to appear and to disappear. */
private const val BarFadeMillis = 150

/**
 * The comics reader: the pages of the album [viewModel] holds, its bars over them, its settings sheet, and the
 * sheet at the end of the album. While it is shown the screen stays on, and the system bars are hidden with
 * its own bars and shown with them; both are as before when it is left. Back leaves.
 *
 * @param onLeave the reader is left, what was read being written.
 * @param onOpenNext the next album of the series is to be opened in the reader, in place of this one, what
 *   was read of this one being written.
 */
@Composable
fun ComicReaderScreen(
    viewModel: ComicReaderViewModel,
    onLeave: () -> Unit,
    onOpenNext: (bookId: BookId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ComicReaderEvents(viewModel.events, onLeave, onOpenNext)
    val context = LocalContext.current
    val debuggable = remember(context) { context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 }
    ComicHostReports(viewModel, debuggable)
    val barsOver = state.barsShown || state.phase != ComicPhase.Ready
    ComicWindowEffects(
        barsOver = barsOver,
        darkGround = darkUnderSystemBars(
            barsOver = barsOver,
            bar = MaterialTheme.colorScheme.surface,
            page = state.settings.background.colours().background,
        ),
    )
    ComicReaderContent(state, viewModel, modifier, debuggable)
}

/**
 * What the comics reader shows of [state], in the theme it is shown in: a progress mark while
 * the album opens, with how far its fetch is for a document fetched whole, why it could not be opened, or its
 * pages with the bars over them. The bars never resize the
 * pages. Back, the system's or the bar's, leaves. The sheet at the end of the album is over it all while the
 * state has its panel; back then dismisses the sheet.
 *
 * @param debuggable whether the build can be debugged: its settings then offer to draw the zones of the pages.
 */
@Composable
fun ComicReaderContent(
    state: ComicReaderUiState,
    actions: ComicReaderActions,
    modifier: Modifier = Modifier,
    debuggable: Boolean = false,
) {
    BackHandler(onBack = actions::leave)
    val colours = state.settings.background.colours()
    Box(modifier.fillMaxSize().background(colours.background)) {
        when (val phase = state.phase) {
            ComicPhase.Ready -> OpenAlbum(state, actions, debuggable)
            else -> Opening(state, phase, colours, actions)
        }
        state.endPanel?.let { ComicEndSheet(it, EndedBook(state.title, state.kind), actions) }
    }
}

/** The album is not open: the top bar, with back alone, and under it the progress mark or what went wrong. */
@Composable
private fun Opening(state: ComicReaderUiState, phase: ComicPhase, colours: SpreadColours, actions: ComicReaderActions) {
    Column(Modifier.fillMaxSize()) {
        ComicTopBar(state, onBack = actions::leave, onSettings = null)
        when (phase) {
            is ComicPhase.Failed ->
                ComicOpeningFailed(phase.reason, colours.content, actions::retryOpening)
            else -> ComicLoading(colours.content, fetch = state.opening)
        }
    }
}

/**
 * The pages, filling the screen, and over them the bars while they are shown, the offer of a newer page or what
 * an album that cannot be cut says, and the settings sheet once its button was pressed, with the switch of the
 * zones in a build that is [debuggable];
 * over it all, what reading panel by panel is, the first time it is turned on.
 */
@Composable
private fun BoxScope.OpenAlbum(state: ComicReaderUiState, actions: ComicReaderActions, debuggable: Boolean) {
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    ComicSpreadView(state, navigation = actions, onToggleBars = actions::toggleBars, onHideBars = actions::hideBars)
    Bar(state.barsShown, Modifier.align(Alignment.TopCenter)) {
        ComicTopBar(
            state = state,
            onBack = actions::leave,
            onSettings = { settingsOpen = true },
            onPanelMode = actions::setPanelMode,
        )
    }
    Column(Modifier.align(Alignment.BottomCenter)) {
        Banner(state, actions)
        Bar(state.barsShown) { ComicBottomBar(state, navigation = actions) }
    }
    if (settingsOpen) {
        val onZones = (actions::showZones).takeIf { debuggable }
        ComicSettingsSheet(state, actions, onDismiss = { settingsOpen = false }, onZones = onZones)
    }
    if (state.panels.notice) PanelNoticeDialog(onClose = actions::closePanelNotice)
}

/**
 * What is said over the bottom of the page: the offer of a newer page, else what an album that lends itself
 * badly to reading panel by panel says. One at a time: the album waits for the newer page to be answered.
 */
@Composable
private fun Banner(state: ComicReaderUiState, actions: ComicReaderActions) {
    val newer = state.newerPage
    val insets = bannerInsets(state.barsShown)
    when {
        newer != null -> NewerPageBanner(newer, actions, insets)
        state.panels.albumBanner -> AlbumBanner(actions, insets)
    }
}

/** A bar that appears and disappears with a short fade. */
@Composable
private fun Bar(shown: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = shown,
        modifier = modifier,
        enter = fadeIn(tween(BarFadeMillis)),
        exit = fadeOut(tween(BarFadeMillis)),
    ) {
        content()
    }
}

/** What is said there keeps clear of the window's sides, and of its bottom when the bottom bar is not under it. */
@Composable
private fun bannerInsets(barUnder: Boolean): WindowInsets {
    val sides = if (barUnder) WindowInsetsSides.Horizontal else WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
    return WindowInsets.safeDrawing.only(sides)
}
