package app.tfdn.lirelle.feature.reader

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import android.content.Context
import android.provider.Settings
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.Menu
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrightnessMedium
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlin.math.roundToInt
import app.tfdn.lirelle.core.ui.R as UiR

internal const val BrightnessIndicatorTag = "reader-brightness"
private val IndicatorUprightPadding = 4.dp
private val IndicatorIconSize = 14.dp
private val IndicatorBarHeight = 3.dp
private const val IndicatorInkAlpha = 0.7f
private const val IndicatorTrackAlpha = 0.25f

private const val Hundred = 100

/** The highest value of the system's brightness setting. */
private const val SystemBrightnessMax = 255f

private val IndicatorBarWidth = 96.dp

/**
 * What the reader does to its window while it is shown, all of it undone when it is left: the
 * screen stays on, the system bars are hidden while reading and shown with the controls, the
 * brightness is the reader's, the volume keys turn the pages when the option is on, and the
 * system's back goes through the reader before leaving it.
 *
 * @param onLeave back was pressed and the reader had nothing left to close.
 */
@Composable
internal fun ReaderWindowEffects(state: ReaderUiState, host: ReaderHostEvents, onLeave: () -> Unit) {
    BackHandler { if (!host.onBack()) onLeave() }
    val window = LocalActivity.current?.window ?: return
    KeepScreenOn(window)
    SystemBars(window, hidden = state.phase is ReaderPhase.Reading && !state.controlsShown, dark = state.colours.isDark)
    WindowBrightness(window, state.settings.brightness, host)
    VolumeKeys(window, state, host)
}

@Composable
private fun KeepScreenOn(window: Window) {
    DisposableEffect(window) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}

/**
 * Hides the status and navigation bars while [hidden]; a swipe from an edge then shows them for a
 * moment. Their icons are dark on a light page and light on a [dark] one. On leaving, the bars
 * come back as they were.
 */
@Composable
private fun SystemBars(window: Window, hidden: Boolean, dark: Boolean) {
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
    DisposableEffect(controller, hidden, dark) {
        controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (hidden) controller.hide(WindowInsets.Type.systemBars()) else controller.show(WindowInsets.Type.systemBars())
        controller.setSystemBarsAppearance(if (dark) 0 else LightBars, LightBars)
        onDispose { }
    }
}

private const val LightBars =
    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS

/**
 * Gives the window the reader's [brightness], or the system's when it is null, and the one it had
 * before on leaving. Each time the reader comes to the front, [host] is told how bright the screen
 * is without the reader's own setting: where a first drag starts from.
 */
@Composable
private fun WindowBrightness(window: Window, brightness: Float?, host: ReaderHostEvents) {
    val before = remember(window) { window.attributes.screenBrightness }
    LifecycleResumeEffect(window, host) {
        systemBrightness(window.context, before)?.let(host::onSystemBrightness)
        onPauseOrDispose { }
    }
    DisposableEffect(window) {
        onDispose { window.setBrightness(before) }
    }
    DisposableEffect(window, brightness) {
        window.setBrightness(brightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
        onDispose { }
    }
}

private fun Window.setBrightness(level: Float) {
    attributes = attributes.apply { screenBrightness = level }
}

/**
 * How bright the screen is when the reader does not say, from 0 to 1: what the window asked for
 * before the reader, or else the system's setting. Null when the device does not tell.
 */
private fun systemBrightness(context: Context, windowBrightness: Float): Float? {
    if (windowBrightness >= 0f) return windowBrightness.coerceAtMost(1f)
    val setting = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1)
    return if (setting < 0) null else (setting / SystemBrightnessMax).coerceIn(0f, 1f)
}

/**
 * Hears the volume keys before the window does, wherever the focus is, the web view included.
 * A key the reader takes is consumed, press and release, so that the volume does not change.
 */
@Composable
private fun VolumeKeys(window: Window, state: ReaderUiState, host: ReaderHostEvents) {
    val current by rememberUpdatedState(state)
    val currentHost by rememberUpdatedState(host)
    DisposableEffect(window) {
        val before = window.callback
        val keys = KeyInterceptor(before) { event -> onVolumeKey(event, current, currentHost) }
        window.callback = keys
        onDispose {
            // Something else may have wrapped the callback since: then this one stays, and lets everything through.
            keys.active = false
            if (window.callback === keys) window.callback = before
        }
    }
}

/** Whether [event] is a volume key the reader takes. A key held down turns one page only. */
private fun onVolumeKey(event: KeyEvent, state: ReaderUiState, host: ReaderHostEvents): Boolean {
    val volumeDown = when (event.keyCode) {
        KeyEvent.KEYCODE_VOLUME_DOWN -> true
        KeyEvent.KEYCODE_VOLUME_UP -> false
        else -> return false
    }
    val firstPress = event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0
    return if (firstPress) host.onVolumeKey(volumeDown) else state.takesVolumeKeys
}

/** The same rule as the view model's for a press: the option is on, the book is shown, nothing is above it. */
private val ReaderUiState.takesVolumeKeys: Boolean
    get() = settings.volumeKeys && opened && openSheet == null && endPanel == null && externalLink == null &&
        !noteOpen && highlightPanel == null

/** A window's callback that offers each key to [onKey] first, and is otherwise [wrapped]. */
private class KeyInterceptor(
    private val wrapped: Window.Callback,
    private val onKey: (KeyEvent) -> Boolean,
) : Window.Callback by wrapped {
    var active = true

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        (active && onKey(event)) || wrapped.dispatchKeyEvent(event)

    // The interface gives these two a body of its own, which delegation would leave in place.
    override fun onProvideKeyboardShortcuts(data: MutableList<KeyboardShortcutGroup>?, menu: Menu?, deviceId: Int) =
        wrapped.onProvideKeyboardShortcuts(data, menu, deviceId)

    override fun onPointerCaptureChanged(hasCapture: Boolean) = wrapped.onPointerCaptureChanged(hasCapture)
}

/**
 * How bright the reader is, shown while a finger slides along the edge. [level] goes from 0 to 1.
 *
 * One discreet line, in the type of the page's header and without a ground of its own: it is drawn in the
 * band above the text, where it hides none of it.
 */
@Composable
internal fun BrightnessIndicator(level: Float, modifier: Modifier = Modifier) {
    val shown = level.coerceIn(0f, 1f)
    val ink = MaterialTheme.colorScheme.onBackground
    Row(
        modifier = modifier
            .testTag(BrightnessIndicatorTag)
            .padding(horizontal = 16.dp, vertical = IndicatorUprightPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.BrightnessMedium,
            contentDescription = null,
            modifier = Modifier.size(IndicatorIconSize),
            tint = ink.copy(alpha = IndicatorInkAlpha),
        )
        Box(
            Modifier
                .width(IndicatorBarWidth)
                .height(IndicatorBarHeight)
                .background(ink.copy(alpha = IndicatorTrackAlpha), CircleShape),
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(shown)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
            )
        }
        Text(
            text = stringResource(UiR.string.progress_percent, (shown * Hundred).roundToInt()),
            style = MaterialTheme.typography.labelSmall,
            color = ink.copy(alpha = IndicatorInkAlpha),
            maxLines = 1,
            softWrap = false,
        )
    }
}
