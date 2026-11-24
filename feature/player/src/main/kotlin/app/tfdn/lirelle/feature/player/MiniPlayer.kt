package app.tfdn.lirelle.feature.player

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tfdn.lirelle.core.audio.timer.SleepChoice
import app.tfdn.lirelle.core.audio.timer.SleepState
import app.tfdn.lirelle.core.designsystem.SingleLineLabel
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.model.CoverShape
import app.tfdn.lirelle.core.ui.BookCover
import app.tfdn.lirelle.feature.player.controller.NarrationUi
import app.tfdn.lirelle.feature.player.controller.PlayerUiState
import app.tfdn.lirelle.feature.player.controller.timeLeft
import kotlinx.coroutines.launch

internal const val MiniPlayerTag = "mini-player"
internal const val MiniPlayerProgressTag = "mini-player-progress"
internal const val MiniPlayerNarrationTag = "mini-player-narration"

private val MiniPlayerShape = RoundedCornerShape(14.dp)
private val MiniPlayerMinHeight = 64.dp
private val MiniCoverSize = 48.dp
private val MiniButtonSize = 48.dp
private val ProgressHeight = 3.dp
private val NarrationGlyphSize = 16.dp
private const val Percent = 100.0

/** How far across its width the bar is swiped before it goes. */
private const val DismissFraction = 0.4f

/**
 * The book loaded, in a bar above the navigation (spec 5.2): tapped, it opens the player. Nothing is
 * shown while no book is loaded. A finished book opens the player on its end panel, from the bar as
 * from "Rate" (spec 6). Swiped away, or by its accessibility action, it stops listening: [snackbars]
 * then says so, with "Undo". A narration (a read-along book's audio) opens the reader on its book
 * instead ([onOpenReader]).
 */
@Composable
fun MiniPlayer(
    viewModel: MiniPlayerViewModel,
    onOpenPlayer: () -> Unit,
    onOpenReader: (bookId: BookId) -> Unit,
    snackbars: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val narration by viewModel.narration.collectAsStateWithLifecycle()
    // The bar goes once the book is closed; this scope, the shell's, keeps the snackbar's wait.
    val scope = rememberCoroutineScope()
    val stopped = stringResource(R.string.player_stopped)
    val undo = stringResource(R.string.player_stopped_undo)
    MiniPlayerContent(
        state = state,
        onOpenPlayer = onOpenPlayer,
        onPlayPause = viewModel::playPause,
        onBack = viewModel::back,
        onRate = {
            viewModel.openEndPanel()
            onOpenPlayer()
        },
        modifier = modifier,
        narration = narration,
        onOpenReader = onOpenReader,
        onStop = {
            viewModel.stopListening()
            scope.launch {
                val answer = snackbars.showSnackbar(stopped, actionLabel = undo, duration = SnackbarDuration.Long)
                if (answer == SnackbarResult.ActionPerformed) viewModel.undoStop()
            }
        },
    )
}

/**
 * The cover, the title, the chapter with its time left (or the sleep timer while it runs), back and
 * play or pause, over a thin line of the book's progress. A finished book says so and offers "Rate",
 * which the bar itself does then too. Swiped sideways past [DismissFraction] of its width, either way,
 * it goes and [onStop] is called; a screen reader has the same as an action of the bar.
 *
 * During a [narration], the line under the title is the wave and "Narration · section", or "Narration
 * finished" at its end (no rating: a narration is not the audiobook), and the bar opens the reader on the
 * book ([onOpenReader]).
 */
@Composable
internal fun MiniPlayerContent(
    state: PlayerUiState,
    onOpenPlayer: () -> Unit,
    onPlayPause: () -> Unit,
    onBack: () -> Unit,
    onRate: () -> Unit,
    modifier: Modifier = Modifier,
    onStop: () -> Unit = {},
    narration: NarrationUi? = null,
    onOpenReader: (bookId: BookId) -> Unit = {},
) {
    val bookId = state.bookId ?: return
    // Made afresh each time a book shows: a bar swiped away does not come back swiped.
    val swipe = rememberSwipeToDismissBoxState(positionalThreshold = { width -> width * DismissFraction })
    SwipeToDismissBox(
        state = swipe,
        // Nothing behind the bar: it slides away.
        backgroundContent = {},
        modifier = modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
        onDismiss = { onStop() },
    ) {
        // From the player's state alone: a narration never opens the player, even before its own state shows.
        val tap = when {
            state.narrating -> MiniTap(R.string.player_mini_open_reader) { onOpenReader(bookId) }
            // A finished book opens on its end panel, to be rated; from a source without ratings, to be closed.
            state.finished != null && state.capabilities.ratings -> MiniTap(R.string.player_mini_rate_book, onRate)
            state.finished != null -> MiniTap(R.string.player_mini_open, onRate)
            else -> MiniTap(R.string.player_mini_open, onOpenPlayer)
        }
        MiniPlayerCard(state, narration, tap, onPlayPause, onBack, onRate, onStop)
    }
}

/** What a tap on the bar does, and what a screen reader says it does. */
private class MiniTap(@StringRes val label: Int, val action: () -> Unit)

/** The bar itself, with "Stop listening" as an accessibility action. */
@Composable
private fun MiniPlayerCard(
    state: PlayerUiState,
    narration: NarrationUi?,
    tap: MiniTap,
    onPlayPause: () -> Unit,
    onBack: () -> Unit,
    onRate: () -> Unit,
    onStop: () -> Unit,
) {
    val colours = MaterialTheme.colorScheme
    val stopLabel = stringResource(R.string.player_stop_listening)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MiniPlayerShape)
            .background(colours.surfaceContainerHigh)
            .clickable(onClickLabel = stringResource(tap.label), role = Role.Button, onClick = tap.action)
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction(stopLabel) {
                        onStop()
                        true
                    },
                )
            }
            .testTag(MiniPlayerTag),
    ) {
        CompositionLocalProvider(LocalContentColor provides colours.onSurface) {
            Row(
                modifier = Modifier
                    .heightIn(min = MiniPlayerMinHeight)
                    .padding(start = 8.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The title is beside it: a book without a cover shows a plain tile.
                BookCover(
                    // The small cover: the bar does not decode the full one.
                    url = state.artworkUrl ?: state.coverUrl,
                    title = state.title,
                    shape = CoverShape.Square,
                    modifier = Modifier.size(MiniCoverSize),
                    showTitleWhenMissing = false,
                )
                MiniPlayerTexts(state, narration, Modifier.weight(1f))
                MiniPlayerButtons(state, narration, onPlayPause, onBack, onRate)
            }
        }
        progressFraction(state)?.let { fraction ->
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(fraction)
                    .height(ProgressHeight)
                    .background(colours.primary)
                    .progressSemantics(fraction)
                    .testTag(MiniPlayerProgressTag),
            )
        }
    }
}

/**
 * The title, and the chapter's line or "Finished" (a narration's own line), each on one line that ends with an
 * ellipsis.
 */
@Composable
private fun MiniPlayerTexts(state: PlayerUiState, narration: NarrationUi?, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = state.title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
        if (state.narrating) {
            // Its line once the narration's own state shows.
            narration?.let { NarrationLine(it) }
            return@Column
        }
        val line = lineText(state)
        if (line.isNotEmpty()) {
            val colours = MaterialTheme.colorScheme
            Text(
                text = line,
                style = MaterialTheme.typography.bodySmall,
                color = if (state.finished != null) colours.primary else colours.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The wave and "Narration · section", or "Narration finished" at its end. */
@Composable
private fun NarrationLine(narration: NarrationUi) {
    val colours = MaterialTheme.colorScheme
    val colour = if (narration.finished) colours.primary else colours.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        // The words say it is the narration: the wave says nothing more.
        Icon(
            imageVector = Icons.Outlined.GraphicEq,
            contentDescription = null,
            tint = colour,
            modifier = Modifier.size(NarrationGlyphSize).testTag(MiniPlayerNarrationTag),
        )
        Text(
            text = if (narration.finished) {
                stringResource(R.string.player_mini_narration_finished)
            } else {
                stringResource(R.string.player_mini_narration, narration.sectionTitle)
            },
            style = MaterialTheme.typography.bodySmall,
            color = colour,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Back and play or pause, named for a screen reader; "Rate" in their place once the book is finished, nothing at
 * the end of a narration.
 */
@Composable
private fun MiniPlayerButtons(
    state: PlayerUiState,
    narration: NarrationUi?,
    onPlayPause: () -> Unit,
    onBack: () -> Unit,
    onRate: () -> Unit,
) {
    if (narration?.finished == true) return
    // Never for a narration, whose state has no end panel.
    if (state.finished != null) {
        // Nothing to rate from a source without ratings: the bar itself opens the end panel.
        if (state.capabilities.ratings) {
            TextButton(onClick = onRate) { SingleLineLabel(stringResource(R.string.player_mini_rate)) }
        }
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, modifier = Modifier.size(MiniButtonSize)) {
            Icon(
                imageVector = Icons.Rounded.Replay,
                contentDescription = stringResource(R.string.player_button_back, state.settings.backSeconds),
            )
        }
        FilledIconButton(onClick = onPlayPause, modifier = Modifier.size(MiniButtonSize)) {
            Icon(
                imageVector = if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = stringResource(if (state.playing) R.string.player_pause else R.string.player_play),
            )
        }
    }
}

/** "12. The crossing · −23:51": the chapter, then the sleep timer or the time left in it; "Finished" at the end. */
@Composable
private fun lineText(state: PlayerUiState): String {
    if (state.finished != null) return stringResource(R.string.player_finished)
    val detail = state.sleep?.let { sleepText(it) } ?: chapterLeftText(state)
    val chapter = state.chapterTitle.takeIf { it.isNotEmpty() }
    return when {
        chapter != null && detail != null -> stringResource(R.string.player_joined, chapter, detail)
        else -> chapter ?: detail.orEmpty()
    }
}

/** When the sleep timer stops; null when that is not known. */
@Composable
private fun sleepText(sleep: SleepState): String? {
    val remaining = sleep.remainingMs
    return when {
        remaining != null -> stringResource(R.string.player_sleep_stops_in, durationText(remaining))
        sleep.choice == SleepChoice.EndOfChapter -> stringResource(R.string.player_sleep_stops_chapter)
        else -> null
    }
}

/** "−23:51", as under the player's chapter bar (at the speed); null for a chapter of unknown length. */
@Composable
private fun chapterLeftText(state: PlayerUiState): String? = state.chapterDurationMs?.let {
    stringResource(R.string.player_remaining, clockTime(timeLeft(it, state.chapterElapsedMs, state.speed)))
}

/** How far into the book playback is, from 0 to 1; into the chapter when the book's length is unknown. */
private fun progressFraction(state: PlayerUiState): Float? {
    val fraction = state.bookPercent?.div(Percent)
        ?: state.chapterDurationMs?.takeIf { it > 0 }?.let { state.chapterElapsedMs.toDouble() / it }
    return fraction?.toFloat()?.coerceIn(0f, 1f)
}
