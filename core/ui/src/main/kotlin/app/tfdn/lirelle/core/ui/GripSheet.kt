package app.tfdn.lirelle.core.ui

import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

const val GripSheetTag = "grip-sheet"
const val SheetGripTag = "sheet-grip"

/** The share of the window a sheet of lists takes. */
private const val SheetShare = 0.9f

/** Let go past this share of its height, the sheet is dismissed. */
private const val DismissFraction = 0.25f

/** Let go moving down at least this fast, the sheet is dismissed however little it moved. */
private val DismissVelocity = 600.dp

/** The strip of the grip: as high as a touch target, the width of the sheet. */
private val GripStripHeight = 48.dp

/**
 * The height of a modal bottom sheet holding a list: a share of the window, never what the list
 * shows, so that the sheet does not change size as the list scrolls or from one tab to another.
 */
@Composable
fun sheetListHeight(): Dp {
    val window = LocalWindowInfo.current.containerSize.height
    return with(LocalDensity.current) { window.toDp() * SheetShare }
}

/**
 * A modal bottom sheet whose content scrolls on its own: nothing a list or a column inside it does
 * moves the sheet (Material's sheet takes part in its content's scrolling, which made it bounce, close
 * or hold the list on a device). It closes with a tap above it and with back, as Material's, and by
 * its grip: dragged down, the sheet follows the finger, and let go far enough or fast enough down it
 * closes, otherwise it comes back up. A screen reader closes it with the grip's dismiss action.
 *
 * @param containerColor the sheet's colour; Material's by default.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GripSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = BottomSheetDefaults.ContainerColor,
    content: @Composable ColumnScope.() -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val dismiss by rememberUpdatedState(onDismissRequest)
    val drag = remember { GripDrag() }
    val close: () -> Unit = { scope.launch { drag.close(sheetState) { dismiss() } } }
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        sheetGesturesEnabled = false,
        // The sheet is drawn here, so that it follows the grip; Material's stays where it is, unseen.
        containerColor = Color.Transparent,
        tonalElevation = 0.dp,
        dragHandle = null,
        contentWindowInsets = { WindowInsets(0) },
    ) {
        Surface(
            modifier = modifier
                .testTag(GripSheetTag)
                .offset { IntOffset(0, drag.offset.roundToInt()) }
                .onSizeChanged { drag.height = it.height },
            shape = BottomSheetDefaults.ExpandedShape,
            color = containerColor,
        ) {
            Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))) {
                Grip(drag, scope, onClose = close)
                content()
            }
        }
    }
}

/** The grip's strip: dragging it moves the sheet; it tells a screen reader how to close the sheet. */
@Composable
private fun Grip(drag: GripDrag, scope: CoroutineScope, onClose: () -> Unit) {
    val fast = with(LocalDensity.current) { DismissVelocity.toPx() }
    val label = stringResource(R.string.sheet_grip)
    val closeLabel = stringResource(R.string.sheet_close)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(GripStripHeight)
            .testTag(SheetGripTag)
            // First in the sheet to take the focus, as Material's handle: a field below is not focused on opening.
            .focusable()
            .semantics {
                contentDescription = label
                dismiss(closeLabel) {
                    onClose()
                    true
                }
            }
            .draggable(
                state = rememberDraggableState { drag.moveBy(it) },
                orientation = Orientation.Vertical,
                onDragStopped = { velocity ->
                    if (drag.dismissed(velocity, fast)) onClose() else scope.launch { drag.settle() }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(width = 32.dp, height = 4.dp)
                .background(MaterialTheme.colorScheme.onSurfaceVariant, RoundedCornerShape(2.dp)),
        )
    }
}

/** How far down the grip has dragged the sheet, in pixels, never above its place. */
private class GripDrag {
    var offset by mutableFloatStateOf(0f)
        private set
    var height by mutableIntStateOf(0)

    fun moveBy(delta: Float) {
        offset = (offset + delta).coerceAtLeast(0f)
    }

    /** Let go at [velocity] down: dismissed when fast enough down, or far enough and not flung back up. */
    fun dismissed(velocity: Float, fast: Float): Boolean =
        velocity > fast || (offset > height * DismissFraction && velocity > -fast)

    suspend fun settle() {
        animate(offset, 0f) { value, _ -> offset = value }
    }

    /** Out of sight, then Material's sheet hidden (its scrim fading), then [then]. */
    @OptIn(ExperimentalMaterial3Api::class)
    suspend fun close(sheetState: SheetState, then: () -> Unit) {
        animate(offset, height.toFloat()) { value, _ -> offset = value }
        sheetState.hide()
        then()
    }
}
