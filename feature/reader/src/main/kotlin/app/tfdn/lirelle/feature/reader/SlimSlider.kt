package app.tfdn.lirelle.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private val TrackHeight = 4.dp
private val ThumbSize = 20.dp
private const val DisabledAlpha = 0.38f

/**
 * The reader's slider: a thin track and a round thumb, without the gap and the end dot of the stock one,
 * which weigh too much under a page of text. The touch target stays that of the stock slider.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SlimSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val interactions = remember { MutableInteractionSource() }
    val accent = MaterialTheme.colorScheme.primary
    val slider = remember(valueRange) { SliderState(value, trackRange = valueRange) }
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
                thumbTrackGapSize = 0.dp,
                trackInsideCornerSize = 0.dp,
            )
        },
    )
}
