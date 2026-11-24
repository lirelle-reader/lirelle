package app.tfdn.lirelle.feature.reader

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.reader.marks.HighlightColour

private val DotSize = 26.dp
private val RingWidth = 2.dp
private val EdgeWidth = 1.dp

/** Between the dot and its ring: the surface shows through, so the ring reads on any colour. */
private val RingGap = 2.dp

/**
 * The four colours of highlights, each a dot said by its name. The one in [current] (`#RRGGBB`,
 * any case) is ringed and selected; a colour of elsewhere, or none, marks none.
 *
 * @param choice the dots are a choice of one (the colour of a highlight), said as radio buttons in
 *   a group; otherwise each is a button that acts at once.
 */
@Composable
internal fun ColourDots(
    current: String?,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
    choice: Boolean = false,
) {
    Row(if (choice) modifier.selectableGroup() else modifier) {
        HighlightColour.entries.forEach { colour ->
            val spoken = stringResource(colour.spoken)
            val selected = current.equals(colour.hex, ignoreCase = true)
            Box(
                modifier = Modifier
                    .size(MinTarget)
                    .clip(CircleShape)
                    .selectable(
                        selected = selected,
                        role = if (choice) Role.RadioButton else Role.Button,
                        onClick = { onPick(colour.hex) },
                    )
                    .semantics { contentDescription = spoken },
                contentAlignment = Alignment.Center,
            ) {
                Dot(colour.colour, selected)
            }
        }
    }
}

/** A ringed dot is the current one; the others have a fine edge, so that a pale one shows on a pale bar. */
@Composable
private fun Dot(colour: Color, ringed: Boolean) {
    val ring = if (ringed) Modifier.border(RingWidth, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier
    val edge = if (ringed) {
        Modifier
    } else {
        Modifier.border(EdgeWidth, MaterialTheme.colorScheme.outlineVariant, CircleShape)
    }
    Box(
        Modifier
            .size(DotSize + (RingWidth + RingGap) * 2)
            .then(ring)
            .padding(RingWidth + RingGap)
            .background(colour, CircleShape)
            .then(edge),
    )
}

private val HighlightColour.colour: Color
    get() = Color(android.graphics.Color.parseColor(hex))

@get:StringRes
private val HighlightColour.spoken: Int
    get() = when (this) {
        HighlightColour.Yellow -> R.string.reader_highlight_yellow
        HighlightColour.Green -> R.string.reader_highlight_green
        HighlightColour.Blue -> R.string.reader_highlight_blue
        HighlightColour.Pink -> R.string.reader_highlight_pink
    }
