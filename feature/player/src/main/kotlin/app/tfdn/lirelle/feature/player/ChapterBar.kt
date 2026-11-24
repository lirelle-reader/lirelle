package app.tfdn.lirelle.feature.player

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.feature.player.controller.timeLeft
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay

internal const val ChapterBarTag = "player-chapter-bar"

private val TrackHeight = 4.dp
private val ThumbSize = 16.dp
private const val DisabledAlpha = 0.38f

/** The chapter's bar takes a finger across its whole height, however thin its track. */
private val BarTouchHeight = 40.dp

/** Playback this close to where the bar was let go is there: the bar follows it again. */
private const val CatchUpMs = 1_000L

/** How many looks at playback a bar let go waits for it to get there, before following it anyway. */
private const val MostLooks = 2

/** How long a bar let go waits at most, looks or not (a paused player sends none). */
private val HoldAtMost = 2.seconds

/**
 * A drag of the chapter's bar, which belongs to the chapter it began in: a drag still under the
 * finger when another chapter starts is cancelled, and seeks nothing.
 */
private data class Drag(
    val chapter: Int,
    val fraction: Float,
    val released: Boolean = false,
    val cancelled: Boolean = false,
    val looks: Int = 0,
)

/**
 * Where the bar is drawn while a finger drags it, and once let go until playback is there: the bar
 * does not jump back to the old place while the seek is on its way.
 */
internal class ChapterBarDrag {
    private var drag by mutableStateOf<Drag?>(null)

    /** How many times the bar was let go: each starts the wait of [dropHeld]. */
    var releases by mutableIntStateOf(0)
        private set

    /** The fraction drawn instead of playback's; null to draw playback's. */
    val shown: Float? get() = drag?.takeUnless { it.cancelled }?.fraction

    /** The finger is at [fraction] of chapter [chapter]. */
    fun move(chapter: Int, fraction: Float) {
        val current = drag
        drag = when {
            current == null || current.released -> Drag(chapter, fraction)
            current.cancelled -> current
            else -> current.copy(fraction = fraction)
        }
    }

    /** The finger is lifted: where to seek, or null for a drag cancelled. */
    fun release(): Float? {
        val current = drag ?: return null
        if (current.cancelled || current.released) {
            drag = null
            return null
        }
        drag = current.copy(released = true)
        releases++
        return current.fraction
    }

    /** The bar let go has waited long enough: it follows playback again. A drag under the finger stays. */
    fun dropHeld() {
        if (drag?.released == true) drag = null
    }

    /** Playback is at [elapsedMs] into chapter [chapter], [durationMs] long. */
    fun onPlayback(chapter: Int, elapsedMs: Long, durationMs: Long?) {
        val current = drag ?: return
        if (chapter != current.chapter) {
            drag = if (current.released) null else current.copy(cancelled = true)
            return
        }
        if (!current.released) return
        val target = durationMs?.let { (it * current.fraction.toDouble()).roundToLong() }
        val there = target == null || abs(elapsedMs - target) <= CatchUpMs
        drag = if (there || current.looks >= MostLooks) null else current.copy(looks = current.looks + 1)
    }
}

/**
 * The bar of the current chapter: the time played and, when its length is known, the time left at
 * the speed. A drag moves the bar and the time under the finger; playback moves only when the finger
 * is lifted. A chapter of unknown length cannot be dragged. A screen reader hears the time played and
 * the chapter's length; [label] is what it is called (a narration's section's bar is not a chapter's).
 */
@Composable
internal fun ChapterBar(
    place: BarPlace,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    @StringRes label: Int = R.string.player_chapter_bar,
) {
    val drag = remember { ChapterBarDrag() }
    // Playback moved, not the speed: a change of speed is no news of where a seek has got to.
    LaunchedEffect(place.chapterIndex, place.elapsedMs, place.durationMs) {
        drag.onPlayback(place.chapterIndex, place.elapsedMs, place.durationMs)
    }
    LaunchedEffect(drag.releases) {
        if (drag.releases == 0) return@LaunchedEffect
        delay(HoldAtMost)
        drag.dropHeld()
    }
    val duration = place.durationMs?.takeIf { it > 0 }
    val dragged = drag.shown
    val fraction = dragged ?: duration?.let { (place.elapsedMs.toFloat() / it).coerceIn(0f, 1f) } ?: 0f
    val elapsed = duration?.let { d -> dragged?.let { (d * it.toDouble()).roundToLong() } } ?: place.elapsedMs
    val spoken = stringResource(label)
    val state = duration?.let { stringResource(R.string.player_chapter_bar_state, clockTime(elapsed), clockTime(it)) }
        ?: clockTime(elapsed)
    Column(modifier) {
        SlimSlider(
            value = fraction,
            onValueChange = { drag.move(place.chapterIndex, it) },
            onValueChangeFinished = { drag.release()?.let(onSeek) },
            enabled = duration != null,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = BarTouchHeight)
                .testTag(ChapterBarTag)
                .semantics {
                    contentDescription = spoken
                    stateDescription = state
                },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            ProvideTextStyle(MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum")) {
                Text(clockTime(elapsed), color = MaterialTheme.colorScheme.onSurfaceVariant)
                duration?.let {
                    // Listening time at the speed, as every time left; the time played stays the book's.
                    val left = stringResource(R.string.player_remaining, clockTime(timeLeft(it, elapsed, place.speed)))
                    Text(left, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** A thin track and a round thumb, as the reader's; the touch target stays that of the stock slider. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SlimSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val interactions = remember { MutableInteractionSource() }
    val accent = MaterialTheme.colorScheme.primary
    val slider = remember(steps, valueRange) { SliderState(value, steps, valueRange) }
    slider.value = value
    Slider(
        state = slider,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        onValueChangeFinished = onValueChangeFinished,
        interactionSource = interactions,
        thumb = {
            Box(
                Modifier
                    .size(ThumbSize)
                    .background(if (enabled) accent else accent.copy(alpha = DisabledAlpha), CircleShape),
            )
        },
        track = { state ->
            SliderDefaults.Track(
                sliderState = state,
                modifier = Modifier.height(TrackHeight),
                enabled = enabled,
                drawStopIndicator = null,
                drawTick = { _, _ -> },
                thumbTrackGapSize = 0.dp,
                trackInsideCornerSize = 0.dp,
            )
        },
    )
}
