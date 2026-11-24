package app.tfdn.lirelle.feature.reader

import androidx.compose.ui.draw.alpha
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tfdn.lirelle.core.designsystem.LocalWindowWidthClass
import app.tfdn.lirelle.core.designsystem.WindowWidthClass
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.reader.settings.ReaderSettings
import app.tfdn.lirelle.core.reader.settings.RunningSlot
import app.tfdn.lirelle.feature.player.narration.NarrationActions
import app.tfdn.lirelle.feature.reader.narration.ControlsNarrationDock
import app.tfdn.lirelle.feature.reader.narration.NarrationWaysBack
import app.tfdn.lirelle.feature.reader.narration.NarrationPlace
import app.tfdn.lirelle.feature.reader.narration.ReaderNarrationUi
import app.tfdn.lirelle.feature.reader.narration.ReaderNarrationOverlays
import app.tfdn.lirelle.feature.reader.narration.narrationDocked
import app.tfdn.lirelle.feature.reader.narration.rememberNarrationPlace
import app.tfdn.lirelle.feature.reader.narration.rememberNarrationStop
import app.tfdn.lirelle.feature.reader.bridge.PageNoteLabels
import app.tfdn.lirelle.feature.reader.web.ReaderWebView
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlin.math.roundToInt

internal const val ReaderPageTag = "reader-page"

/** The bottom bar does not stretch over a wide window. */
private val BottomBarMaxWidth = 720.dp

/**
 * The reader of one book, on the whole screen.
 *
 * @param onLeave the reader is left for the screen it was opened from.
 * @param onOpenBook the reader is left for the page of this book (the next volume).
 * @param sheets the settings sheets, given the state and the actions; they are drawn in the
 *   colours of the reading theme.
 * @param endPanel the panel at the end of the book, likewise.
 */
@Composable
fun ReaderScreen(
    viewModel: ReaderViewModel,
    onLeave: () -> Unit,
    onOpenBook: (BookId) -> Unit,
    modifier: Modifier = Modifier,
    sheets: @Composable (ReaderUiState, ReaderActions) -> Unit = { _, _ -> },
    endPanel: @Composable (ReaderUiState, ReaderActions) -> Unit = { _, _ -> },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ReaderEvents(viewModel.events, onLeave, onOpenBook)
    ReaderHostReports(viewModel)
    ReaderWindowEffects(state, viewModel, onLeave = viewModel::leave)
    ReaderContent(
        state = state,
        actions = viewModel,
        engineSlot = { slot ->
            ReaderWebView(
                book = (state.phase as? ReaderPhase.Reading)?.content,
                onEngine = viewModel::onEngine,
                modifier = slot,
                background = state.colours.background,
            )
        },
        modifier = modifier,
        sheets = { sheets(state, viewModel) },
        endPanel = { endPanel(state, viewModel) },
        device = deviceReadings(
            settings = state.settings,
            offered = state.newerPosition,
            panelOpen = state.openSheet == ReaderSheet.Contents,
        ),
        narrationControls = viewModel.narrationControls,
    )
}

/** What the reader asks once: to be left, or a web address to be opened. */
@Composable
private fun ReaderEvents(events: Flow<ReaderEvent>, onLeave: () -> Unit, onOpenBook: (BookId) -> Unit) {
    val context = LocalContext.current
    val leave by rememberUpdatedState(onLeave)
    val openBook by rememberUpdatedState(onOpenBook)
    LaunchedEffect(events, context) {
        events.collect { event ->
            when (event) {
                ReaderEvent.Leave -> leave()
                is ReaderEvent.OpenBook -> openBook(event.bookId)
                is ReaderEvent.OpenUrl -> context.openInBrowser(event.url)
            }
        }
    }
}

/** A device without a browser says so instead of failing. */
internal fun Context.openInBrowser(url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addCategory(Intent.CATEGORY_BROWSABLE)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, R.string.reader_no_browser, Toast.LENGTH_LONG).show()
    }
}

/** What the reader has to know of the screen around it: in front or not, dark or light, wide or not. */
@Composable
private fun ReaderHostReports(host: ReaderHostEvents) {
    LifecycleResumeEffect(host) {
        host.onResumed()
        onPauseOrDispose { host.onPaused() }
    }
    val dark = isSystemInDarkTheme()
    LaunchedEffect(host, dark) { host.onSystemDark(dark) }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val wide = LocalWindowWidthClass.current == WindowWidthClass.Expanded && landscape
    LaunchedEffect(host, wide) { host.onWindow(wide) }
    val labels = PageNoteLabels(
        goTo = stringResource(R.string.reader_note_go_to),
        title = stringResource(R.string.reader_note_title),
    )
    LaunchedEffect(host, labels) { host.onNoteLabels(labels) }
    val markLabels = MarkLabels(bookmarkAt = stringResource(R.string.reader_bookmark_at))
    LaunchedEffect(host, markLabels) { host.onMarkLabels(markLabels) }
}

/**
 * The time and the battery, each followed only while a slot shows it, and the moment a newer
 * position is offered or the panel opened, from which the age of the position and the days of the
 * marks are told.
 */
@Composable
private fun deviceReadings(settings: ReaderSettings, offered: OfferedPosition?, panelOpen: Boolean): DeviceReadings {
    val slots = listOf(settings.header.left, settings.header.right, settings.footer.left, settings.footer.right)
    return DeviceReadings(
        time = if (RunningSlot.Time in slots) rememberTimeText() else null,
        batteryPercent = if (RunningSlot.Battery in slots) rememberBatteryPercent() else null,
        now = if (offered == null && !panelOpen) null else remember(offered, panelOpen) { Instant.now() },
    )
}

/**
 * Everything the reader shows but the page itself, the settings sheets and the end panel, which
 * come through [engineSlot], [sheets] and [endPanel]. All of it is in the colours of the reading
 * theme, the slots included.
 *
 * @param engineSlot the web view, to be given the modifier. It is composed in every phase, under
 *   what the phase shows, so that the page is ready when the book is.
 * @param device the time and the battery for the header and the footer, and the moment it is for
 *   the age of a newer position and the days of the marks in the panel; nothing unless given, the
 *   panel then telling the days from the moment it opened.
 * @param narrationControls what the narration's bar and panel ask of the player; without them no
 *   narration's bar is shown.
 */
@Composable
fun ReaderContent(
    state: ReaderUiState,
    actions: ReaderActions,
    engineSlot: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
    sheets: @Composable () -> Unit = {},
    endPanel: @Composable () -> Unit = {},
    device: DeviceReadings = DeviceReadings(),
    narrationControls: NarrationActions? = null,
) {
    // The controls' bars would cover the page where a selection's bar goes: the reader hides them
    // when a selection starts, and they are never drawn while there is one.
    val shown = if (state.selection != null && state.controlsShown) state.copy(controlsShown = false) else state
    var bottomChromeHeight by remember { mutableIntStateOf(0) }
    var footerHeight by remember { mutableIntStateOf(0) }
    var searchBarRoom by remember { mutableIntStateOf(0) }
    var headerCentre by remember { mutableIntStateOf(0) }
    var narrationPanel by rememberSaveable { mutableStateOf(false) }
    val narration = rememberNarrationPlace(
        controls = narrationControls,
        state = shown,
        onOpenPanel = { narrationPanel = true },
    )
    val stopNarration = rememberNarrationStop(actions, narration)
    val searchBar = SearchBarPlace(
        // With the controls, the bar rises from its room to sit on their bars (the narration's among them),
        // which cover the footer.
        lift = { if (shown.controlsShown) (bottomChromeHeight - footerHeight).coerceAtLeast(0) else 0 },
        onRoom = { searchBarRoom = it },
    )
    ReaderChromeTheme(shown.colours) {
        Surface(
            modifier = modifier
                .fillMaxSize()
                // While the highlight's panel is open a screen reader stays in it.
                .then(if (shown.highlightPanel != null) Modifier.clearAndSetSemantics {} else Modifier),
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
        ) {
            Box {
                PageWithRunningLines(
                    shown,
                    actions,
                    engineSlot,
                    device,
                    searchBar,
                    onHeaderCentre = { headerCentre = it },
                ) { footerHeight = it }
                if (shown.phase is ReaderPhase.Reading) {
                    Column(Modifier.align(Alignment.TopCenter)) {
                        TopChrome(shown, actions, device.now, onOpenNarration = narration.onOpenPanel)
                        // Over the page, under the top bar: the page keeps its height as they come and go.
                        NarrationWaysBack(shown, actions, narration, headerCentre)
                    }
                    BottomChrome(
                        shown,
                        actions,
                        footerHeight,
                        narration,
                        stopNarration,
                        Modifier
                            .align(Alignment.BottomCenter)
                            .onSizeChanged { bottomChromeHeight = it.height },
                    )
                }
                if (shown.adjustingBrightness) {
                    // In the band above the text, in place of the header: it hides no line of the page.
                    BrightnessIndicator(
                        shown.settings.brightness ?: 0f,
                        Modifier
                            .align(Alignment.TopCenter)
                            .windowInsetsPadding(WindowInsets.displayCutout),
                    )
                }
            }
        }
        ReaderNarrationOverlays(
            state = shown,
            actions = actions,
            place = narration,
            heights = bottomChromeHeight to searchBarRoom,
            panelOpen = narrationPanel,
            onClosePanel = { narrationPanel = false },
        )
        PanelWindows(shown, actions, device.now)
        shown.externalLink?.let { ExternalLinkDialog(it, actions::confirmExternalLink, actions::dismissExternalLink) }
        sheets()
        endPanel()
        shown.highlightPanel?.let { HighlightPanel(it, actions) }
        // Last, above the sheets drawn in the reader's window, the highlight's panel, the bottom bar,
        // the search's bar and "Go back". The panel of contents, bookmarks and notes and the search's
        // panel are windows of their own, which would cover it: while one is open, the offer is shown in it.
        // Nor over a footnote's panel, which the page draws over everything of its own.
        if (!shown.panelWindowOpen && !shown.noteOpen) {
            val searchLift = if (shown.searchBarOnPage) searchBarRoom else 0
            shown.undo?.let { UndoOverlay(it, actions, bottomChromeHeight + searchLift) }
        }
    }
}

/**
 * The header, the page, the search's bar and the footer, clear of the display's cut-outs. Like the
 * footer, the bar takes its own room: the page is laid out above it, and no line of text is covered.
 * "Go back" is in the footer, or in the search's bar while it is shown.
 *
 * @param onHeaderCentre the middle of the header, in pixels from the top, for the narration's ways back.
 * @param onFooterHeight the footer's height, nothing when it shows nothing, for what stacks above it.
 */
@Composable
private fun PageWithRunningLines(
    state: ReaderUiState,
    actions: ReaderActions,
    engineSlot: @Composable (Modifier) -> Unit,
    device: DeviceReadings,
    searchBar: SearchBarPlace,
    onHeaderCentre: (Int) -> Unit,
    onFooterHeight: (Int) -> Unit,
) {
    val shown = state.phase is ReaderPhase.Reading && state.opened
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.displayCutout),
    ) {
        if (shown) {
            RunningHeader(
                state,
                device,
                Modifier
                    .alpha(if (state.adjustingBrightness) 0f else 1f)
                    .onGloballyPositioned { onHeaderCentre(it.boundsInRoot().center.y.roundToInt()) },
            )
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .then(if (shown) pageSemantics(state, actions) else Modifier),
        ) {
            engineSlot(Modifier.fillMaxSize())
            when (val phase = state.phase) {
                is ReaderPhase.Downloading ->
                    DownloadingView(state.title, state.coverUrl, phase.progress, onCancel = actions::leave)
                is ReaderPhase.Failed -> FailureView(phase.reason, onRetry = actions::retry, onBack = actions::leave)
                is ReaderPhase.Reading -> if (!state.opened) OpeningCover(state.title, state.coverUrl)
            }
            // At the page's top-right corner, which is the right-hand page's with two side by side.
            if (shown && state.ribbonShown) {
                Ribbon(state.colours.ribbon, Modifier.align(AbsoluteAlignment.TopRight))
            }
            // Laid out in the page's own area, which the selection's bounds are fractions of.
            val selection = state.selection
            if (shown && selection != null) {
                SelectionBar(
                    selection = selection,
                    offered = state.textActions,
                    current = state.selectionColour(selection),
                    actions = actions,
                    onReadFromHere = actions::readFromSelection.takeIf { state.narrationOffered },
                    annotations = state.capabilities.annotations,
                )
            }
        }
        SearchBarRoom(state, actions, searchBar, Modifier.align(Alignment.CenterHorizontally))
        // The last of the column: a narration has no room under the page, whose height it never changes.
        Box(Modifier.fillMaxWidth().onSizeChanged { onFooterHeight(it.height) }) {
            if (shown) RunningFooter(state, device, actions::goBack)
        }
    }
}

/**
 * What a finger does on the page, offered to a screen reader as actions. The hints name the edge
 * to tap, which is the other one in a right-to-left book; the turns themselves follow the book.
 */
@Composable
private fun pageSemantics(state: ReaderUiState, actions: ReaderActions): Modifier {
    val page = stringResource(R.string.reader_page)
    val controls = stringResource(
        if (state.controlsShown) R.string.reader_hide_controls else R.string.reader_show_controls,
    )
    val next = stringResource(if (state.rtl) R.string.reader_next_page_left else R.string.reader_next_page_right)
    val previous = stringResource(
        if (state.rtl) R.string.reader_previous_page_right else R.string.reader_previous_page_left,
    )
    return Modifier.testTag(ReaderPageTag).semantics {
        contentDescription = page
        customActions = listOf(
            CustomAccessibilityAction(controls) {
                actions.toggleControls()
                true
            },
            CustomAccessibilityAction(next) {
                actions.nextPage()
                true
            },
            CustomAccessibilityAction(previous) {
                actions.previousPage()
                true
            },
        )
    }
}

/** The top bar while the controls are shown, and the banner of a newer position under it. */
@Composable
private fun TopChrome(
    state: ReaderUiState,
    actions: ReaderActions,
    now: Instant?,
    modifier: Modifier = Modifier,
    onOpenNarration: () -> Unit = {},
) {
    val offered = state.newerPosition
    if (!state.controlsShown && offered == null) return
    Surface(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            ),
        ) {
            if (state.controlsShown) {
                ReaderTopBar(
                    title = state.title,
                    bookmarked = state.ribbonShown,
                    actions = actions,
                    narration = state.narration.takeIf { state.capabilities.readAlong } ?: ReaderNarrationUi(),
                    onOpenNarration = onOpenNarration,
                    bookmarks = state.capabilities.annotations,
                )
            }
            if (offered != null) {
                PositionBanner(offered, now, actions::acceptNewerPosition, actions::ignoreNewerPosition)
            }
        }
    }
}

/**
 * At the bottom of the page while the controls are shown, from the top: "Go back" (not while a
 * footnote's panel is there, nor while the search's bar is shown, which holds it), and the bottom
 * bar, which covers the footer. Without the controls only room, [footerHeight] pixels high above the
 * system's bars, for what stacks above the footer. The search's bar has a room of its own above it.
 *
 * While the book is narrated, the narration's bar is the last of the controls, under the bottom bar,
 * and keeps clear of the system's bars itself: it comes and goes with the controls, over the page.
 */
@Composable
private fun BottomChrome(
    state: ReaderUiState,
    actions: ReaderActions,
    footerHeight: Int,
    narration: NarrationPlace,
    onStopNarration: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val docked = state.controlsShown && state.narrationDocked(narration)
    val bottom = if (docked) {
        WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
    } else {
        WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
    }
    val sides = Modifier
        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
        .padding(bottom = 12.dp)
    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (state.controlsShown) {
            if (state.goBackOffered && !state.noteOpen) GoBackButton(actions::goBack, sides)
            Surface(modifier = Modifier.fillMaxWidth()) {
                Box(modifier = Modifier.windowInsetsPadding(bottom), contentAlignment = Alignment.Center) {
                    ReaderBottomBar(state.location, actions, Modifier.widthIn(max = BottomBarMaxWidth))
                }
            }
            if (docked) {
                // A hairline between the reader's own controls and the narration's, which are two things.
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                ControlsNarrationDock(state, actions, narration, onStopNarration)
            }
        } else {
            val footer = with(LocalDensity.current) { footerHeight.toDp() }
            Spacer(Modifier.windowInsetsPadding(bottom).height(footer))
        }
    }
}
