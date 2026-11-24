package app.tfdn.lirelle.feature.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.reader.settings.ReaderFont
import app.tfdn.lirelle.core.reader.settings.ReaderTheme

/**
 * The five themes, each a disc in its own page colour crossed by a stroke in its text colour;
 * "Auto" is half light, half dark. The chosen one has a thick ring and its name in bold, and
 * says so to a screen reader: the choice is not told by colour alone.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ThemeSwatches(selected: ReaderTheme, onSelect: (ReaderTheme) -> Unit) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ReaderTheme.entries.forEach { theme ->
            ThemeSwatch(theme, selected = theme == selected, onSelect = { onSelect(theme) })
        }
    }
}

@Composable
private fun ThemeSwatch(theme: ReaderTheme, selected: Boolean, onSelect: () -> Unit) {
    Column(
        modifier = Modifier
            .clip(MaterialTheme.shapes.medium)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .widthIn(min = 56.dp)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ThemeDisc(theme, selected)
        Text(
            text = stringResource(theme.label),
            fontWeight = if (selected) FontWeight.Bold else null,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ThemeDisc(theme: ReaderTheme, selected: Boolean) {
    // "Auto" shows both of its faces, the one in use on the left.
    val dark = isSystemInDarkTheme()
    val left = theme.colours(systemDark = dark)
    val right = if (theme == ReaderTheme.System) theme.colours(systemDark = !dark) else left
    val ring = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    val ringWidth = if (selected) 3.dp else 1.dp
    Box(
        Modifier
            .size(44.dp)
            .drawBehind {
                val stroke = ringWidth.toPx()
                val inset = stroke + if (selected) 2.dp.toPx() else 0f
                val disc = Size(size.width - 2 * inset, size.height - 2 * inset)
                val corner = Offset(inset, inset)
                drawArc(left.background, startAngle = 90f, sweepAngle = 180f, useCenter = true, corner, disc)
                drawArc(right.background, startAngle = 270f, sweepAngle = 180f, useCenter = true, corner, disc)
                drawInk(left.foreground, right.foreground)
                drawCircle(ring, radius = (size.minDimension - stroke) / 2, style = Stroke(stroke))
            },
    )
}

/** A short stroke across the disc, as a line of text in the theme's text colour. */
private fun DrawScope.drawInk(left: Color, right: Color) {
    val length = size.width * InkLength
    val thickness = size.height * InkThickness
    val top = (size.height - thickness) / 2
    val start = (size.width - length) / 2
    val radius = CornerRadius(thickness / 2)
    drawRoundRect(left, Offset(start, top), Size(length, thickness), radius)
    if (right != left) {
        drawRect(right, Offset(size.width / 2, top), Size(length / 2 - thickness / 2, thickness))
        drawCircle(right, thickness / 2, Offset(start + length - thickness / 2, size.height / 2))
    }
}

private const val InkLength = 0.4f
private const val InkThickness = 0.1f

/** The fonts offered; the first one imposes nothing and can always be chosen. Long names wrap. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FontChoices(selected: ReaderFont, onSelect: (ReaderFont) -> Unit) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ReaderFont.entries.forEach { font ->
            val chosen = font == selected
            Surface(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .selectable(selected = chosen, role = Role.RadioButton, onClick = { onSelect(font) }),
                shape = RoundedCornerShape(12.dp),
                color = if (chosen) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                border = BorderStroke(
                    width = if (chosen) 2.dp else 1.dp,
                    color = if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                ),
            ) {
                Box(Modifier.widthIn(min = 48.dp).padding(horizontal = 14.dp, vertical = 14.dp), Alignment.Center) {
                    Text(
                        text = stringResource(font.label),
                        fontWeight = if (chosen) FontWeight.Bold else null,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}
