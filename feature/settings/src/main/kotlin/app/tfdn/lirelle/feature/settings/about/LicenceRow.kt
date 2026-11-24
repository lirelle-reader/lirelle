package app.tfdn.lirelle.feature.settings.about

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.feature.settings.R
import kotlin.math.max

private val RowMinHeight = 56.dp

/** The room between the names and the chip, when they are side by side. */
private val ChipGapBeside = 12.dp

/** The room between the names and the chip, when the chip is under them. */
private val ChipGapUnder = 6.dp

/** The share of the row a chip takes at most beside the names; a longer one goes under them, where it can wrap. */
private const val ChipMaxShare = 0.5f

/** Where a name made like an artifact's coordinates may break: after a dot, a colon, a slash, a dash. */
private val BreakPlaces = Regex("(?<=[.:/_-])(?=[\\p{L}\\p{N}])")

/** A break that has no width and shows nothing. */
private const val InvisibleBreak = "\u200B"

/**
 * The name with a place to break after each of its dots, colons, slashes and dashes, so that a name such as
 * "androidx.core:core-viewtree" wraps between its parts rather than in the middle of one.
 */
internal fun String.withBreakPlaces(): String = replace(BreakPlaces, InvisibleBreak)

/** The test tag of the row of a component. */
internal fun licenceRowTag(componentId: String): String = "licence-row-$componentId"

/**
 * The row of a component: its name, who made it when the list tells, and a chip with the short name of its
 * licence, followed by the count of the others when it has several.
 */
@Composable
internal fun LicenceRow(component: Component, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val first = component.licences.firstOrNull()
    val others = component.licences.size - 1
    val chip = when {
        first == null -> null
        others > 0 -> stringResource(R.string.licences_more, first.shortName, others)
        else -> first.shortName
    }
    NamesAndChip(
        names = { Names(component) },
        chip = { if (chip != null) LicenceChip(chip) },
        modifier = modifier
            .testTag(licenceRowTag(component.id))
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = RowMinHeight)
            .wrapContentHeight()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun Names(component: Component) {
    Column {
        Text(component.name.withBreakPlaces(), style = MaterialTheme.typography.bodyLarge)
        if (component.author != null) {
            Text(
                text = component.author,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LicenceChip(text: String) {
    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/**
 * The names with the chip at the end of the row, or under the names where the longest word of the names would
 * not be whole beside the chip on one line: a narrow window, large text, a long name.
 */
@Composable
private fun NamesAndChip(names: @Composable () -> Unit, chip: @Composable () -> Unit, modifier: Modifier = Modifier) {
    Layout(
        content = {
            Box { names() }
            Box { chip() }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val (namesBox, chipBox) = measurables
        val width = constraints.maxWidth
        // The chip on one line.
        val chipWidth = chipBox.maxIntrinsicWidth(Constraints.Infinity)
        // The least width of a text is that of its longest word.
        val longestWord = namesBox.minIntrinsicWidth(Constraints.Infinity)
        val gapBeside = if (chipWidth > 0) ChipGapBeside.roundToPx() else 0
        val beside = chipWidth <= width * ChipMaxShare && longestWord + gapBeside + chipWidth <= width
        if (beside) {
            val chipPlaced = chipBox.measure(Constraints(maxWidth = chipWidth))
            val namesPlaced = namesBox.measure(Constraints(maxWidth = max(width - gapBeside - chipPlaced.width, 0)))
            val height = max(namesPlaced.height, chipPlaced.height)
            layout(width, height) {
                namesPlaced.placeRelative(0, (height - namesPlaced.height) / 2)
                chipPlaced.placeRelative(width - chipPlaced.width, (height - chipPlaced.height) / 2)
            }
        } else {
            val namesPlaced = namesBox.measure(Constraints(maxWidth = width))
            val chipPlaced = chipBox.measure(Constraints(maxWidth = width))
            val gap = ChipGapUnder.roundToPx()
            layout(width, namesPlaced.height + gap + chipPlaced.height) {
                namesPlaced.placeRelative(0, 0)
                chipPlaced.placeRelative(0, namesPlaced.height + gap)
            }
        }
    }
}
