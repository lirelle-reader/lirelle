package app.tfdn.lirelle.feature.reader.narration

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.feature.player.narration.NarrationActions
import app.tfdn.lirelle.feature.player.narration.NarrationBar
import app.tfdn.lirelle.feature.player.narration.NarrationColors
import app.tfdn.lirelle.feature.player.narration.NarrationEndedBar
import app.tfdn.lirelle.feature.player.narration.NarrationNotice
import app.tfdn.lirelle.feature.player.narration.NarrationPanel
import app.tfdn.lirelle.core.designsystem.SingleLineLabel
import app.tfdn.lirelle.feature.reader.R
import app.tfdn.lirelle.feature.reader.ReaderUiState
import app.tfdn.lirelle.feature.reader.searchBarOnPage
import app.tfdn.lirelle.feature.player.R as PlayerR

internal const val NarrationDockTag = "reader-narration-dock"
internal const val WaveButtonTag = "reader-wave-button"
internal const val ReadFromHereTag = "reader-read-from-here"
internal const val NarrationProblemTag = "reader-narration-problem"
internal const val NarrationSwipeTag = "reader-narration-swipe"

/** Behind the wave button while the book is narrated: the accent, faint. */
private const val WaveTint = 0.16f

private val ButtonSize = 48.dp
private val RowWaveSize = 18.dp
private val ProblemCorner = 12.dp
private val ProblemElevation = 4.dp

/** How far across its width the narration's bar is swiped before it goes, as the mini-player. */
private const val DismissFraction = 0.4f

/** The width, in dp at the default text size, from which a snackbar's action fits beside its message. */
private const val RoomyWidth = 400

/**
 * At the very bottom of the reader's controls while the book is narrated: the narration's bar, or the bar that
 * says it ended. One of the controls, over the page as they are: it takes no room of the page's, so that a
 * narration starting or stopping never has the text laid out again. Below it, the room of the navigation bar.
 *
 * While the narration does not play (paused, or ended), its bar is swiped away sideways to stop listening
 * ([onStop]); while it plays it stays, being where the pages are turned by a swipe.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NarrationDock(
    narration: ReaderNarrationUi,
    colors: NarrationColors,
    actions: ReaderNarrationActions,
    controls: NarrationActions,
    wide: Boolean,
    onOpenPanel: () -> Unit,
    modifier: Modifier = Modifier,
    onStop: () -> Unit = {},
) {
    // A narration that could not be loaded has nothing to show in a bar: its failure is told over the page.
    val active = narration.active?.takeIf { it.loaded } ?: return
    val navigation = WindowInsets.navigationBarsIgnoringVisibility
        .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.background)
            .windowInsetsPadding(navigation)
            .testTag(NarrationDockTag),
    ) {
        StopBySwipe(enabled = narration.ended || !active.playing, onStop = onStop) {
            if (narration.ended) {
                NarrationEndedBar(colors, onFinishBook = actions::finishBook)
            } else {
                NarrationBar(active, colors, controls, onOpenPanel = onOpenPanel, wide = wide)
            }
        }
    }
}

/** [content], which a sideways swipe past [DismissFraction] of its width takes away, calling [onStop], if [enabled]. */
@Composable
private fun StopBySwipe(enabled: Boolean, onStop: () -> Unit, content: @Composable () -> Unit) {
    if (!enabled) {
        content()
        return
    }
    // Made afresh each time the swipe is allowed: a bar that comes back does not come back swiped.
    val swipe = rememberSwipeToDismissBoxState(positionalThreshold = { width -> width * DismissFraction })
    SwipeToDismissBox(
        state = swipe,
        backgroundContent = {},
        modifier = Modifier.testTag(NarrationSwipeTag),
        onDismiss = { onStop() },
    ) {
        content()
    }
}

/**
 * Over the page, [lift] above the bottom of the reader (the narration's bar, or the controls' bar on it): a
 * section the narration skipped, and "Narration unavailable · Try again" or "Listening stopped · Undo" in
 * [snackbars]. The narration's panel, when [panelOpen].
 */
@Composable
internal fun NarrationOverlays(
    narration: ReaderNarrationUi,
    colors: NarrationColors,
    actions: ReaderNarrationActions,
    controls: NarrationActions,
    lift: Dp,
    panelOpen: Boolean,
    onClosePanel: () -> Unit,
    snackbars: SnackbarHostState = remember { SnackbarHostState() },
) {
    val message = stringResource(R.string.reader_narration_unavailable)
    val retry = stringResource(R.string.reader_narration_retry)
    // Once per failure: not again when the screen is made anew (a rotation) while the narration is still unavailable.
    var told by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(narration.unavailable) {
        if (!narration.unavailable) {
            told = false
            return@LaunchedEffect
        }
        if (told) return@LaunchedEffect
        told = true
        val answer = snackbars.showSnackbar(message, actionLabel = retry, duration = SnackbarDuration.Long)
        if (answer == SnackbarResult.ActionPerformed) actions.retryNarration()
    }
    Box(Modifier.fillMaxSize().padding(bottom = lift), contentAlignment = Alignment.BottomCenter) {
        Column(
            modifier = Modifier.padding(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            NarrationProblem(narration, colors, actions::retryNarration)
            narration.notice?.let { notice ->
                NarrationNotice(
                    notice = notice,
                    colors = colors,
                    onRead = { actions.readSkipped(notice) },
                    onTimeout = { actions.dismissNotice(notice) },
                )
            }
            SnackbarHost(snackbars) { data ->
                // On a narrow window or at a large text size, "Try again" goes under the message rather than
                // squeeze it.
                val roomy = LocalConfiguration.current.screenWidthDp / LocalDensity.current.fontScale >= RoomyWidth
                Snackbar(data, actionOnNewLine = !roomy)
            }
        }
    }
    val active = narration.active?.takeIf { it.loaded }
    // A narration that went away takes its panel with it; another one does not open it again.
    LaunchedEffect(active == null) { if (active == null) onClosePanel() }
    if (panelOpen && active != null) NarrationPanel(active, colors, controls, onDismiss = onClosePanel)
}

/**
 * Why this book's narration does not play, as the player says it (spec 11): it could not be loaded (held, or
 * asked while another book's is held), an error stopped it (both with "Try again", [onRetry]), it waits for the
 * network, or the server cannot be reached. Nothing when it plays as it should.
 */
@Composable
private fun NarrationProblem(narration: ReaderNarrationUi, colors: NarrationColors, onRetry: () -> Unit) {
    val active = narration.active
    val (text, retry) = when {
        narration.loadFailed || active?.loadFailed == true -> PlayerR.string.player_load_failed to true
        active?.error == true -> R.string.reader_narration_error to true
        active?.offline == true -> PlayerR.string.player_offline to false
        active?.unreachable == true -> PlayerR.string.player_unreachable to false
        else -> return
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .testTag(NarrationProblemTag),
        shape = RoundedCornerShape(ProblemCorner),
        color = colors.foreground,
        contentColor = colors.background,
        shadowElevation = ProblemElevation,
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(text),
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 10.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (retry) {
                TextButton(
                    onClick = onRetry,
                    modifier = Modifier.heightIn(min = ButtonSize),
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.background),
                ) {
                    SingleLineLabel(stringResource(R.string.reader_narration_retry))
                }
            }
        }
    }
}

/**
 * In the top bar while the file has a narration: starts it from the page shown; while the book is narrated it is
 * tinted with [accent], and opens the narration's panel.
 */
@Composable
internal fun WaveButton(active: Boolean, accent: Color, onStart: () -> Unit, onOpenPanel: () -> Unit) {
    val label = stringResource(if (active) R.string.reader_narration_playing else R.string.reader_narration_start)
    // While narrated, a screen reader hears what the button does now: it opens the controls.
    val action = if (active) stringResource(R.string.reader_narration_open_controls) else null
    Box(
        modifier = Modifier
            .size(ButtonSize)
            .clip(CircleShape)
            .background(if (active) accent.copy(alpha = WaveTint) else Color.Transparent)
            .clickable(onClickLabel = action, role = Role.Button, onClick = if (active) onOpenPanel else onStart)
            .testTag(WaveButtonTag),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.GraphicEq,
            contentDescription = label,
            tint = if (active) accent else LocalContentColor.current,
        )
    }
}

/** The first row of the selection's card when the file has a narration: "Read from here", the wave in [accent]. */
@Composable
internal fun ReadFromHereRow(accent: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = ButtonSize)
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .testTag(ReadFromHereTag),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.GraphicEq, contentDescription = null, Modifier.size(RowWaveSize), tint = accent)
        Text(stringResource(R.string.reader_read_from_here), style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * [NarrationOverlays] above everything at the bottom of the reader: the narration's bar, the footer or the
 * controls' bar over it, and the search's bar. [heights] are those of the bottom of the reader and of the
 * search's bar's room, in pixels.
 */
@Composable
internal fun ReaderNarrationOverlays(
    state: ReaderUiState,
    actions: ReaderNarrationActions,
    place: NarrationPlace,
    heights: Pair<Int, Int>,
    panelOpen: Boolean,
    onClosePanel: () -> Unit,
) {
    val controls = place.controls ?: return
    val (bottomChrome, searchBar) = heights
    val lift = with(LocalDensity.current) { (bottomChrome + if (state.searchBarOnPage) searchBar else 0).toDp() }
    NarrationOverlays(state.narration, place.colors, actions, controls, lift, panelOpen, onClosePanel, place.snackbars)
}
