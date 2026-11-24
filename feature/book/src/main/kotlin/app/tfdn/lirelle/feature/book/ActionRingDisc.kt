package app.tfdn.lirelle.feature.book

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

// ---------- The disc of a reading action, and the ring around it ----------

const val ActionRingDiscTag = "action-ring-disc"

/** Material's alpha of what is switched off. */
internal const val RingDisabledAlpha = 0.38f

/** The room the disc and its ring take. */
private val DiscSize = 68.dp

/** The radius of the disc; the ring is around it, the page showing between the two. */
private val DiscRadius = 26.dp

/** The radius of the middle of the ring's stroke. */
private val RingRadius = 31.dp
private val RingStroke = 4.dp
private val DashedStroke = 2.dp
private val DashLength = 5.dp

/** How much of the accent is left in the track of the filled button. */
internal const val FilledTrackAlpha = 0.3f
private const val TopOfCircle = -90f
private const val WholeCircle = 360f

/**
 * The colours of a disc.
 *
 * @property track the ring where there is no progress; it is drawn over the page.
 * @property arc the progress.
 * @property dashed the button is switched off for want of its format: no disc, a dashed circle in [track].
 */
internal class DiscColors(val disc: Color, val track: Color, val arc: Color, val icon: Color, val dashed: Boolean)

/**
 * The progress is always the accent colour, and the rest of the ring a faded colour: the accent itself, mostly
 * transparent, around the filled button, which is a disc of the accent; a quiet line around the outlined one,
 * which is a quiet disc. A button whose format the device does not have is a dashed circle. Any other button
 * that cannot be used has the outlined look, dimmed.
 */
internal fun discColors(ui: ActionRingUi, scheme: ColorScheme): DiscColors {
    val dim = if (ui.enabled) 1f else RingDisabledAlpha
    return when {
        ui.status == RingStatus.Offline || ui.status == RingStatus.Unavailable -> DiscColors(
            disc = Color.Transparent,
            track = scheme.outlineVariant,
            arc = Color.Transparent,
            icon = scheme.onSurfaceVariant.copy(alpha = RingDisabledAlpha),
            dashed = true,
        )
        ui.filled && ui.enabled -> DiscColors(
            disc = scheme.primary,
            track = scheme.primary.copy(alpha = FilledTrackAlpha),
            arc = scheme.primary,
            icon = scheme.onPrimary,
            dashed = false,
        )
        else -> DiscColors(
            disc = scheme.surfaceContainerHigh.copy(alpha = dim),
            track = scheme.outlineVariant.copy(alpha = dim),
            arc = scheme.primary.copy(alpha = dim),
            icon = scheme.onSurface.copy(alpha = dim),
            dashed = false,
        )
    }
}

/**
 * The disc of the button, with the action's icon in its middle. The ring is around the disc, apart from it: a
 * track all around, and over it the progress, an arc that starts at the top, runs clockwise and has round ends.
 */
@Composable
internal fun ActionDisc(ui: ActionRingUi, modifier: Modifier = Modifier) {
    val colors = discColors(ui, MaterialTheme.colorScheme)
    Box(
        modifier = modifier
            .size(DiscSize)
            .testTag(ActionRingDiscTag)
            .drawBehind { if (colors.dashed) drawDashedCircle(colors.track) else drawRing(colors, ui.fraction) },
        contentAlignment = Alignment.Center,
    ) {
        ActionIcon(ui, colors.icon)
    }
}

private fun DrawScope.drawRing(colors: DiscColors, fraction: Float) {
    val radius = RingRadius.toPx()
    val stroke = RingStroke.toPx()
    drawCircle(colors.disc, DiscRadius.toPx())
    drawCircle(colors.track, radius, style = Stroke(stroke))
    if (fraction > 0f) {
        drawArc(
            color = colors.arc,
            startAngle = TopOfCircle,
            sweepAngle = WholeCircle * fraction,
            useCenter = false,
            topLeft = Offset(center.x - radius, center.y - radius),
            size = Size(radius * 2, radius * 2),
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
    }
}

private fun DrawScope.drawDashedCircle(color: Color) {
    val dash = DashLength.toPx()
    drawCircle(
        color = color,
        radius = RingRadius.toPx(),
        style = Stroke(DashedStroke.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash))),
    )
}
