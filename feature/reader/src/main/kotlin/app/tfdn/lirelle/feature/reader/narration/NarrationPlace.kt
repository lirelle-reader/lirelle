package app.tfdn.lirelle.feature.reader.narration

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import app.tfdn.lirelle.core.designsystem.LocalWindowWidthClass
import app.tfdn.lirelle.core.designsystem.WindowWidthClass
import app.tfdn.lirelle.feature.player.narration.NarrationActions
import app.tfdn.lirelle.feature.player.narration.NarrationColors
import app.tfdn.lirelle.feature.reader.ReaderPhase
import app.tfdn.lirelle.feature.reader.ReaderUiState
import kotlinx.coroutines.launch
import app.tfdn.lirelle.feature.player.R as PlayerR

/**
 * Where the narration of the book is shown: its bar among the reader's controls, its panel opened by
 * [onOpenPanel], its messages in [snackbars]. Nothing without [controls].
 */
@Stable
internal class NarrationPlace(
    val controls: NarrationActions?,
    val colors: NarrationColors,
    val wide: Boolean,
    val onOpenPanel: () -> Unit,
    val snackbars: SnackbarHostState,
)

/**
 * The narration's place for [state]: none until the book is at hand; the wide bar on a tablet's window. The same
 * place while these stay, so that what is given it is not composed again for nothing: [onOpenPanel] is taken
 * once, and must only set state.
 */
@Composable
internal fun rememberNarrationPlace(
    controls: NarrationActions?,
    state: ReaderUiState,
    onOpenPanel: () -> Unit,
): NarrationPlace {
    val shown = controls?.takeIf { state.phase is ReaderPhase.Reading }
    val wide = LocalWindowWidthClass.current != WindowWidthClass.Compact
    val snackbars = remember { SnackbarHostState() }
    return remember(shown, state.colours, wide) {
        NarrationPlace(shown, state.colours.narrationColors(), wide, onOpenPanel, snackbars)
    }
}

/** Whether the narration's bar is among the controls: the book has a narration loaded, and [place] shows it. */
internal fun ReaderUiState.narrationDocked(place: NarrationPlace): Boolean =
    place.controls != null && narration.active?.loaded == true

/**
 * Stops listening and says so in [place]'s messages, with "Undo". Made where the reader stays composed: the bar
 * that asks it goes with the narration it stops, and with the controls, while the message waits for its answer.
 */
@Composable
internal fun rememberNarrationStop(actions: ReaderNarrationActions, place: NarrationPlace): () -> Unit {
    val scope = rememberCoroutineScope()
    val stopped = stringResource(PlayerR.string.player_stopped)
    val undo = stringResource(PlayerR.string.player_stopped_undo)
    return remember(actions, place, stopped, undo) {
        {
            actions.stopNarration()
            scope.launch {
                val answer = place.snackbars.showSnackbar(stopped, undo, duration = SnackbarDuration.Long)
                if (answer == SnackbarResult.ActionPerformed) actions.undoStopNarration()
            }
        }
    }
}

/** [NarrationDock] among the reader's controls, under their bottom bar. Nothing without a narration loaded. */
@Composable
internal fun ControlsNarrationDock(
    state: ReaderUiState,
    actions: ReaderNarrationActions,
    place: NarrationPlace,
    onStop: () -> Unit,
) {
    val controls = place.controls ?: return
    NarrationDock(state.narration, place.colors, actions, controls, place.wide, place.onOpenPanel, onStop = onStop)
}
