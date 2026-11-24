package app.tfdn.lirelle.feature.comics

import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.tfdn.lirelle.core.ids.BookId
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.Flow

/**
 * What the comics reader does to its window while it is shown, all of it undone when it is left: the screen
 * stays on, and the system bars are hidden while the reader's own bars are, and shown with them, as the book
 * reader's are; while they are hidden, a swipe from an edge shows them for a moment.
 *
 * @param barsOver whether the reader's bars are over the pages (always while the album opens).
 * @param darkGround whether what lies under the system bars is dark ([darkUnderSystemBars]).
 */
@Composable
internal fun ComicWindowEffects(barsOver: Boolean, darkGround: Boolean) {
    val window = LocalActivity.current?.window ?: return
    KeepScreenOn(window)
    SystemBars(window, hidden = !barsOver, darkGround)
}

/**
 * Whether what lies under the system bars is dark: the reader's bars when they are over the pages, the ground
 * of the pages otherwise.
 *
 * @param barsOver whether the reader's bars are shown (always while the album opens).
 * @param bar the ground of the reader's bars.
 * @param page the ground of the pages.
 */
internal fun darkUnderSystemBars(barsOver: Boolean, bar: Color, page: Color): Boolean =
    (if (barsOver) bar else page).luminance() < DarkBelow

/** The luminance under which a ground is dark. The mid grey of the pages is: light icons read better on it. */
private const val DarkBelow = 0.5f

@Composable
private fun KeepScreenOn(window: Window) {
    DisposableEffect(window) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}

/**
 * Hides the status and navigation bars while [hidden]; a swipe from an edge then shows them for a moment.
 * Their icons are light over a [darkGround] and dark over a light one. On leaving, the bars come back as they
 * were.
 */
@Composable
private fun SystemBars(window: Window, hidden: Boolean, darkGround: Boolean) {
    val controller = window.insetsController ?: return
    DisposableEffect(controller) {
        val behaviour = controller.systemBarsBehavior
        val appearance = controller.systemBarsAppearance and LightBars
        onDispose {
            controller.show(WindowInsets.Type.systemBars())
            controller.systemBarsBehavior = behaviour
            controller.setSystemBarsAppearance(appearance, LightBars)
        }
    }
    // After the effect above, which remembers what the bars were before the reader changes them.
    DisposableEffect(controller, hidden, darkGround) {
        controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        val bars = WindowInsets.Type.systemBars()
        if (hidden) controller.hide(bars) else controller.show(bars)
        controller.setSystemBarsAppearance(if (darkGround) 0 else LightBars, LightBars)
        onDispose {}
    }
}

private const val LightBars =
    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS

/**
 * What the reader has to know of the screen around it: the size of its window, at first and each time it
 * changes, whether it is in front, and whether the build is [debuggable].
 */
@Composable
internal fun ComicHostReports(host: ComicHostEvents, debuggable: Boolean = false) {
    LaunchedEffect(host, debuggable) { host.buildDebuggable(debuggable) }
    val size = LocalWindowInfo.current.containerSize
    val (width, height) = with(LocalDensity.current) { size.width.toDp() to size.height.toDp() }
    LaunchedEffect(host, width, height) { host.windowSize(width.value.roundToInt(), height.value.roundToInt()) }
    LifecycleResumeEffect(host) {
        host.onForeground()
        onPauseOrDispose { host.onBackground() }
    }
}

/** What the reader asks once: to be left, or the next album to be opened. */
@Composable
internal fun ComicReaderEvents(
    events: Flow<ComicReaderEvent>,
    onLeave: () -> Unit,
    onOpenNext: (bookId: BookId) -> Unit,
) {
    val leave by rememberUpdatedState(onLeave)
    val openNext by rememberUpdatedState(onOpenNext)
    LaunchedEffect(events) {
        events.collect { event ->
            when (event) {
                ComicReaderEvent.Leave -> leave()
                is ComicReaderEvent.OpenNext -> openNext(event.bookId)
            }
        }
    }
}
