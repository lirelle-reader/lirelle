package app.tfdn.lirelle.feature.comics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.comics.settings.Direction
import kotlin.math.roundToInt

const val ComicSliderTag = "comic-slider"

/** The thumb of the slider. */
const val ComicSliderThumbTag = "comic-slider-thumb"

private val TrackHeight = 4.dp
private val ThumbSize = 20.dp
private const val DisabledAlpha = 0.38f

/**
 * The slider over the pages of the album. Dragged, its thumb stays under the finger, between two pages as well,
 * and it tells the reader the nearest page each time it changes ([ComicNavigation.previewPage]), which the bar
 * then names; let go, the reader goes to that page ([ComicNavigation.goTo]) and the thumb stands on it. It runs
 * in the reading direction: from the right in an album read right to left. A screen reader hears "Page" and
 * "12 of 48", and moves it page by page, as the arrow keys do.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PageSlider(state: ComicReaderUiState, navigation: ComicNavigation, modifier: Modifier = Modifier) {
    val last = (state.pageCount - 1).coerceAtLeast(0)
    // Where the finger is, in pages, from the first move to the release: the reader's own preview comes after it.
    var dragged by remember { mutableStateOf<Float?>(null) }
    val page = dragged?.roundToInt() ?: state.previewPage ?: state.spread?.first ?: 0
    val name = stringResource(R.string.comics_slider)
    val value = state.label?.let { sliderValue(it) }.orEmpty()
    val enabled = last > 0
    val accent = MaterialTheme.colorScheme.primary
    val direction =
        if (state.settings.direction == Direction.RightToLeft) LayoutDirection.Rtl else LayoutDirection.Ltr
    val pages = 0f..last.coerceAtLeast(1).toFloat()
    val steps = (last - 1).coerceAtLeast(0)
    // No steps: the thumb follows the finger between two pages.
    val slider = remember(pages) { SliderState(page.toFloat(), trackRange = pages) }
    slider.value = dragged ?: page.toFloat()

    CompositionLocalProvider(LocalLayoutDirection provides direction) {
        Slider(
            state = slider,
            onValueChange = {
                val nearest = it.roundToInt().coerceIn(0, last)
                if (nearest != dragged?.roundToInt()) navigation.previewPage(nearest)
                dragged = it
            },
            modifier = modifier
                .testTag(ComicSliderTag)
                .onPreviewKeyEvent { event ->
                    val to = keyStep(event.key, direction)?.let { (page + it).coerceIn(0, last) }
                    if (to != null && event.type == KeyEventType.KeyDown) {
                        navigation.previewPage(to)
                        navigation.goTo(to)
                    }
                    to != null
                }
                .semantics {
                    contentDescription = name
                    stateDescription = value
                    // A page at a time, wherever the thumb is between two of them.
                    progressBarRangeInfo = ProgressBarRangeInfo(page.toFloat(), pages, steps)
                },
            enabled = enabled,
            onValueChangeFinished = {
                dragged?.let { navigation.goTo(it.roundToInt().coerceIn(0, last)) }
                dragged = null
            },
            thumb = {
                Box(
                    Modifier
                        .size(ThumbSize)
                        .testTag(ComicSliderThumbTag)
                        .background(if (enabled) accent else accent.copy(alpha = DisabledAlpha), CircleShape),
                )
            },
            track = { sliderState ->
                SliderDefaults.Track(
                    sliderState = sliderState,
                    modifier = Modifier.height(TrackHeight),
                    enabled = enabled,
                    drawStopIndicator = null,
                    thumbTrackGapSize = 0.dp,
                    trackInsideCornerSize = 0.dp,
                )
            },
        )
    }
}

/**
 * How many pages an arrow key moves the slider by, null for another key: the arrow at the end of the reading
 * direction and the one up go forward.
 */
private fun keyStep(key: Key, direction: LayoutDirection): Int? {
    val towardsEnd = if (direction == LayoutDirection.Rtl) Key.DirectionLeft else Key.DirectionRight
    val towardsStart = if (direction == LayoutDirection.Rtl) Key.DirectionRight else Key.DirectionLeft
    return when (key) {
        towardsEnd, Key.DirectionUp -> 1
        towardsStart, Key.DirectionDown -> -1
        else -> null
    }
}

/** "12 of 48", or "12-13 of 48" with two pages. */
@Composable
private fun sliderValue(label: PageLabel): String = when (val last = label.last) {
    null -> stringResource(R.string.comics_slider_value, label.first, label.count)
    else -> stringResource(R.string.comics_slider_value_two, label.first, last, label.count)
}
