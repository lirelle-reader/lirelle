package app.tfdn.lirelle.feature.reader

import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Let go past this share of its height, the sheet is dismissed. */
private const val DismissFraction = 0.25f

/** Let go moving down at least this fast, the sheet is dismissed however little it moved. */
private val DismissVelocity = 600.dp

/**
 * A sheet at the bottom of the screen that follows a finger dragging it down, and calls
 * [onDismiss] when let go fast enough down, or far enough and not flung back up; otherwise, and
 * once [onDismiss] was called, it comes back up: whether it closes is the caller's business.
 * Content that scrolls is scrolled first; the sheet moves once it is at its top.
 */
@Composable
internal fun DraggableSheet(onDismiss: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val dismiss by rememberUpdatedState(onDismiss)
    var height by remember { mutableIntStateOf(0) }
    val fast = with(LocalDensity.current) { DismissVelocity.toPx() }
    val drag = remember(scope) { SheetDrag(scope) }
    drag.onRelease = { velocity ->
        // Far enough down, unless flung back up: a sheet on its way up stays.
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

/** The grip at the top of a sheet: it says that the sheet can be dragged down. */
@Composable
internal fun SheetGrip(modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(width = 36.dp, height = 4.dp)
            .background(MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp)),
    )
}

/**
 * How far down a sheet is dragged, in pixels, never above its place. Drags and flings of the content
 * inside it come here once the content cannot take them.
 */
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

    /** A sheet already moved goes back up before the content scrolls. */
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
        if (available.y < 0 && offset > 0f && source == NestedScrollSource.UserInput) {
            Offset(0f, moveBy(available.y))
        } else {
            Offset.Zero
        }

    /** What the content, at its top, leaves of a drag down moves the sheet. */
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        if (available.y > 0 && source == NestedScrollSource.UserInput) Offset(0f, moveBy(available.y)) else Offset.Zero

    /** A sheet that was moved takes the fling: it is dismissed or comes back. */
    override suspend fun onPreFling(available: Velocity): Velocity {
        if (offset <= 0f) return Velocity.Zero
        release(available.y)
        return available
    }
}
