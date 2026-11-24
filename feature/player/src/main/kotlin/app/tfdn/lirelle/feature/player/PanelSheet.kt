package app.tfdn.lirelle.feature.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal const val PanelSheetTag = "player-panel-sheet"
internal const val PanelGripTag = "player-panel-grip"

/** How much the player is dimmed behind the panel. */
private const val ScrimAlpha = 0.5f

/** On a wide window the panel stays a sheet, centred, instead of a band across the screen. */
private val SheetMaxWidth = 640.dp
private val SheetCorner = 24.dp

/** Let go past this share of its height, the sheet is dismissed. */
private const val DismissFraction = 0.25f

/** Let go moving down at least this fast, the sheet is dismissed however little it moved. */
private val DismissVelocity = 600.dp

/** The sides where the panel keeps clear of the system bars and cut-outs; its content sees to the bottom. */
private val AroundSheet = WindowInsetsSides.Top + WindowInsetsSides.Horizontal

/**
 * A panel at the bottom of its slot, which it fills, over the player dimmed behind it: the 2b panels
 * of the EPUB reader (its end panel), copied rather than shared so that the player does not depend on
 * the reader. A swipe down on the sheet, from its grip or anywhere on it, a tap above it and back all
 * call [onDismiss]; whether it closes is the caller's business. The player takes no touch while it is
 * shown.
 */
@Composable
internal fun PanelSheet(onDismiss: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val dismiss by rememberUpdatedState(onDismiss)
    BackHandler { dismiss() }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = ScrimAlpha))
            .windowInsetsPadding(WindowInsets.safeDrawing.only(AroundSheet)),
        contentAlignment = Alignment.BottomCenter,
    ) {
        // Behind the sheet, not around it: a tap on the sheet itself must not dismiss it.
        Box(Modifier.matchParentSize().pointerInput(Unit) { detectTapGestures { dismiss() } })
        DraggableSheet(
            onDismiss = { dismiss() },
            modifier = Modifier
                .testTag(PanelSheetTag)
                .widthIn(max = SheetMaxWidth)
                .fillMaxWidth(),
        ) {
            Surface(
                shape = RoundedCornerShape(topStart = SheetCorner, topEnd = SheetCorner),
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    SheetGrip(Modifier.testTag(PanelGripTag).padding(top = 10.dp))
                    Box(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))) {
                        content()
                    }
                }
            }
        }
    }
}

/** The grip at the top of a sheet: it says that the sheet can be dragged down. */
@Composable
private fun SheetGrip(modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(width = 36.dp, height = 4.dp)
            .background(MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp)),
    )
}

/**
 * A sheet that follows a finger dragging it down, and calls [onDismiss] when let go fast enough down,
 * or far enough and not flung back up; then it comes back up. Content that scrolls is scrolled first;
 * the sheet moves once it is at its top.
 */
@Composable
private fun DraggableSheet(onDismiss: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val dismiss by rememberUpdatedState(onDismiss)
    var height by remember { mutableIntStateOf(0) }
    val fast = with(LocalDensity.current) { DismissVelocity.toPx() }
    val drag = remember(scope) { SheetDrag(scope) }
    drag.onRelease = { velocity ->
        if (velocity > fast || (drag.offset > height * DismissFraction && velocity > -fast)) dismiss()
    }
    Box(
        modifier = modifier
            .onSizeChanged { height = it.height }
            .offset { IntOffset(0, drag.offset.roundToInt()) }
            .nestedScroll(drag)
            .draggable(
                state = rememberDraggableState { drag.moveBy(it) },
                orientation = Orientation.Vertical,
                onDragStopped = { drag.release(it) },
            ),
    ) {
        content()
    }
}

/** How far down a sheet is dragged, in pixels, never above its place; what its content leaves of a drag. */
private class SheetDrag(private val scope: CoroutineScope) : NestedScrollConnection {
    var offset by mutableFloatStateOf(0f)
        private set

    /** Called with the speed down, in pixels a second, when the finger leaves a dragged sheet. */
    var onRelease: (Float) -> Unit = {}

    private var back: Job? = null

    fun moveBy(delta: Float): Float {
        back?.cancel()
        val before = offset
        offset = (before + delta).coerceAtLeast(0f)
        return offset - before
    }

    fun release(velocity: Float) {
        onRelease(velocity)
        back?.cancel()
        back = scope.launch { animate(offset, 0f) { value, _ -> offset = value } }
    }

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
        if (available.y < 0 && offset > 0f && source == NestedScrollSource.UserInput) {
            Offset(0f, moveBy(available.y))
        } else {
            Offset.Zero
        }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        if (available.y > 0 && source == NestedScrollSource.UserInput) Offset(0f, moveBy(available.y)) else Offset.Zero

    override suspend fun onPreFling(available: Velocity): Velocity {
        if (offset <= 0f) return Velocity.Zero
        release(available.y)
        return available
    }
}
