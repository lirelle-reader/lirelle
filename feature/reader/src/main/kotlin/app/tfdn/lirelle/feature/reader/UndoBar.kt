package app.tfdn.lirelle.feature.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.designsystem.SingleLineLabel
import kotlinx.coroutines.delay

internal const val UndoBarTag = "reader-undo-bar"

/** As long as a short snackbar stays: what is undone is offered no longer than that. */
private const val UndoShownMillis = 4_000L

private val UndoBarMaxWidth = 560.dp

/**
 * What was just deleted, and "Undo". The offer goes away by itself after the time of a short
 * snackbar, longer when the user asked the system for more time to act; a newer deletion is given
 * the whole time again. [onDismiss] is told which offer timed out.
 */
@Composable
internal fun UndoBar(undo: UndoUi, onUndo: () -> Unit, onDismiss: (UndoUi) -> Unit, modifier: Modifier = Modifier) {
    val message = when (undo) {
        is UndoUi.Highlight -> stringResource(R.string.reader_highlight_deleted)
        is UndoUi.Bookmarks -> if (undo.ids.size == 1) {
            stringResource(R.string.reader_bookmark_deleted)
        } else {
            pluralStringResource(R.plurals.reader_bookmarks_deleted, undo.ids.size, undo.ids.size)
        }
    }
    val accessibility = LocalAccessibilityManager.current
    val dismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(undo) {
        val shown = accessibility?.calculateRecommendedTimeoutMillis(
            UndoShownMillis,
            containsIcons = false,
            containsText = true,
            containsControls = true,
        ) ?: UndoShownMillis
        delay(shown)
        // This offer: a newer one, not yet on screen, is not taken away with it.
        dismiss(undo)
    }
    Surface(
        modifier = modifier
            .testTag(UndoBarTag)
            .padding(horizontal = 12.dp)
            .widthIn(max = UndoBarMaxWidth)
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shadowElevation = 6.dp,
    ) {
        MessageAndAction(message, Modifier.padding(start = 16.dp, end = 8.dp)) {
            TextButton(
                onClick = onUndo,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.inversePrimary),
            ) {
                SingleLineLabel(stringResource(R.string.reader_undo))
            }
        }
    }
}

/**
 * The message, then the action at the end of the same line; the action goes under the message,
 * still at the end, when a word of the message would not fit beside it. Words are what spaces
 * separate: a hyphen is no place to count on for a break.
 */
@Composable
private fun MessageAndAction(message: String, modifier: Modifier, action: @Composable () -> Unit) {
    val style = MaterialTheme.typography.bodyMedium
    val measurer = rememberTextMeasurer()
    Layout(
        content = {
            Text(text = message, modifier = Modifier.padding(vertical = 14.dp), style = style)
            action()
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val text = measurables[0]
        val button = measurables[1].measure(constraints.copy(minWidth = 0, minHeight = 0))
        val beside = (constraints.maxWidth - button.width).coerceAtLeast(0)
        val longestWord = message.split(' ', '\n').maxOf { word ->
            measurer.measure(word, style, maxLines = 1, softWrap = false).size.width
        }
        if (longestWord <= beside) {
            val shown = text.measure(Constraints(maxWidth = beside))
            val height = maxOf(shown.height, button.height)
            layout(constraints.maxWidth, height) {
                shown.placeRelative(0, (height - shown.height) / 2)
                button.placeRelative(constraints.maxWidth - button.width, (height - button.height) / 2)
            }
        } else {
            val shown = text.measure(Constraints(maxWidth = constraints.maxWidth))
            layout(constraints.maxWidth, shown.height + button.height) {
                shown.placeRelative(0, 0)
                button.placeRelative(constraints.maxWidth - button.width, shown.height)
            }
        }
    }
}

/**
 * The offer to undo a deletion, at the bottom of the screen, above what [BottomChrome] shows there
 * ([liftPx] high), else clear of the system's bars.
 */
@Composable
internal fun UndoOverlay(undo: UndoUi, actions: ReaderActions, liftPx: Int) {
    val bottom = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
    val lift = with(LocalDensity.current) { liftPx.toDp() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(if (liftPx > 0) Modifier.padding(bottom = lift) else Modifier.windowInsetsPadding(bottom)),
        contentAlignment = Alignment.BottomCenter,
    ) {
        UndoBar(undo, actions::undo, actions::dismissUndo, Modifier.padding(bottom = 12.dp))
    }
}
